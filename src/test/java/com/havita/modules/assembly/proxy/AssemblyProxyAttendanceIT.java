package com.havita.modules.assembly.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.application.AssemblyProxyEvents;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.domain.VotingDelegation;
import com.havita.modules.assembly.domain.VotingDelegationStatus;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.assembly.infrastructure.VotingDelegationRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Fase 2 / P2 — poderes, delegaciones y asistencia por HTTP sobre el ESQUEMA DE FLYWAY (V100..V103, contenedor propio),
 * con los poderes habilitados solo en este contexto y limite de 2 unidades por apoderado (en la aplicacion no hay
 * limite por defecto). Cubre la exclusividad delegacion/poder en ambos sentidos, la asistencia atomica por poder
 * (APPROVED -> USED), la representacion unica, el quorum, la seguridad entre comunidades, la auditoria y 9 carreras.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AssemblyProxyAttendanceIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("proxy_attendance_it");
    static final Path STORAGE;

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            PG.start();
        }
        try {
            STORAGE = Files.createTempDirectory("havita-p2-proxies-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.locations", () -> "classpath:db/flyway");
        r.add("spring.flyway.table", () -> "havita_schema_history");
        r.add("spring.flyway.baseline-on-migrate", () -> "false");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("havita.assembly-proxies.enabled", () -> "true");
        r.add("havita.assembly-proxies.max-units-per-proxy", () -> "2");
        r.add("storage.provider", () -> "local");
        r.add("storage.local.base-path", STORAGE::toString);
    }

    private static final int ROUNDS = 8;
    static final byte[] PDF = "%PDF-1.7\n% poder sintetico P2\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private AssemblyAttendeeRepository attendeeRepository;
    @Autowired private VotingDelegationRepository delegationRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private AssemblyProxyEvents events;

    private Side a;
    private Side b;
    private Assembly asmA;

    @BeforeEach
    void seed() {
        reset((Object) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(events));
        var c = fixture.create();
        a = c.a();
        b = c.b();
        asmA = assembly(a);
    }

    // ── datos y llamadas ──────────────────────────────────────────────────────

    private Assembly assembly(Side s) {
        Assembly x = Assembly.builder().title("Asamblea P2").assemblyDate(LocalDate.now().plusDays(3)).startTime(LocalTime.NOON).build();
        x.setTenantId(s.tenantId());
        return assemblyRepository.save(x);
    }

    private User member(Side s, RoleType role) {
        User u = userRepository.save(User.builder().email("p2-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}x").fullName("Persona P2").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(s.tenantId()).role(role).status(MembershipStatus.ACTIVE).build());
        return u;
    }

    private Resident relation(Side s, Property unit, User u, String type) {
        Resident r = Resident.builder().property(unit).user(u).name(u.getFullName()).type(type).email(u.getEmail())
                .status(ResidentStatus.ACTIVE).build();
        r.setTenantId(s.tenantId());
        return residentRepository.save(r);
    }

    /** Arrendatario activo con cuenta en la unidad (al que se puede delegar el voto). */
    private User tenantOf(Side s, Property unit) {
        User t = member(s, RoleType.RESIDENT);
        relation(s, unit, t, "Arrendatario");
        return t;
    }

    private static String doc() {
        return "P2" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private MockHttpServletResponse send(User u, Side s, MockHttpServletRequestBuilder req) throws Exception {
        return send(fixture.bearer(u), s, req);
    }

    private MockHttpServletResponse send(String bearer, Side s, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", bearer).header("X-Tenant-ID", s.tenantId().toString())).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse r) throws Exception {
        return objectMapper.readTree(r.getContentAsString());
    }

    private void assertCode(MockHttpServletResponse r, int status, String code) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        if (code != null) assertThat(json(r).path("code").asText()).isEqualTo(code);
    }

    private UUID requestProxy(User grantor, Side s, Assembly asm, Property unit, UUID proxyUserId, String docNumber) throws Exception {
        String data = "{\"propertyId\":\"" + unit.getId() + "\"" + (proxyUserId != null ? ",\"proxyUserId\":\"" + proxyUserId + "\"" : "")
                + ",\"proxyFullName\":\"Apoderada P2\",\"proxyDocumentType\":\"CC\",\"proxyDocumentNumber\":\"" + docNumber + "\"}";
        MockHttpServletResponse r = send(grantor, s, multipart("/api/v1/assemblies/{a}/proxies", asm.getId())
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, data.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("document", "poder.pdf", "application/pdf", PDF)));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return UUID.fromString(json(r).at("/data/id").asText());
    }

    private MockHttpServletResponse approve(Side s, Assembly asm, UUID proxy) throws Exception {
        return send(s.admin(), s, post("/api/v1/assemblies/{a}/proxies/{p}/approve", asm.getId(), proxy));
    }

    private UUID approvedProxy(Side s, Assembly asm, User grantor, Property unit, UUID proxyUserId, String docNumber) throws Exception {
        UUID id = requestProxy(grantor, s, asm, unit, proxyUserId, docNumber);
        assertCode(approve(s, asm, id), 200, null);
        return id;
    }

    private MockHttpServletResponse revokeProxy(User u, Side s, Assembly asm, UUID proxy) throws Exception {
        return send(u, s, post("/api/v1/assemblies/{a}/proxies/{p}/revoke", asm.getId(), proxy));
    }

    private MockHttpServletResponse attendByAdmin(Side s, Assembly asm, UUID proxy, String docType, String docNumber) throws Exception {
        return send(s.admin(), s, post("/api/v1/assemblies/{a}/proxies/{p}/attendance", asm.getId(), proxy)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentType\":\"" + docType + "\",\"documentNumber\":\"" + docNumber + "\"}"));
    }

    private MockHttpServletResponse attendSelf(User u, Side s, Assembly asm, UUID proxy) throws Exception {
        return send(u, s, post("/api/v1/assemblies/{a}/proxies/{p}/attendance/self", asm.getId(), proxy));
    }

    private MockHttpServletResponse delegate(User owner, Side s, Property unit) throws Exception {
        return send(owner, s, post("/api/v1/properties/{p}/voting-delegation", unit.getId()));
    }

    private MockHttpServletResponse revokeDelegation(User owner, Side s, Property unit) throws Exception {
        return send(owner, s, delete("/api/v1/properties/{p}/voting-delegation", unit.getId()));
    }

    private MockHttpServletResponse selfRegister(User u, Side s, Assembly asm, Property unit) throws Exception {
        return send(u, s, post("/api/v1/assemblies/{id}/attendees/self-register", asm.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\":\"" + unit.getId() + "\"}"));
    }

    private MockHttpServletResponse adminRegister(Side s, Assembly asm, Property unit, UUID residentId, String extra) throws Exception {
        return send(s.admin(), s, post("/api/v1/assemblies/{id}/attendees", asm.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + unit.getId() + "\",\"residentId\":\"" + residentId + "\"" + (extra != null ? "," + extra : "") + "}"));
    }

    private void start(Side s, Assembly asm) throws Exception {
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/{id}/start", asm.getId())), 200, null);
    }

    private void finish(Side s, Assembly asm) throws Exception {
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/{id}/finish", asm.getId())), 200, null);
    }

    private String proxyStatus(UUID id) {
        return jdbc.queryForObject("select status from assembly_proxies where id = ?", String.class, id);
    }

    private long attendees(Assembly asm, Property unit) {
        Long n = jdbc.queryForObject("select count(*) from assembly_attendees where assembly_id = ? and property_id = ?", Long.class,
                asm.getId(), unit.getId());
        return n == null ? 0 : n;
    }

    private long activeDelegations(Property unit) {
        Long n = jdbc.queryForObject("select count(*) from voting_delegations where property_id = ? and status = 'ACTIVE'", Long.class, unit.getId());
        return n == null ? 0 : n;
    }

    private long events(UUID proxy, String action, String result) {
        Long n = jdbc.queryForObject("select count(*) from assembly_proxy_events where proxy_id = ? and action = ? and result = ?",
                Long.class, proxy, action, result);
        return n == null ? 0 : n;
    }

    /** Invariantes de dominio tras cualquier operacion o carrera. */
    private void assertInvariants(Assembly asm, Property unit) {
        // USED <=> asistencia por apoderado que lo referencia.
        assertThat(jdbc.queryForObject("select count(*) from assembly_proxies p where p.status = 'USED' and not exists "
                + "(select 1 from assembly_attendees x where x.id = p.attendee_id and x.is_power_of_attorney)", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from assembly_attendees x where x.is_power_of_attorney and x.assembly_id = ? "
                + "and not exists (select 1 from assembly_proxies p where p.attendee_id = x.id and p.status = 'USED')", Long.class, asm.getId())).isZero();
        // Una representacion por unidad y asamblea.
        assertThat(attendees(asm, unit)).isLessThanOrEqualTo(1);
        // Nunca delegacion ACTIVA con poder APPROVED/USED de una asamblea abierta.
        assertThat(jdbc.queryForObject("select count(*) from voting_delegations d join assembly_proxies p on p.property_id = d.property_id "
                + "join assemblies a on a.id = p.assembly_id where d.status = 'ACTIVE' and p.status in ('APPROVED','USED') "
                + "and a.status <> 'ENDED' and d.property_id = ?", Long.class, unit.getId())).isZero();
    }

    // ── P2-B: delegacion ──────────────────────────────────────────────────────

    @Test
    @DisplayName("delegacion valida sin poder; con poder APROBADO: 409 DELEGATION_BLOCKED_BY_PROXY, sin efectos y auditado")
    void delegationVersusApprovedProxy() throws Exception {
        tenantOf(a, a.home());
        User t2 = tenantOf(a, a.neighbourHome());
        assertCode(delegate(a.neighbour(), a, a.neighbourHome()), 200, null); // valida sin poder

        UUID proxy = approvedProxy(a, asmA, a.owner(), a.home(), null, doc());
        MockHttpServletResponse r = delegate(a.owner(), a, a.home());
        assertCode(r, 409, "DELEGATION_BLOCKED_BY_PROXY");
        assertThat(activeDelegations(a.home())).isZero();
        assertThat(proxyStatus(proxy)).isEqualTo("APPROVED");
        assertThat(events(proxy, "DELEGATION_BLOCKED_BY_PROXY", "DENIED")).isEqualTo(1);
        assertThat(r.getContentAsString()).doesNotContain(proxy.toString()).doesNotContain(asmA.getId().toString());
        assertInvariants(asmA, a.home());
        assertThat(t2).isNotNull();
    }

    @Test
    @DisplayName("delegacion previa (coexistencia heredada) + nueva delegacion bloqueada: la delegacion anterior no se toca")
    void previousDelegationUntouched() throws Exception {
        tenantOf(a, a.home()); // arrendatario actual
        UUID proxy = approvedProxy(a, asmA, a.owner(), a.home(), null, doc());
        // Coexistencia heredada (anterior a P2), simulada directamente: delegacion ACTIVA a un ANTERIOR arrendatario.
        User t2 = member(a, RoleType.RESIDENT);
        VotingDelegation legacy = VotingDelegation.builder().property(a.home()).ownerUser(a.owner()).delegateUser(t2)
                .status(VotingDelegationStatus.ACTIVE).build();
        legacy.setTenantId(a.tenantId());
        legacy = delegationRepository.save(legacy);
        // Delegar al arrendatario actual invalidaria la anterior: el poder lo impide ANTES de cualquier escritura.
        assertCode(delegate(a.owner(), a, a.home()), 409, "DELEGATION_BLOCKED_BY_PROXY");
        VotingDelegation after = delegationRepository.findById(legacy.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(VotingDelegationStatus.ACTIVE);
        assertThat(after.getRevokedAt()).isNull();
        assertThat(proxyStatus(proxy)).isEqualTo("APPROVED");
        assertThat(t2).isNotNull();
    }

    @Test
    @DisplayName("no bloquean la delegacion: poderes en revision, rechazados, revocados ni de asambleas finalizadas")
    void historicalProxiesDoNotBlockDelegation() throws Exception {
        tenantOf(a, a.home());
        // En revision: delegar se permite (el poder no es efectivo); su aprobacion queda bloqueada despues (P1).
        UUID pending = requestProxy(a.owner(), a, asmA, a.home(), null, doc());
        assertCode(delegate(a.owner(), a, a.home()), 200, null);
        assertCode(approve(a, asmA, pending), 409, "PROXY_CONFLICTS_WITH_DELEGATION");
        // Rechazado con delegacion activa: el rechazo se permite.
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{a}/proxies/{p}/reject", asmA.getId(), pending)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Documento incompleto\"}")), 200, null);
        assertCode(revokeDelegation(a.owner(), a, a.home()), 200, null);
        assertCode(delegate(a.owner(), a, a.home()), 200, null); // rechazado: no bloquea
        assertCode(revokeDelegation(a.owner(), a, a.home()), 200, null);
        // Revocado: no bloquea.
        UUID revoked = approvedProxy(a, asmA, a.owner(), a.home(), null, doc());
        assertCode(revokeProxy(a.owner(), a, asmA, revoked), 200, null);
        assertCode(delegate(a.owner(), a, a.home()), 200, null);
        assertCode(revokeDelegation(a.owner(), a, a.home()), 200, null);
        // Aprobado de una asamblea FINALIZADA: historico, no bloquea.
        Assembly ended = assembly(a);
        approvedProxy(a, ended, a.owner(), a.home(), null, doc());
        finish(a, ended);
        assertCode(delegate(a.owner(), a, a.home()), 200, null);
        assertInvariants(asmA, a.home());
    }

    // ── P2-C/D: asistencia mediante poder ─────────────────────────────────────

    @Test
    @DisplayName("asistencia con poder aprobado (administracion, identidad verificada): atomica, USED, POA, quorum y auditoria")
    void attendanceByAdminWithApprovedProxy() throws Exception {
        Property home = propertyRepository.findById(a.home().getId()).orElseThrow();
        home.setCoefficient(new BigDecimal("0.300000"));
        propertyRepository.save(home);
        String docNumber = doc();
        UUID proxy = approvedProxy(a, asmA, a.owner(), a.home(), null, docNumber);
        MockHttpServletResponse wrong = attendByAdmin(a, asmA, proxy, "CC", "OTRO123");
        assertCode(wrong, 409, "PROXY_IDENTITY_MISMATCH");
        assertThat(attendees(asmA, a.home())).isZero();
        assertThat(events(proxy, "PROXY_ATTENDANCE_DENIED", "DENIED")).isEqualTo(1);

        MockHttpServletResponse ok = attendByAdmin(a, asmA, proxy, "CC", docNumber.toLowerCase()); // sin distinguir mayusculas
        assertCode(ok, 201, null);
        assertThat(ok.getContentAsString()).doesNotContain(docNumber);
        assertThat(proxyStatus(proxy)).isEqualTo("USED");
        AssemblyAttendee att = attendeeRepository.findByAssemblyIdAndPropertyId(asmA.getId(), a.home().getId()).orElseThrow();
        assertThat(att.getIsPowerOfAttorney()).isTrue();
        assertThat(att.getResident()).isNull();
        assertThat(att.getRepresentativeName()).isEqualTo("Apoderada P2");
        assertThat(att.getCoefficient()).isEqualByComparingTo("0.300000");
        assertThat(jdbc.queryForObject("select attendee_id from assembly_proxies where id = ?", UUID.class, proxy)).isEqualTo(att.getId());
        assertThat(jdbc.queryForObject("select identity_verified_by_user_id from assembly_proxies where id = ?", UUID.class, proxy))
                .isEqualTo(a.admin().getId());
        assertThat(jdbc.queryForObject("select used_channel from assembly_proxies where id = ?", String.class, proxy)).isEqualTo("ADMIN");
        for (String action : List.of("PROXY_IDENTITY_VERIFIED", "PROXY_ATTENDANCE_REGISTERED", "PROXY_USED")) {
            assertThat(events(proxy, action, "SUCCESS")).as(action).isEqualTo(1);
        }
        Assembly reloaded = assemblyRepository.findById(asmA.getId()).orElseThrow();
        assertThat(reloaded.getRegisteredAttendees()).isEqualTo(1);
        assertThat(reloaded.getQuorumPercentage()).isGreaterThan(BigDecimal.ZERO);
        // Coeficiente original de la unidad intacto. Reutilizacion: 409 y auditada.
        assertThat(propertyRepository.findById(a.home().getId()).orElseThrow().getCoefficient()).isEqualByComparingTo("0.300000");
        assertCode(attendByAdmin(a, asmA, proxy, "CC", docNumber), 409, "PROXY_ALREADY_USED");
        assertThat(jdbc.queryForObject("select count(*) from assembly_proxy_events where proxy_id = ? and reason_code = 'PROXY_ALREADY_USED'",
                Long.class, proxy)).isEqualTo(1);
        // Revocacion despues de la asistencia: rechazada.
        assertCode(revokeProxy(a.owner(), a, asmA, proxy), 409, "PROXY_ALREADY_USED");
        assertInvariants(asmA, a.home());
    }

    @Test
    @DisplayName("apoderado con cuenta: registra su propia asistencia (asamblea en curso); otro usuario 404; inactivo rechazado")
    void attendanceBySelfLinkedProxy() throws Exception {
        UUID proxy = approvedProxy(a, asmA, a.owner(), a.home(), a.neighbour().getId(), doc());
        assertCode(attendSelf(a.neighbour(), a, asmA, proxy), 409, "ASSEMBLY_NOT_IN_PROGRESS"); // programada: solo la administracion
        start(a, asmA);
        assertCode(attendSelf(a.owner(), a, asmA, proxy), 404, null); // el otorgante no usa el poder
        assertCode(attendSelf(a.guard(), a, asmA, proxy), 404, null);
        assertCode(attendSelf(a.neighbour(), a, asmA, proxy), 201, null);
        assertThat(proxyStatus(proxy)).isEqualTo("USED");
        assertThat(jdbc.queryForObject("select used_channel from assembly_proxies where id = ?", String.class, proxy)).isEqualTo("PROXY");
        assertThat(jdbc.queryForObject("select identity_verified_at is null from assembly_proxies where id = ?", Boolean.class, proxy)).isTrue();

        // Apoderado con cuenta cuya membresia se desactiva despues de aprobar: ni el ni la administracion usan el poder.
        User proxyUser = member(a, RoleType.STAFF);
        UUID proxy2 = approvedProxy(a, asmA, a.neighbour(), a.neighbourHome(), proxyUser.getId(), doc());
        Membership m = membershipRepository.findByUserIdAndTenantId(proxyUser.getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(attendSelf(proxyUser, a, asmA, proxy2).getStatus()).isIn(401, 403);
        String docNumber = jdbc.queryForObject("select proxy_document_number from assembly_proxies where id = ?", String.class, proxy2);
        assertCode(attendByAdmin(a, asmA, proxy2, "CC", docNumber), 409, "PROXY_USER_NOT_ACTIVE_MEMBER");
        assertThat(proxyStatus(proxy2)).isEqualTo("APPROVED");
        assertThat(attendees(asmA, a.neighbourHome())).isZero();
    }

    @Test
    @DisplayName("poder en revision, rechazado, revocado o vencido: no registra asistencia (409) y queda auditado")
    void attendanceWithNonApprovedProxy() throws Exception {
        UUID pending = requestProxy(a.owner(), a, asmA, a.home(), null, doc());
        assertCode(attendByAdmin(a, asmA, pending, "CC", "X1"), 409, "PROXY_NOT_APPROVED");
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{a}/proxies/{p}/reject", asmA.getId(), pending)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Firma ilegible\"}")), 200, null);
        assertCode(attendByAdmin(a, asmA, pending, "CC", "X1"), 409, "PROXY_NOT_APPROVED");
        UUID revoked = approvedProxy(a, asmA, a.owner(), a.home(), null, doc());
        assertCode(revokeProxy(a.admin(), a, asmA, revoked), 200, null);
        assertCode(attendByAdmin(a, asmA, revoked, "CC", "X1"), 409, "PROXY_NOT_APPROVED");
        assertThat(events(pending, "PROXY_ATTENDANCE_DENIED", "DENIED")).isEqualTo(2);
        assertThat(events(revoked, "PROXY_ATTENDANCE_DENIED", "DENIED")).isEqualTo(1);

        Assembly other = assembly(a);
        UUID expiring = approvedProxy(a, other, a.owner(), a.home(), null, doc());
        finish(a, other);
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{a}/proxies/expire", other.getId())), 200, null);
        assertCode(attendByAdmin(a, other, expiring, "CC", "X1"), 409, "PROXY_NOT_APPROVED");
        assertThat(attendees(asmA, a.home()) + attendees(other, a.home())).isZero();
    }

    @Test
    @DisplayName("poder de otra comunidad o de otra asamblea: 404; fabricar representacion por parametros: rechazado")
    void crossTenantAndFabrication() throws Exception {
        Assembly asmB = assembly(b);
        String docNumber = doc();
        UUID proxyB = approvedProxy(b, asmB, b.owner(), b.home(), null, docNumber);
        MockHttpServletResponse foreign = attendByAdmin(a, asmB, proxyB, "CC", docNumber);
        assertCode(foreign, 404, null);
        assertThat(foreign.getContentAsString()).doesNotContain(b.tenantId().toString()).doesNotContain(b.home().getNumber());
        assertCode(revokeProxy(a.owner(), a, asmB, proxyB), 404, null);
        assertThat(attendByAdmin(a, asmA, proxyB, "CC", docNumber).getStatus()).isEqualTo(404); // otra asamblea (de A) + poder de B
        Assembly asmA2 = assembly(a);
        UUID proxyA = approvedProxy(a, asmA, a.owner(), a.home(), null, docNumber);
        assertCode(attendByAdmin(a, asmA2, proxyA, "CC", docNumber), 404, null); // poder de otra asamblea
        // Parametros heredados: is_power_of_attorney/representativeName en el registro tradicional siguen rechazados.
        assertCode(adminRegister(a, asmA, a.neighbourHome(), a.neighbourResident().getId(),
                "\"isPowerOfAttorney\":true,\"representativeName\":\"Fabricado\""), 409, "POWER_OF_ATTORNEY_NOT_SUPPORTED");
        assertThat(attendees(asmA, a.neighbourHome())).isZero();
        assertThat(proxyStatus(proxyB)).isEqualTo("APPROVED");
        assertThat(proxyStatus(proxyA)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("propietario y arrendatario delegado frente a apoderado: una sola representacion; revocar antes permite asistir")
    void traditionalVersusProxy() throws Exception {
        start(a, asmA);
        UUID proxy = approvedProxy(a, asmA, a.owner(), a.home(), null, doc());
        assertCode(selfRegister(a.owner(), a, asmA, a.home()), 409, "ATTENDANCE_BLOCKED_BY_PROXY");
        assertCode(adminRegister(a, asmA, a.home(), a.ownerResident().getId(), null), 409, "ATTENDANCE_BLOCKED_BY_PROXY");
        assertThat(events(proxy, "ATTENDANCE_BLOCKED_BY_PROXY", "DENIED")).isEqualTo(2);
        assertThat(attendees(asmA, a.home())).isZero();
        assertCode(revokeProxy(a.owner(), a, asmA, proxy), 200, null);
        assertCode(selfRegister(a.owner(), a, asmA, a.home()), 201, null);
        // Con la asistencia tradicional registrada, ningun poder nuevo de la unidad.
        assertThat(attendees(asmA, a.home())).isEqualTo(1);

        // Arrendatario delegado (coexistencia heredada) frente a poder aprobado.
        User t = tenantOf(a, a.neighbourHome());
        UUID proxy2 = approvedProxy(a, asmA, a.neighbour(), a.neighbourHome(), null, doc());
        VotingDelegation legacy = VotingDelegation.builder().property(a.neighbourHome()).ownerUser(a.neighbour()).delegateUser(t)
                .status(VotingDelegationStatus.ACTIVE).build();
        legacy.setTenantId(a.tenantId());
        delegationRepository.save(legacy);
        assertCode(selfRegister(t, a, asmA, a.neighbourHome()), 409, "ATTENDANCE_BLOCKED_BY_PROXY");
        String d2 = jdbc.queryForObject("select proxy_document_number from assembly_proxies where id = ?", String.class, proxy2);
        assertCode(attendByAdmin(a, asmA, proxy2, "CC", d2), 409, "PROXY_CONFLICTS_WITH_DELEGATION");
        assertThat(attendees(asmA, a.neighbourHome())).isZero();
    }

    @Test
    @DisplayName("asamblea finalizada: no se registra asistencia por poder; nunca se modifican asistencias historicas")
    void endedAssembly() throws Exception {
        String docNumber = doc();
        UUID proxy = approvedProxy(a, asmA, a.owner(), a.home(), null, docNumber);
        finish(a, asmA);
        assertCode(attendByAdmin(a, asmA, proxy, "CC", docNumber), 409, "ASSEMBLY_ENDED");
        assertThat(proxyStatus(proxy)).isEqualTo("APPROVED");
        assertThat(attendees(asmA, a.home())).isZero();
    }

    @Test
    @DisplayName("administrador desactivado y JWT obsoleto (permiso retirado despues de emitir el token): denegado contra PostgreSQL")
    void disabledAdminAndStaleJwt() throws Exception {
        String docNumber = doc();
        UUID proxy = approvedProxy(a, asmA, a.owner(), a.home(), null, docNumber);
        String staleToken = fixture.bearer(a.admin()); // emitido con COMMUNITY_ADMIN vigente
        Membership m = membershipRepository.findByUserIdAndTenantId(a.admin().getId(), a.tenantId()).orElseThrow();
        m.setRole(RoleType.STAFF); // degradado despues de emitir el token
        membershipRepository.save(m);
        MockHttpServletResponse stale = send(staleToken, a, post("/api/v1/assemblies/{a}/proxies/{p}/attendance", asmA.getId(), proxy)
                .contentType(MediaType.APPLICATION_JSON).content("{\"documentType\":\"CC\",\"documentNumber\":\"" + docNumber + "\"}"));
        assertThat(stale.getStatus()).as(stale.getContentAsString()).isIn(401, 403);
        m.setRole(RoleType.COMMUNITY_ADMIN);
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(send(staleToken, a, post("/api/v1/assemblies/{a}/proxies/{p}/attendance", asmA.getId(), proxy)
                .contentType(MediaType.APPLICATION_JSON).content("{\"documentType\":\"CC\",\"documentNumber\":\"" + docNumber + "\"}"))
                .getStatus()).isIn(401, 403);
        assertThat(proxyStatus(proxy)).isEqualTo("APPROVED");
        assertThat(attendees(asmA, a.home())).isZero();
    }

    @Test
    @DisplayName("un apoderado, varias unidades (limite 2): cada unidad una vez en el quorum; la tercera aprobacion bloqueada")
    void multipleUnitsAndQuorum() throws Exception {
        for (Property p : List.of(a.home(), a.neighbourHome(), a.vacant())) {
            Property x = propertyRepository.findById(p.getId()).orElseThrow();
            x.setCoefficient(p.getId().equals(a.vacant().getId()) ? new BigDecimal("0.500000") : new BigDecimal("0.250000"));
            propertyRepository.save(x);
        }
        String same = doc();
        UUID p1 = approvedProxy(a, asmA, a.owner(), a.home(), null, same);
        UUID p2 = approvedProxy(a, asmA, a.neighbour(), a.neighbourHome(), null, same);
        User vacantOwner = member(a, RoleType.RESIDENT);
        relation(a, a.vacant(), vacantOwner, "Propietario");
        UUID p3 = requestProxy(vacantOwner, a, asmA, a.vacant(), null, same);
        assertCode(approve(a, asmA, p3), 409, "PROXY_REPRESENTATION_LIMIT_REACHED");
        assertCode(attendByAdmin(a, asmA, p1, "CC", same), 201, null);
        assertCode(attendByAdmin(a, asmA, p2, "CC", same), 201, null);
        assertCode(attendByAdmin(a, asmA, p1, "CC", same), 409, "PROXY_ALREADY_USED");
        Assembly r = assemblyRepository.findById(asmA.getId()).orElseThrow();
        assertThat(r.getRegisteredAttendees()).isEqualTo(2);
        BigDecimal total = jdbc.queryForObject("select coalesce(sum(coefficient),0) from properties where tenant_id = ?", BigDecimal.class, a.tenantId());
        BigDecimal attended = jdbc.queryForObject("select coalesce(sum(coefficient),0) from assembly_attendees where assembly_id = ?", BigDecimal.class, asmA.getId());
        assertThat(attended).isEqualByComparingTo("0.500000"); // 0.25 + 0.25, cada unidad una vez
        assertThat(r.getQuorumPercentage()).isEqualByComparingTo(attended.multiply(BigDecimal.valueOf(100)).divide(total, 2, java.math.RoundingMode.HALF_UP));
        assertInvariants(asmA, a.home());
        assertInvariants(asmA, a.neighbourHome());
    }

    @Test
    @DisplayName("P2 no habilita el voto por poder: nadie vota con la asistencia del apoderado; no se reasigna a otra persona")
    void noVotingThroughProxyAttendanceYet() throws Exception {
        start(a, asmA);
        UUID proxy = approvedProxy(a, asmA, a.owner(), a.home(), a.neighbour().getId(), doc());
        assertCode(attendSelf(a.neighbour(), a, asmA, proxy), 201, null);
        MockHttpServletResponse poll = send(a.admin(), a, post("/api/v1/assemblies/polls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assemblyId\":\"" + asmA.getId() + "\",\"question\":\"Aprobar presupuesto\",\"type\":\"SINGLE_CHOICE\",\"options\":[\"Si\",\"No\"]}"));
        assertThat(poll.getStatus()).as(poll.getContentAsString()).isIn(200, 201);
        UUID pollId = UUID.fromString(json(poll).at("/data/id").asText());
        UUID optionId = UUID.fromString(json(poll).at("/data/options/0/id").asText());
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/polls/{p}/open", pollId)), 200, null);
        String vote = "{\"propertyId\":\"" + a.home().getId() + "\",\"optionIds\":[\"" + optionId + "\"]}";
        for (User u : List.of(a.owner(), a.neighbour())) {
            MockHttpServletResponse r = send(u, a, post("/api/v1/assemblies/polls/{p}/vote", pollId).contentType(MediaType.APPLICATION_JSON).content(vote));
            assertThat(r.getStatus()).as(r.getContentAsString()).isIn(403, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from poll_votes where poll_id = ?", Long.class, pollId)).isZero();
        UUID attendeeId = jdbc.queryForObject("select attendee_id from assembly_proxies where id = ?", UUID.class, proxy);
        assertCode(send(a.admin(), a, put("/api/v1/assemblies/{id}/attendees/{att}/representative", asmA.getId(), attendeeId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"residentId\":\"" + a.ownerResident().getId() + "\"}")),
                409, "POWER_OF_ATTORNEY_NOT_SUPPORTED");
    }

    @Test
    @DisplayName("rollback completo: si falla un paso posterior a insertar la asistencia, no queda asistencia, ni USED, ni quorum")
    void fullRollback() throws Exception {
        String docNumber = doc();
        UUID proxy = approvedProxy(a, asmA, a.owner(), a.home(), null, docNumber);
        AssemblyProxyEvents target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(events);
        doThrow(new IllegalStateException("fallo simulado despues de la asistencia")).when(target).success(
                ArgumentMatchers.eq(a.tenantId()), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(proxy),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("PROXY_USED"), ArgumentMatchers.any(), ArgumentMatchers.any());
        MockHttpServletResponse r = attendByAdmin(a, asmA, proxy, "CC", docNumber);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(r.getContentAsString()).doesNotContain("fallo simulado");
        assertThat(proxyStatus(proxy)).isEqualTo("APPROVED");
        assertThat(attendees(asmA, a.home())).isZero();
        assertThat(assemblyRepository.findById(asmA.getId()).orElseThrow().getRegisteredAttendees()).isZero();
        assertThat(events(proxy, "PROXY_ATTENDANCE_REGISTERED", "SUCCESS")).isZero();
        reset((Object) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(events));
        assertCode(attendByAdmin(a, asmA, proxy, "CC", docNumber), 201, null); // sin efectos residuales
        assertInvariants(asmA, a.home());
    }

    // ── concurrencia (peticiones simultaneas, transacciones independientes) ───

    private List<MockHttpServletResponse> concurrently(List<Callable<MockHttpServletResponse>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> fs = new ArrayList<>();
        for (Callable<MockHttpServletResponse> c : calls) {
            fs.add(pool.submit(() -> {
                go.await();
                return c.call();
            }));
        }
        go.countDown();
        List<MockHttpServletResponse> out = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        for (MockHttpServletResponse r : out) assertThat(r.getStatus()).as("nunca 5xx: " + r.getContentAsString()).isLessThan(500);
        return out;
    }

    private record World(Side s, Assembly asm) {}

    private World fresh() {
        var c = fixture.create();
        return new World(c.a(), assembly(c.a()));
    }

    private int ok(MockHttpServletResponse r) {
        return r.getStatus() < 300 ? 1 : 0;
    }

    @Test
    @DisplayName("carrera 1 · crear delegacion frente a aprobar poder: exactamente una gana, nunca coexisten (8 rondas)")
    void raceDelegationVersusApproval() throws Exception {
        int[] wins = new int[2];
        for (int round = 0; round < ROUNDS; round++) {
            World w = fresh();
            tenantOf(w.s(), w.s().home());
            UUID proxy = requestProxy(w.s().owner(), w.s(), w.asm(), w.s().home(), null, doc());
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> approve(w.s(), w.asm(), proxy), () -> delegate(w.s().owner(), w.s(), w.s().home())));
            assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
            if (ok(r.get(0)) == 1) {
                wins[0]++;
                assertThat(json(r.get(1)).path("code").asText()).isEqualTo("DELEGATION_BLOCKED_BY_PROXY");
            } else {
                wins[1]++;
                assertThat(json(r.get(0)).path("code").asText()).isEqualTo("PROXY_CONFLICTS_WITH_DELEGATION");
            }
            assertInvariants(w.asm(), w.s().home());
        }
        System.out.printf("P2 carrera 1: aprobacion gana=%d, delegacion gana=%d%n", wins[0], wins[1]);
    }

    @Test
    @DisplayName("carrera 2 · crear delegacion frente a crear poder: compatibles (el poder queda en revision); despues no se aprueba")
    void raceDelegationVersusCreation() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = fresh();
            tenantOf(w.s(), w.s().home());
            String d = doc();
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> send(w.s().owner(), w.s(), multipart("/api/v1/assemblies/{a}/proxies", w.asm().getId())
                            .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, ("{\"propertyId\":\"" + w.s().home().getId()
                                    + "\",\"proxyFullName\":\"Apoderada\",\"proxyDocumentType\":\"CC\",\"proxyDocumentNumber\":\"" + d + "\"}").getBytes(StandardCharsets.UTF_8)))
                            .file(new MockMultipartFile("document", "p.pdf", "application/pdf", PDF))),
                    () -> delegate(w.s().owner(), w.s(), w.s().home())));
            assertThat(r.get(1).getStatus()).as("ronda " + round).isEqualTo(200);
            assertThat(r.get(0).getStatus()).isIn(201, 409);
            if (r.get(0).getStatus() == 201) {
                UUID id = UUID.fromString(json(r.get(0)).at("/data/id").asText());
                assertCode(approve(w.s(), w.asm(), id), 409, "PROXY_CONFLICTS_WITH_DELEGATION");
            } else {
                assertThat(json(r.get(0)).path("code").asText()).isEqualTo("PROXY_CONFLICTS_WITH_DELEGATION");
            }
            assertInvariants(w.asm(), w.s().home());
        }
    }

    @Test
    @DisplayName("carrera 3 · revocar delegacion frente a aprobar poder: nunca poder aprobado con delegacion activa")
    void raceRevokeDelegationVersusApproval() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = fresh();
            tenantOf(w.s(), w.s().home());
            UUID proxy = requestProxy(w.s().owner(), w.s(), w.asm(), w.s().home(), null, doc());
            assertCode(delegate(w.s().owner(), w.s(), w.s().home()), 200, null);
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> approve(w.s(), w.asm(), proxy), () -> revokeDelegation(w.s().owner(), w.s(), w.s().home())));
            assertThat(r.get(1).getStatus()).as("ronda " + round).isEqualTo(200);
            assertThat(r.get(0).getStatus()).isIn(200, 409);
            assertThat(proxyStatus(proxy)).isEqualTo(r.get(0).getStatus() == 200 ? "APPROVED" : "PENDING_REVIEW");
            assertInvariants(w.asm(), w.s().home());
        }
    }

    @Test
    @DisplayName("carrera 4 · revocar poder frente a crear delegacion: la delegacion solo gana si el poder ya estaba revocado")
    void raceRevokeProxyVersusDelegation() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = fresh();
            tenantOf(w.s(), w.s().home());
            UUID proxy = approvedProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), null, doc());
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> revokeProxy(w.s().owner(), w.s(), w.asm(), proxy), () -> delegate(w.s().owner(), w.s(), w.s().home())));
            assertThat(r.get(0).getStatus()).as("ronda " + round).isEqualTo(200);
            assertThat(r.get(1).getStatus()).isIn(200, 409);
            assertThat(proxyStatus(proxy)).isEqualTo("REVOKED");
            assertThat(activeDelegations(w.s().home())).isEqualTo(r.get(1).getStatus() == 200 ? 1 : 0);
            assertInvariants(w.asm(), w.s().home());
        }
    }

    @Test
    @DisplayName("carrera 5 · dos delegaciones simultaneas para la misma unidad: una sola activa")
    void raceTwoDelegations() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = fresh();
            tenantOf(w.s(), w.s().home());
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> delegate(w.s().owner(), w.s(), w.s().home()), () -> delegate(w.s().owner(), w.s(), w.s().home())));
            assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
            assertThat(activeDelegations(w.s().home())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("carrera 6 · revocacion frente a asistencia por poder: o USED con asistencia, o REVOKED sin ella")
    void raceRevocationVersusAttendance() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = fresh();
            String d = doc();
            UUID proxy = approvedProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), null, d);
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> revokeProxy(w.s().owner(), w.s(), w.asm(), proxy), () -> attendByAdmin(w.s(), w.asm(), proxy, "CC", d)));
            assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
            assertThat(proxyStatus(proxy)).isEqualTo(ok(r.get(1)) == 1 ? "USED" : "REVOKED");
            assertThat(attendees(w.asm(), w.s().home())).isEqualTo(ok(r.get(1)));
            assertInvariants(w.asm(), w.s().home());
        }
    }

    @Test
    @DisplayName("carrera 7 · dos asistencias simultaneas con el mismo poder (apoderado y administracion): una sola")
    void raceTwoAttendances() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = fresh();
            start(w.s(), w.asm());
            String d = doc();
            UUID proxy = approvedProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), w.s().neighbour().getId(), d);
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> attendSelf(w.s().neighbour(), w.s(), w.asm(), proxy), () -> attendByAdmin(w.s(), w.asm(), proxy, "CC", d)));
            assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
            assertThat(attendees(w.asm(), w.s().home())).isEqualTo(1);
            assertThat(assemblyRepository.findById(w.asm().getId()).orElseThrow().getRegisteredAttendees()).isEqualTo(1);
            assertInvariants(w.asm(), w.s().home());
        }
    }

    @Test
    @DisplayName("carrera 8 · asistencia por poder frente a finalizacion: nunca una asistencia en una asamblea ya finalizada")
    void raceAttendanceVersusFinish() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = fresh();
            String d = doc();
            UUID proxy = approvedProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), null, d);
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> attendByAdmin(w.s(), w.asm(), proxy, "CC", d),
                    () -> send(w.s().admin(), w.s(), post("/api/v1/assemblies/{id}/finish", w.asm().getId()))));
            assertThat(r.get(1).getStatus()).as("ronda " + round).isEqualTo(200);
            int attended = ok(r.get(0));
            if (attended == 0) assertThat(json(r.get(0)).path("code").asText()).isEqualTo("ASSEMBLY_ENDED");
            assertThat(proxyStatus(proxy)).isEqualTo(attended == 1 ? "USED" : "APPROVED");
            assertThat(attendees(w.asm(), w.s().home())).isEqualTo(attended);
            assertInvariants(w.asm(), w.s().home());
        }
    }

    @Test
    @DisplayName("carrera 9 · asistencia tradicional frente a asistencia por poder: una sola representacion, la del apoderado")
    void raceTraditionalVersusProxyAttendance() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = fresh();
            start(w.s(), w.asm());
            String d = doc();
            UUID proxy = approvedProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), null, d);
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> selfRegister(w.s().owner(), w.s(), w.asm(), w.s().home()), () -> attendByAdmin(w.s(), w.asm(), proxy, "CC", d)));
            assertThat(r.get(1).getStatus()).as("ronda " + round + " " + r.get(1).getContentAsString()).isEqualTo(201);
            assertThat(r.get(0).getStatus()).isEqualTo(409);
            assertThat(json(r.get(0)).path("code").asText()).isIn("ATTENDANCE_BLOCKED_BY_PROXY", "ALREADY_REGISTERED");
            assertThat(attendees(w.asm(), w.s().home())).isEqualTo(1);
            assertInvariants(w.asm(), w.s().home());
        }
    }
}
