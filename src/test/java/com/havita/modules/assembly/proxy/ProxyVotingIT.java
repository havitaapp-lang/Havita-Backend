package com.havita.modules.assembly.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.application.AssemblyVoteEvents;
import com.havita.modules.assembly.application.ProxyVotingService;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.VotingDelegation;
import com.havita.modules.assembly.domain.VotingDelegationStatus;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.assembly.infrastructure.VoteLedgerSchema;
import com.havita.modules.assembly.infrastructure.VotingDelegationRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.domain.PropertyStatus;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Fase 2 / P4 — voto mediante poder, voto asistido, anulacion auditable, V-1/V-2/V-4/V-5 y auditoria de votos.
 *
 * Contenedor PostgreSQL PROPIO (Testcontainers, aislado; nunca una base real) con el esquema de Flyway V100-V104 y la
 * bandera {@code havita.assembly-proxies.enabled=true} SOLO en este contexto de prueba. Datos sinteticos. Las carreras
 * usan peticiones HTTP simultaneas (transacciones independientes) y se repiten 8 rondas; tras cada prueba se comprueban
 * las invariantes de votacion en toda la base.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProxyVotingIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("proxy_voting_it");
    static final Path STORAGE;

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            PG.start();
        }
        try {
            STORAGE = Files.createTempDirectory("havita-p4-votes-");
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
    static final byte[] PDF = "%PDF-1.7\n% poder sintetico P4\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private VotingDelegationRepository delegationRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private VoteLedgerSchema ledgerSchema;
    @Autowired private ProxyVotingService proxyVotingService;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private AssemblyVoteEvents voteEvents;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        reset((Object) AopTestUtils.getUltimateTargetObject(voteEvents));
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    @AfterEach
    void invariants() {
        assertVotingInvariants();
    }

    // ── datos y llamadas ──────────────────────────────────────────────────────

    private record PollRef(UUID id, List<UUID> options) {
        UUID opt(int i) {
            return options.get(i);
        }
    }

    private Assembly assembly(Side s) {
        Assembly x = Assembly.builder().title("Asamblea P4").assemblyDate(LocalDate.now()).startTime(LocalTime.NOON).build();
        x.setTenantId(s.tenantId());
        return assemblyRepository.save(x);
    }

    private Assembly started(Side s) throws Exception {
        Assembly x = assembly(s);
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/{id}/start", x.getId())), 200, null);
        return x;
    }

    private PollRef poll(Side s, Assembly asm, String type, int options) throws Exception {
        StringBuilder opts = new StringBuilder();
        for (int i = 0; i < options; i++) opts.append(i > 0 ? "," : "").append("\"Opcion ").append(i).append('"');
        MockHttpServletResponse r = send(s.admin(), s, post("/api/v1/assemblies/polls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assemblyId\":\"" + asm.getId() + "\",\"question\":\"Pregunta P4\",\"type\":\"" + type + "\",\"options\":[" + opts + "]}"));
        assertThat(r.getStatus()).as(r.getContentAsString()).isIn(200, 201);
        UUID id = UUID.fromString(json(r).at("/data/id").asText());
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/polls/{p}/open", id)), 200, null);
        List<UUID> ids = new ArrayList<>();
        for (JsonNode o : json(r).at("/data/options")) ids.add(UUID.fromString(o.path("id").asText()));
        return new PollRef(id, ids);
    }

    private PollRef poll(Side s, Assembly asm) throws Exception {
        return poll(s, asm, "SINGLE_CHOICE", 2);
    }

    private User member(Side s, RoleType role) {
        User u = userRepository.save(User.builder().email("p4-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}x").fullName("Persona P4").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(s.tenantId()).role(role).status(MembershipStatus.ACTIVE).build());
        return u;
    }

    private Resident relation(Side s, Property unit, User u, String type) {
        Resident r = Resident.builder().property(unit).user(u).name(u.getFullName()).type(type).email(u.getEmail())
                .status(ResidentStatus.ACTIVE).build();
        r.setTenantId(s.tenantId());
        return residentRepository.save(r);
    }

    /** Unidad ocupada con su propietario (cuenta RESIDENT) y coeficiente. */
    private record Owned(Property unit, User owner) {}

    private Owned ownedUnit(Side s, String coefficient) {
        Property p = Property.builder().number("P4-" + UUID.randomUUID().toString().substring(0, 6)).name("Apto P4")
                .status(PropertyStatus.OCCUPIED).coefficient(new BigDecimal(coefficient)).build();
        p.setTenantId(s.tenantId());
        p = propertyRepository.save(p);
        User owner = member(s, RoleType.RESIDENT);
        relation(s, p, owner, "Propietario");
        return new Owned(p, owner);
    }

    private static String doc() {
        return "P4" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
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

    private String code(MockHttpServletResponse r) throws Exception {
        return json(r).path("code").asText();
    }

    private static String ids(UUID... options) {
        StringBuilder sb = new StringBuilder();
        for (UUID o : options) sb.append(sb.length() > 0 ? "," : "").append('"').append(o).append('"');
        return sb.toString();
    }

    private MockHttpServletResponse vote(User u, Side s, PollRef p, Property unit, UUID... options) throws Exception {
        return send(u, s, post("/api/v1/assemblies/polls/{id}/vote", p.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + unit.getId() + "\",\"optionIds\":[" + ids(options) + "]}"));
    }

    private MockHttpServletResponse proxyVote(User u, Side s, PollRef p, UUID proxy, UUID... options) throws Exception {
        return proxyVote(fixture.bearer(u), s, p, proxy, options);
    }

    private MockHttpServletResponse proxyVote(String bearer, Side s, PollRef p, UUID proxy, UUID... options) throws Exception {
        return send(bearer, s, post("/api/v1/assemblies/polls/{id}/proxy-vote", p.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"proxyId\":\"" + proxy + "\",\"optionIds\":[" + ids(options) + "]}"));
    }

    private MockHttpServletResponse assisted(User admin, Side s, PollRef p, UUID proxy, String docNumber, UUID... options) throws Exception {
        return assisted(fixture.bearer(admin), s, p, proxy, "CC", docNumber, true, options);
    }

    private MockHttpServletResponse assisted(String bearer, Side s, PollRef p, UUID proxy, String docType, String docNumber,
                                             Boolean confirmed, UUID... options) throws Exception {
        return send(bearer, s, post("/api/v1/assemblies/polls/{id}/assisted-vote", p.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"proxyId\":\"" + proxy + "\",\"documentType\":\"" + docType + "\",\"documentNumber\":\"" + docNumber
                        + "\",\"expressedByProxyConfirmed\":" + confirmed + ",\"optionIds\":[" + ids(options) + "]}"));
    }

    private MockHttpServletResponse voidVote(User u, Side s, PollRef p, UUID participation, String reason) throws Exception {
        return voidVote(fixture.bearer(u), s, p, participation, reason);
    }

    private MockHttpServletResponse voidVote(String bearer, Side s, PollRef p, UUID participation, String reason) throws Exception {
        return send(bearer, s, post("/api/v1/assemblies/polls/{id}/participations/{x}/void", p.id(), participation)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}"));
    }

    private MockHttpServletResponse selfRegister(User u, Side s, Assembly asm, Property unit) throws Exception {
        return send(u, s, post("/api/v1/assemblies/{id}/attendees/self-register", asm.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\":\"" + unit.getId() + "\"}"));
    }

    private UUID requestProxy(User grantor, Side s, Assembly asm, Property unit, UUID proxyUserId, String docNumber) throws Exception {
        String data = "{\"propertyId\":\"" + unit.getId() + "\"" + (proxyUserId != null ? ",\"proxyUserId\":\"" + proxyUserId + "\"" : "")
                + ",\"proxyFullName\":\"Apoderada P4\",\"proxyDocumentType\":\"CC\",\"proxyDocumentNumber\":\"" + docNumber + "\"}";
        MockHttpServletResponse r = send(grantor, s, multipart("/api/v1/assemblies/{a}/proxies", asm.getId())
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, data.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("document", "poder.pdf", "application/pdf", PDF)));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return UUID.fromString(json(r).at("/data/id").asText());
    }

    private UUID approvedProxy(Side s, Assembly asm, User grantor, Property unit, UUID proxyUserId, String docNumber) throws Exception {
        UUID id = requestProxy(grantor, s, asm, unit, proxyUserId, docNumber);
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/{a}/proxies/{p}/approve", asm.getId(), id)), 200, null);
        return id;
    }

    /** Poder del apoderado CON CUENTA, aprobado y utilizado (asistencia propia; asamblea en curso). */
    private UUID usedAccountProxy(Side s, Assembly asm, User grantor, Property unit, User proxyUser) throws Exception {
        UUID id = approvedProxy(s, asm, grantor, unit, proxyUser.getId(), doc());
        assertCode(send(proxyUser, s, post("/api/v1/assemblies/{a}/proxies/{p}/attendance/self", asm.getId(), id)), 201, null);
        return id;
    }

    /** Poder de un apoderado EXTERNO, aprobado y utilizado (asistencia presencial registrada por la administracion). */
    private UUID usedExternalProxy(Side s, Assembly asm, User grantor, Property unit, String docNumber) throws Exception {
        UUID id = approvedProxy(s, asm, grantor, unit, null, docNumber);
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/{a}/proxies/{p}/attendance", asm.getId(), id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"documentType\":\"CC\",\"documentNumber\":\"" + docNumber + "\"}")), 201, null);
        return id;
    }

    private UUID participationId(MockHttpServletResponse r) throws Exception {
        return UUID.fromString(json(r).at("/data/participationId").asText());
    }

    private Map<String, Object> participation(UUID id) {
        return jdbc.queryForMap("select * from poll_participations where id = ?", id);
    }

    private long participations(PollRef p, Property unit) {
        Long n = jdbc.queryForObject("select count(*) from poll_participations where poll_id = ? and property_id = ?", Long.class, p.id(), unit.getId());
        return n == null ? 0 : n;
    }

    private long voteRows(PollRef p, Property unit) {
        Long n = jdbc.queryForObject("select count(*) from poll_votes where poll_id = ? and property_id = ?", Long.class, p.id(), unit.getId());
        return n == null ? 0 : n;
    }

    private long events(PollRef p, String action, String reasonCode) {
        Long n = reasonCode == null
                ? jdbc.queryForObject("select count(*) from assembly_vote_events where poll_id = ? and action = ?", Long.class, p.id(), action)
                : jdbc.queryForObject("select count(*) from assembly_vote_events where poll_id = ? and action = ? and reason_code = ?",
                        Long.class, p.id(), action, reasonCode);
        return n == null ? 0 : n;
    }

    private void setMembership(User u, Side s, MembershipStatus status) {
        Membership m = membershipRepository.findByUserIdAndTenantId(u.getId(), s.tenantId()).orElseThrow();
        m.setStatus(status);
        membershipRepository.save(m);
    }

    /**
     * Invariantes de votacion en TODA la base (tras cada prueba): nunca dos participaciones vigentes por unidad y
     * pregunta; ninguna participacion sin votos; SINGLE_CHOICE con una sola opcion; solo votos asistidos anulados;
     * votos vigentes de una unidad de una sola participacion; poder USED con la misma asistencia; un evento de exito
     * por participacion (y por anulacion); ningun voto despues del cierre de su pregunta.
     */
    private void assertVotingInvariants() {
        assertThat(jdbc.queryForObject("select count(*) from (select poll_id, property_id from poll_participations "
                + "where status = 'VALID' group by 1, 2 having count(*) > 1) x", Long.class)).as("participaciones vigentes duplicadas").isZero();
        assertThat(jdbc.queryForObject("select count(*) from poll_participations pp where not exists "
                + "(select 1 from poll_votes v where v.participation_id = pp.id)", Long.class)).as("participacion sin votos").isZero();
        assertThat(jdbc.queryForObject("select count(*) from poll_participations pp join polls p on p.id = pp.poll_id "
                + "where upper(p.type) = 'SINGLE_CHOICE' and (select count(*) from poll_votes v where v.participation_id = pp.id) <> 1",
                Long.class)).as("SINGLE_CHOICE con una opcion").isZero();
        assertThat(jdbc.queryForObject("select count(*) from poll_participations where status = 'VOIDED' "
                + "and representation_type <> 'ADMIN_ASSISTED_PROXY'", Long.class)).as("solo votos asistidos anulados").isZero();
        assertThat(jdbc.queryForObject("select count(*) from (select v.poll_id, v.property_id from poll_votes v "
                + "left join poll_participations pp on pp.id = v.participation_id where v.participation_id is null or pp.status = 'VALID' "
                + "group by 1, 2 having count(distinct coalesce(v.participation_id, '00000000-0000-0000-0000-000000000000'::uuid)) > 1) x",
                Long.class)).as("votos vigentes de dos participaciones").isZero();
        assertThat(jdbc.queryForObject("select count(*) from poll_participations pp left join assembly_proxies x on x.id = pp.proxy_id "
                + "where pp.proxy_id is not null and (x.status <> 'USED' or x.attendee_id <> pp.attendee_id)", Long.class))
                .as("participacion por poder sin poder USED en su asistencia").isZero();
        assertThat(jdbc.queryForObject("select count(*) from poll_participations pp where (select count(*) from assembly_vote_events e "
                + "where e.participation_id = pp.id and e.action in ('VOTE_CAST', 'ASSISTED_VOTE_CAST', 'ASSISTED_VOTE_REISSUED')) <> 1", Long.class))
                .as("un evento de emision por participacion").isZero();
        assertThat(jdbc.queryForObject("select count(*) from poll_participations pp where pp.status = 'VOIDED' and (select count(*) "
                + "from assembly_vote_events e where e.participation_id = pp.id and e.action = 'ASSISTED_VOTE_VOIDED') <> 1", Long.class))
                .as("un evento por anulacion").isZero();
        assertThat(jdbc.queryForObject("select count(*) from poll_participations pp join polls p on p.id = pp.poll_id "
                + "where p.closed_at is not null and pp.cast_at > p.closed_at", Long.class)).as("voto despues del cierre").isZero();
        assertThat(jdbc.queryForObject("select count(*) from poll_votes v where v.participation_id is not null and not exists "
                + "(select 1 from poll_participations pp join polls p on p.id = pp.poll_id where pp.id = v.participation_id "
                + "and p.tenant_id = v.tenant_id and pp.property_id = v.property_id)", Long.class)).as("voto fuera de su comunidad").isZero();
    }

    // ── esquema y bandera ─────────────────────────────────────────────────────

    @Test
    @DisplayName("esquema V104 detectado; la bandera solo esta activa en este contexto aislado")
    void schemaDetected() {
        assertThat(ledgerSchema.state()).isEqualTo(VoteLedgerSchema.State.COMPLETE);
        // P5: la cadena incluye V105 (nueva emision), V106 (limites por comunidad y auditoria documental), V107 (chat interno, Fase 3) y V108 (retirada del chat anterior).
        assertThat(jdbc.queryForObject("select max(version::int) from havita_schema_history where version is not null",
                Integer.class)).isEqualTo(108);
    }

    // ── votacion tradicional con participaciones (V-1, V-2) ───────────────────

    @Test
    @DisplayName("propietario, copropietario y arrendatario delegado: participacion con actor y titulo derivados del servidor + evento")
    void directRepresentations() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        // Propietario unico.
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0)), 200, null);
        var owner = jdbc.queryForMap("select * from poll_participations where poll_id = ? and property_id = ?", p.id(), a.home().getId());
        assertThat(owner.get("representation_type")).isEqualTo("OWNER");
        assertThat(owner.get("actor_user_id")).isEqualTo(a.owner().getId());
        assertThat(owner.get("resident_id")).isEqualTo(a.ownerResident().getId());
        assertThat(owner.get("proxy_id")).isNull();
        // Copropietario: la unidad tiene dos propietarios activos.
        Owned co = ownedUnit(a, "0.1");
        User co2 = member(a, RoleType.RESIDENT);
        relation(a, co.unit(), co2, "Propietario");
        assertCode(selfRegister(co2, a, asm, co.unit()), 201, null);
        assertCode(vote(co2, a, p, co.unit(), p.opt(1)), 200, null);
        assertThat(jdbc.queryForObject("select representation_type from poll_participations where poll_id = ? and property_id = ?",
                String.class, p.id(), co.unit().getId())).isEqualTo("CO_OWNER");
        assertCode(vote(co.owner(), a, p, co.unit(), p.opt(0)), 409, "UNAUTHORIZED_VOTE");
        // Arrendatario con delegacion a su favor.
        Owned leased = ownedUnit(a, "0.1");
        User lessee = member(a, RoleType.RESIDENT);
        relation(a, leased.unit(), lessee, "Arrendatario");
        assertCode(send(leased.owner(), a, post("/api/v1/properties/{p}/voting-delegation", leased.unit().getId())), 200, null);
        assertCode(selfRegister(lessee, a, asm, leased.unit()), 201, null);
        assertCode(vote(lessee, a, p, leased.unit(), p.opt(0)), 200, null);
        var del = jdbc.queryForMap("select * from poll_participations where poll_id = ? and property_id = ?", p.id(), leased.unit().getId());
        assertThat(del.get("representation_type")).isEqualTo("TENANT_DELEGATE");
        assertThat(del.get("delegation_id")).isNotNull();
        assertThat(del.get("actor_user_id")).isEqualTo(lessee.getId());
        assertThat(events(p, "VOTE_CAST", null)).isEqualTo(3);
    }

    @Test
    @DisplayName("duplicado (misma u otra opcion): 409 PROPERTY_ALREADY_VOTED auditado; dos opciones en SINGLE_CHOICE: 400")
    void duplicatesAndOptions() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0), p.opt(1)), 400, "INVALID_VOTE_OPTIONS");
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0)), 200, null);
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0)), 409, "PROPERTY_ALREADY_VOTED");
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(1)), 409, "PROPERTY_ALREADY_VOTED");
        assertThat(participations(p, a.home())).isEqualTo(1);
        assertThat(voteRows(p, a.home())).isEqualTo(1);
        assertThat(events(p, "DUPLICATE_VOTE_REJECTED", "PROPERTY_ALREADY_VOTED")).isEqualTo(2);
    }

    @Test
    @DisplayName("seleccion multiple: una participacion con varias opciones; una segunda emision de la unidad se rechaza")
    void multipleChoice() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm, "MULTIPLE_CHOICE", 3);
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0), p.opt(2)), 200, null);
        assertThat(participations(p, a.home())).isEqualTo(1);
        assertThat(voteRows(p, a.home())).isEqualTo(2);
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(1)), 409, "PROPERTY_ALREADY_VOTED");
        // Ni siquiera por SQL directo se anade otra opcion a la participacion despues (otra transaccion).
        UUID part = jdbc.queryForObject("select id from poll_participations where poll_id = ? and property_id = ?", UUID.class, p.id(), a.home().getId());
        Throwable e = catchThrowable(() -> jdbc.update("insert into poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, "
                + "coefficient, voted_at, created_at, updated_at, participation_id) values (gen_random_uuid(), ?, ?, ?, ?, 0, now(), now(), now(), ?)",
                a.tenantId(), p.id(), p.opt(1), a.home().getId(), part));
        assertThat(e).hasMessageContaining("junto con su participacion vigente");
        assertThat(voteRows(p, a.home())).isEqualTo(2);
    }

    @Test
    @DisplayName("sin representacion, usuario desactivado, otra comunidad, pregunta cerrada y asamblea finalizada: rechazados sin votos")
    void rejectedVoters() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        // Sin representacion (vecino de otra unidad) y portero.
        assertThat(vote(a.neighbour(), a, p, a.home(), p.opt(0)).getStatus()).isIn(403, 409);
        assertThat(vote(a.guard(), a, p, a.home(), p.opt(0)).getStatus()).isEqualTo(403);
        // Otra comunidad: con su comunidad la pregunta no existe; con la ajena no es miembro.
        assertThat(vote(b.owner(), b, p, a.home(), p.opt(0)).getStatus()).isEqualTo(404);
        assertThat(vote(b.owner(), a, p, a.home(), p.opt(0)).getStatus()).isIn(401, 403);
        // Usuario desactivado.
        String token = fixture.bearer(a.owner());
        setMembership(a.owner(), a, MembershipStatus.INACTIVE);
        assertThat(send(token, a, post("/api/v1/assemblies/polls/{id}/vote", p.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + a.home().getId() + "\",\"optionIds\":[\"" + p.opt(0) + "\"]}")).getStatus()).isIn(401, 403);
        setMembership(a.owner(), a, MembershipStatus.ACTIVE);
        assertThat(participations(p, a.home())).isZero();
        // Pregunta cerrada.
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/polls/{p}/close", p.id())), 200, null);
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0)), 409, "POLL_NOT_LIVE");
        // Asamblea finalizada.
        PollRef p2 = poll(a, asm);
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{id}/finish", asm.getId())), 200, null);
        assertCode(vote(a.owner(), a, p2, a.home(), p2.opt(0)), 409, "ASSEMBLY_ENDED");
        assertThat(jdbc.queryForObject("select count(*) from poll_participations where poll_id in (?, ?)", Long.class, p.id(), p2.id())).isZero();
    }

    @Test
    @DisplayName("historico: un voto sin participacion (anterior a V104) cuenta y bloquea un segundo voto de la unidad")
    void historicalVoteRespected() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        // Voto "historico" (sin participacion), como los de las bases existentes: no se reescribe ni se completa.
        jdbc.update("insert into poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, resident_id, coefficient, voted_at, "
                + "created_at, updated_at) values (gen_random_uuid(), ?, ?, ?, ?, ?, 0.3, now(), now(), now())",
                a.tenantId(), p.id(), p.opt(1), a.home().getId(), a.ownerResident().getId());
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0)), 409, "PROPERTY_ALREADY_VOTED");
        JsonNode poll = json(send(a.admin(), a, get("/api/v1/assemblies/polls/{p}", p.id())));
        assertThat(poll.at("/data/totalVotes").asLong()).isEqualTo(1);
        assertThat(poll.at("/data/options/1/votesCount").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from poll_votes where poll_id = ? and participation_id is null", Long.class, p.id())).isEqualTo(1);
    }

    // ── V-5: administrador representante ──────────────────────────────────────

    @Test
    @DisplayName("V-5 · administrador propietario, copropietario, delegado y apoderado con cuenta vota; sin representacion no")
    void adminRepresentative() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        // Propietario.
        Property own = ownedUnit(a, "0.1").unit();
        relation(a, own, a.admin(), "Propietario");
        // (la unidad tiene otro propietario: el administrador es copropietario)
        assertCode(selfRegister(a.admin(), a, asm, own), 201, null);
        assertCode(vote(a.admin(), a, p, own, p.opt(0)), 200, null);
        assertThat(jdbc.queryForObject("select representation_type from poll_participations where poll_id = ? and property_id = ?",
                String.class, p.id(), own.getId())).isEqualTo("CO_OWNER");
        // Propietario unico.
        Property solo = Property.builder().number("P4S-" + UUID.randomUUID().toString().substring(0, 6)).name("Apto")
                .status(PropertyStatus.OCCUPIED).build();
        solo.setTenantId(a.tenantId());
        solo = propertyRepository.save(solo);
        relation(a, solo, a.admin(), "Propietario");
        assertCode(selfRegister(a.admin(), a, asm, solo), 201, null);
        assertCode(vote(a.admin(), a, p, solo, p.opt(1)), 200, null);
        var row = jdbc.queryForMap("select * from poll_participations where poll_id = ? and property_id = ?", p.id(), solo.getId());
        assertThat(row.get("representation_type")).isEqualTo("OWNER");
        assertThat(row.get("actor_user_id")).isEqualTo(a.admin().getId());
        // Arrendatario delegado.
        Owned leased = ownedUnit(a, "0.1");
        relation(a, leased.unit(), a.admin(), "Arrendatario");
        assertCode(send(leased.owner(), a, post("/api/v1/properties/{p}/voting-delegation", leased.unit().getId())), 200, null);
        assertCode(selfRegister(a.admin(), a, asm, leased.unit()), 201, null);
        assertCode(vote(a.admin(), a, p, leased.unit(), p.opt(0)), 200, null);
        assertThat(jdbc.queryForObject("select representation_type from poll_participations where poll_id = ? and property_id = ?",
                String.class, p.id(), leased.unit().getId())).isEqualTo("TENANT_DELEGATE");
        // Apoderado con cuenta.
        Owned granted = ownedUnit(a, "0.1");
        UUID proxy = usedAccountProxy(a, asm, granted.owner(), granted.unit(), a.admin());
        assertCode(proxyVote(a.admin(), a, p, proxy, p.opt(1)), 201, null);
        // Sin representacion: la unidad del propietario (registrado) y una unidad sin asistencia.
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        assertCode(vote(a.admin(), a, p, a.home(), p.opt(0)), 409, "ADMIN_VOTE_NOT_SUPPORTED");
        assertCode(vote(a.admin(), a, p, a.neighbourHome(), p.opt(0)), 409, "PROPERTY_NOT_REGISTERED_IN_ASSEMBLY");
        assertThat(participations(p, a.home())).isZero();
        assertThat(events(p, "UNAUTHORIZED_VOTE_REJECTED", "ADMIN_VOTE_NOT_SUPPORTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("V-5 · SUPER_ADMIN sin representacion no vota; la administracion de otra comunidad tampoco")
    void superAdminWithoutRepresentation() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        User root = userRepository.save(User.builder().email("p4-root-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test")
                .passwordHash("{noop}x").fullName("Global P4").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(root).tenantId(null).role(RoleType.SUPER_ADMIN).status(MembershipStatus.ACTIVE).build());
        MockHttpServletResponse r = vote(root, a, p, a.home(), p.opt(0));
        assertThat(r.getStatus()).as(r.getContentAsString()).isIn(403, 409);
        if (r.getStatus() == 409) assertThat(code(r)).isEqualTo("ADMIN_VOTE_NOT_SUPPORTED");
        assertThat(vote(b.admin(), a, p, a.home(), p.opt(0)).getStatus()).isIn(401, 403);
        assertThat(vote(b.admin(), b, p, a.home(), p.opt(0)).getStatus()).isEqualTo(404);
        assertThat(participations(p, a.home())).isZero();
    }

    // ── voto mediante poder (apoderado con cuenta) ────────────────────────────

    @Test
    @DisplayName("apoderado con cuenta: vota por la unidad del poder (ACCOUNT_PROXY), una vez; sin titulo de propietario ni membresia nueva")
    void accountProxyVote() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        long memberships = membershipRepository.findByUserId(a.neighbour().getId()).size();
        UUID proxy = usedAccountProxy(a, asm, a.owner(), a.home(), a.neighbour());
        MockHttpServletResponse r = proxyVote(a.neighbour(), a, p, proxy, p.opt(1));
        assertCode(r, 201, null);
        assertThat(json(r).at("/data/representationType").asText()).isEqualTo("ACCOUNT_PROXY");
        assertThat(r.getContentAsString()).doesNotContain("documentNumber").doesNotContain("proxyDocument");
        var row = participation(participationId(r));
        assertThat(row.get("actor_user_id")).isEqualTo(a.neighbour().getId());
        assertThat(row.get("proxy_id")).isEqualTo(proxy);
        assertThat(row.get("grantor_resident_id")).isEqualTo(a.ownerResident().getId());
        assertThat(row.get("resident_id")).isNull();
        assertThat(row.get("property_id")).isEqualTo(a.home().getId());
        var event = jdbc.queryForMap("select * from assembly_vote_events where participation_id = ?", participationId(r));
        assertThat(event.get("action")).isEqualTo("VOTE_CAST");
        assertThat(event.get("represented_resident_id")).isEqualTo(a.ownerResident().getId());
        assertThat(event.get("representation_type")).isEqualTo("ACCOUNT_PROXY");
        assertCode(proxyVote(a.neighbour(), a, p, proxy, p.opt(0)), 409, "PROPERTY_ALREADY_VOTED");
        // El poder no da titulo: por la ruta directa la unidad no es suya; no se crearon membresias ni relaciones.
        assertThat(vote(a.neighbour(), a, p, a.home(), p.opt(0)).getStatus()).isIn(403, 409);
        assertThat(membershipRepository.findByUserId(a.neighbour().getId())).hasSize((int) memberships);
        assertThat(residentRepository.findByTenantIdAndPropertyId(a.tenantId(), a.home().getId()))
                .noneMatch(x -> x.getUser() != null && x.getUser().getId().equals(a.neighbour().getId()));
    }

    @Test
    @DisplayName("apoderado con varias unidades: un voto por unidad, cada uno con su unidad y coeficiente; resultado agregado")
    void proxyWithSeveralUnits() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        Owned u1 = ownedUnit(a, "0.25");
        Owned u2 = ownedUnit(a, "0.15");
        UUID x1 = usedAccountProxy(a, asm, u1.owner(), u1.unit(), a.neighbour());
        UUID x2 = usedAccountProxy(a, asm, u2.owner(), u2.unit(), a.neighbour());
        assertCode(proxyVote(a.neighbour(), a, p, x1, p.opt(0)), 201, null);
        MockHttpServletResponse r = proxyVote(a.neighbour(), a, p, x2, p.opt(0));
        assertCode(r, 201, null);
        assertThat(participations(p, u1.unit())).isEqualTo(1);
        assertThat(participations(p, u2.unit())).isEqualTo(1);
        assertThat(json(r).at("/data/poll/options/0/coefficientSum").decimalValue()).isEqualByComparingTo("0.40");
        assertCode(proxyVote(a.neighbour(), a, p, x1, p.opt(1)), 409, "PROPERTY_ALREADY_VOTED");
        // P5-D (decision aprobada): el limite NO es retroactivo. Si la comunidad lo reduce despues de usar los poderes,
        // los votos de esos poderes siguen admitiendose (el limite se evalua al aprobar y al usar el poder).
        assertCode(send(a.admin(), a, put("/api/v1/assemblies/proxy-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxUnitsPerProxy\":1}")), 200, null);
        PollRef p2 = poll(a, asm);
        assertCode(proxyVote(a.neighbour(), a, p2, x1, p2.opt(0)), 201, null);
        assertCode(proxyVote(a.neighbour(), a, p2, x2, p2.opt(1)), 201, null);
        assertThat(events(p2, "UNAUTHORIZED_VOTE_REJECTED", "PROXY_REPRESENTATION_LIMIT_REACHED")).isZero();
    }

    @Test
    @DisplayName("poder APROBADO sin asistencia, REVOCADO, VENCIDO, ajeno, de otra asamblea o comunidad: rechazado sin voto")
    void invalidProxies() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        // Aprobado sin asistencia: la unidad no tiene asistencia.
        UUID approved = approvedProxy(a, asm, a.owner(), a.home(), a.neighbour().getId(), doc());
        assertCode(proxyVote(a.neighbour(), a, p, approved, p.opt(0)), 409, "PROPERTY_NOT_REGISTERED_IN_ASSEMBLY");
        // Revocado: la unidad la representa despues su propietario; el poder revocado no vota.
        assertCode(send(a.owner(), a, post("/api/v1/assemblies/{a}/proxies/{p}/revoke", asm.getId(), approved)), 200, null);
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        assertCode(proxyVote(a.neighbour(), a, p, approved, p.opt(0)), 409, "PROXY_NOT_USED");
        assertThat(events(p, "UNAUTHORIZED_VOTE_REJECTED", "PROXY_NOT_USED")).isEqualTo(1);
        // Ajeno: un poder USED de otro apoderado "no existe" para este usuario.
        Owned other = ownedUnit(a, "0.1");
        User someone = member(a, RoleType.STAFF);
        UUID foreign = usedAccountProxy(a, asm, other.owner(), other.unit(), someone);
        assertThat(proxyVote(a.neighbour(), a, p, foreign, p.opt(0)).getStatus()).isEqualTo(404);
        // Otra asamblea: el poder USED de otra asamblea no vale para esta pregunta.
        Assembly asm2 = started(a);
        Owned u2 = ownedUnit(a, "0.1");
        UUID otherAssembly = usedAccountProxy(a, asm2, u2.owner(), u2.unit(), a.neighbour());
        assertThat(proxyVote(a.neighbour(), a, p, otherAssembly, p.opt(0)).getStatus()).isEqualTo(404);
        // Otra comunidad.
        Assembly asmB = started(b);
        UUID proxyB = usedAccountProxy(b, asmB, b.owner(), b.home(), b.neighbour());
        assertThat(proxyVote(a.neighbour(), a, p, proxyB, p.opt(0)).getStatus()).isEqualTo(404);
        assertThat(proxyVote(b.neighbour(), b, p, proxyB, p.opt(0)).getStatus()).isEqualTo(404);
        // Vencido (asamblea finalizada y poderes vencidos): la pregunta de esa asamblea ya no admite votos.
        Assembly asm3 = started(a);
        Owned u3 = ownedUnit(a, "0.1");
        UUID toExpire = approvedProxy(a, asm3, u3.owner(), u3.unit(), a.neighbour().getId(), doc());
        PollRef p3 = poll(a, asm3);
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{id}/finish", asm3.getId())), 200, null);
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{a}/proxies/expire", asm3.getId())), 200, null);
        assertThat(jdbc.queryForObject("select status from assembly_proxies where id = ?", String.class, toExpire)).isEqualTo("EXPIRED");
        assertCode(proxyVote(a.neighbour(), a, p3, toExpire, p3.opt(0)), 409, "ASSEMBLY_ENDED");
        assertThat(jdbc.queryForObject("select count(*) from poll_participations where proxy_id in (?, ?, ?, ?)", Long.class,
                approved, foreign, proxyB, toExpire)).isZero();
    }

    @Test
    @DisplayName("apoderado con membresia inactiva o JWT obsoleto, y delegacion incompatible (defensa): rechazado")
    void proxyMembershipAndDelegation() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        UUID proxy = usedAccountProxy(a, asm, a.owner(), a.home(), a.neighbour());
        String token = fixture.bearer(a.neighbour());
        setMembership(a.neighbour(), a, MembershipStatus.INACTIVE);
        assertThat(proxyVote(token, a, p, proxy, p.opt(0)).getStatus()).isIn(401, 403, 409);
        assertThat(participations(p, a.home())).isZero();
        setMembership(a.neighbour(), a, MembershipStatus.ACTIVE);
        // Una delegacion ACTIVA de la unidad (imposible por las rutas de P2; insertada aqui para probar la defensa).
        User lessee = member(a, RoleType.RESIDENT);
        relation(a, a.home(), lessee, "Arrendatario");
        VotingDelegation d = VotingDelegation.builder().property(a.home()).ownerUser(a.owner()).delegateUser(lessee)
                .status(VotingDelegationStatus.ACTIVE).build();
        d.setTenantId(a.tenantId());
        d = delegationRepository.save(d);
        assertCode(proxyVote(a.neighbour(), a, p, proxy, p.opt(0)), 409, "PROXY_CONFLICTS_WITH_DELEGATION");
        d.setStatus(VotingDelegationStatus.REVOKED);
        delegationRepository.save(d);
        assertCode(proxyVote(a.neighbour(), a, p, proxy, p.opt(0)), 201, null);
    }

    // ── voto asistido (apoderado externo) ─────────────────────────────────────

    @Test
    @DisplayName("voto asistido: administrador como actor, otorgante como representado, verificacion presencial registrada")
    void assistedVote() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        String d = doc();
        UUID proxy = usedExternalProxy(a, asm, a.owner(), a.home(), d);
        MockHttpServletResponse r = assisted(a.admin(), a, p, proxy, d.toLowerCase(), p.opt(0));
        assertCode(r, 201, null);
        assertThat(r.getContentAsString()).doesNotContain(d);
        var row = participation(participationId(r));
        assertThat(row.get("representation_type")).isEqualTo("ADMIN_ASSISTED_PROXY");
        assertThat(row.get("actor_user_id")).isEqualTo(a.admin().getId());
        assertThat(row.get("identity_verified_by_user_id")).isEqualTo(a.admin().getId());
        assertThat(row.get("identity_verified_at")).isNotNull();
        assertThat(row.get("identity_verification_method")).isEqualTo("IN_PERSON_DOCUMENT");
        assertThat(row.get("expressed_by_proxy_confirmed")).isEqualTo(true);
        assertThat(row.get("grantor_resident_id")).isEqualTo(a.ownerResident().getId());
        assertThat(row.get("resident_id")).isNull();
        var event = jdbc.queryForMap("select * from assembly_vote_events where participation_id = ?", participationId(r));
        assertThat(event.get("action")).isEqualTo("ASSISTED_VOTE_CAST");
        assertThat(event.get("actor_user_id")).isEqualTo(a.admin().getId());
        assertThat(event.get("represented_resident_id")).isEqualTo(a.ownerResident().getId());
        // Nada en la auditoria contiene el numero de documento.
        assertThat(jdbc.queryForObject("select count(*) from assembly_vote_events e where cast(row_to_json(e) as text) like ?",
                Long.class, "%" + d + "%")).isZero();
        assertCode(assisted(a.admin(), a, p, proxy, d, p.opt(1)), 409, "PROPERTY_ALREADY_VOTED");
    }

    @Test
    @DisplayName("voto asistido rechazado: apoderado con cuenta, identidad distinta, sin confirmacion, poder no utilizado, "
            + "pregunta cerrada, asamblea no en curso, residente, administrador desactivado o con JWT obsoleto")
    void assistedRejections() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        // P4-R1: apoderado con cuenta.
        UUID account = usedAccountProxy(a, asm, a.owner(), a.home(), a.neighbour());
        assertCode(assisted(a.admin(), a, p, account, "X", p.opt(0)), 409, "ASSISTED_VOTE_ONLY_FOR_EXTERNAL_PROXY");
        assertThat(events(p, "UNAUTHORIZED_VOTE_REJECTED", "ASSISTED_VOTE_ONLY_FOR_EXTERNAL_PROXY")).isEqualTo(1);
        // Identidad distinta y sin confirmacion.
        Owned u = ownedUnit(a, "0.1");
        String d = doc();
        UUID external = usedExternalProxy(a, asm, u.owner(), u.unit(), d);
        assertCode(assisted(a.admin(), a, p, external, d + "9", p.opt(0)), 409, "PROXY_IDENTITY_MISMATCH");
        assertThat(events(p, "UNAUTHORIZED_VOTE_REJECTED", "PROXY_IDENTITY_MISMATCH")).isEqualTo(1);
        assertThat(assisted(fixture.bearer(a.admin()), a, p, external, "CC", d, false, p.opt(0)).getStatus()).isEqualTo(400);
        // Residente: no es administracion.
        assertThat(assisted(a.owner(), a, p, external, d, p.opt(0)).getStatus()).isEqualTo(403);
        // Poder aprobado pero no utilizado (sin asistencia).
        Owned u2 = ownedUnit(a, "0.1");
        String d2 = doc();
        UUID notUsed = approvedProxy(a, asm, u2.owner(), u2.unit(), null, d2);
        assertCode(assisted(a.admin(), a, p, notUsed, d2, p.opt(0)), 409, "PROPERTY_NOT_REGISTERED_IN_ASSEMBLY");
        // JWT obsoleto (degradado despues de emitir el token) y administrador desactivado.
        User admin2 = member(a, RoleType.COMMUNITY_ADMIN);
        String stale = fixture.bearer(admin2);
        Membership m = membershipRepository.findByUserIdAndTenantId(admin2.getId(), a.tenantId()).orElseThrow();
        m.setRole(RoleType.STAFF);
        membershipRepository.save(m);
        assertThat(assisted(stale, a, p, external, "CC", d, true, p.opt(0)).getStatus()).isIn(401, 403);
        m.setRole(RoleType.COMMUNITY_ADMIN);
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(assisted(stale, a, p, external, "CC", d, true, p.opt(0)).getStatus()).isIn(401, 403);
        assertThat(participations(p, u.unit())).isZero();
        // Asamblea programada (no en curso) con pregunta abierta: el voto asistido exige asamblea EN CURSO.
        Assembly scheduled = assembly(a);
        PollRef ps = poll(a, scheduled);
        Owned u3 = ownedUnit(a, "0.1");
        String d3 = doc();
        UUID inScheduled = usedExternalProxy(a, scheduled, u3.owner(), u3.unit(), d3);
        assertCode(assisted(a.admin(), a, ps, inScheduled, d3, ps.opt(0)), 409, "ASSEMBLY_NOT_IN_PROGRESS");
        // Pregunta cerrada.
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/polls/{p}/close", p.id())), 200, null);
        assertCode(assisted(a.admin(), a, p, external, d, p.opt(0)), 409, "POLL_NOT_LIVE");
        assertThat(participations(p, u.unit())).isZero();
    }

    // ── anulacion (V-3) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("anulacion valida: VOIDED con motivo y administrador, votos conservados, resultado sin el voto; nueva emision no autorizada")
    void voidAssisted() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        Property unit = propertyRepository.findById(a.home().getId()).orElseThrow();
        unit.setCoefficient(new BigDecimal("0.3"));
        propertyRepository.save(unit);
        String d = doc();
        UUID proxy = usedExternalProxy(a, asm, a.owner(), a.home(), d);
        UUID part = participationId(assisted(a.admin(), a, p, proxy, d, p.opt(0)));
        String before = jdbc.queryForObject("select string_agg(id::text || poll_option_id::text || coefficient::text || voted_at::text, ',') "
                + "from poll_votes where participation_id = ?", String.class, part);
        JsonNode counted = json(send(a.admin(), a, get("/api/v1/assemblies/polls/{p}", p.id())));
        assertThat(counted.at("/data/options/0/votesCount").asLong()).isEqualTo(1);

        assertCode(voidVote(a.admin(), a, p, part, ""), 400, null);
        assertCode(voidVote(a.admin(), a, p, part, null), 400, null);
        assertThat(voidVote(a.owner(), a, p, part, "intento").getStatus()).isEqualTo(403);
        MockHttpServletResponse r = voidVote(a.admin(), a, p, part, "El apoderado pidió corregir su voto en voz alta");
        assertCode(r, 200, null);
        assertThat(json(r).at("/data/status").asText()).isEqualTo("VOIDED");
        assertThat(json(r).at("/data/poll/options/0/votesCount").asLong()).isZero();
        assertThat(json(r).at("/data/poll/totalVotes").asLong()).isZero();
        var row = participation(part);
        assertThat(row.get("status")).isEqualTo("VOIDED");
        assertThat(row.get("voided_by_user_id")).isEqualTo(a.admin().getId());
        assertThat(row.get("void_reason")).isEqualTo("El apoderado pidió corregir su voto en voz alta");
        assertThat(jdbc.queryForObject("select string_agg(id::text || poll_option_id::text || coefficient::text || voted_at::text, ',') "
                + "from poll_votes where participation_id = ?", String.class, part)).as("voto original conservado").isEqualTo(before);
        var event = jdbc.queryForMap("select * from assembly_vote_events where participation_id = ? and action = 'ASSISTED_VOTE_VOIDED'", part);
        assertThat(event.get("actor_user_id")).isEqualTo(a.admin().getId());
        assertThat(event.get("reason")).isEqualTo("El apoderado pidió corregir su voto en voz alta");
        // Ya anulado.
        assertCode(voidVote(a.admin(), a, p, part, "otra vez"), 409, "PARTICIPATION_ALREADY_VOIDED");
        assertThat(events(p, "VOID_REJECTED", "PARTICIPATION_ALREADY_VOIDED")).isEqualTo(1);
        // P5-A (decision aprobada): tras anular, nueva emision asistida que continua la cadena; la anulada se conserva.
        MockHttpServletResponse again = assisted(a.admin(), a, p, proxy, d, p.opt(1));
        assertCode(again, 201, null);
        assertThat(json(again).at("/data/supersedesParticipationId").asText()).isEqualTo(part.toString());
        assertThat(participations(p, a.home())).isEqualTo(2);
        assertThat(participation(part).get("status")).isEqualTo("VOIDED");
        assertThat(json(again).at("/data/poll/options/1/votesCount").asLong()).isEqualTo(1);
        assertThat(json(again).at("/data/poll/options/0/votesCount").asLong()).isZero();
    }

    @Test
    @DisplayName("anulacion rechazada: voto directo, de apoderado con cuenta, otra comunidad, pregunta cerrada, asamblea finalizada, administrador desactivado")
    void voidRejections() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0)), 200, null);
        UUID direct = jdbc.queryForObject("select id from poll_participations where poll_id = ? and property_id = ?", UUID.class, p.id(), a.home().getId());
        assertCode(voidVote(a.admin(), a, p, direct, "motivo"), 409, "VOID_ONLY_FOR_ASSISTED_VOTES");
        Owned u = ownedUnit(a, "0.1");
        UUID accountProxy = usedAccountProxy(a, asm, u.owner(), u.unit(), a.neighbour());
        UUID byProxy = participationId(proxyVote(a.neighbour(), a, p, accountProxy, p.opt(1)));
        assertCode(voidVote(a.admin(), a, p, byProxy, "motivo"), 409, "VOID_ONLY_FOR_ASSISTED_VOTES");
        assertThat(events(p, "VOID_REJECTED", "VOID_ONLY_FOR_ASSISTED_VOTES")).isEqualTo(2);
        // Asistido de esta comunidad.
        Owned u2 = ownedUnit(a, "0.1");
        String d = doc();
        UUID ext = usedExternalProxy(a, asm, u2.owner(), u2.unit(), d);
        UUID assistedPart = participationId(assisted(a.admin(), a, p, ext, d, p.opt(0)));
        // Otra comunidad: no existe para ella.
        assertThat(voidVote(b.admin(), b, p, assistedPart, "motivo").getStatus()).isEqualTo(404);
        assertThat(voidVote(b.admin(), a, p, assistedPart, "motivo").getStatus()).isIn(401, 403);
        // Administrador desactivado.
        User admin2 = member(a, RoleType.COMMUNITY_ADMIN);
        String token = fixture.bearer(admin2);
        setMembership(admin2, a, MembershipStatus.INACTIVE);
        assertThat(voidVote(token, a, p, assistedPart, "motivo").getStatus()).isIn(401, 403);
        // Pregunta cerrada.
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/polls/{p}/close", p.id())), 200, null);
        assertCode(voidVote(a.admin(), a, p, assistedPart, "motivo"), 409, "POLL_NOT_LIVE");
        assertThat(events(p, "VOID_REJECTED", "POLL_NOT_LIVE")).isEqualTo(1);
        // Asamblea finalizada (pregunta abierta de otra asamblea).
        Assembly asm2 = started(a);
        PollRef p2 = poll(a, asm2);
        String d2 = doc();
        UUID ext2 = usedExternalProxy(a, asm2, a.neighbour(), a.neighbourHome(), d2);
        UUID part2 = participationId(assisted(a.admin(), a, p2, ext2, d2, p2.opt(0)));
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{id}/finish", asm2.getId())), 200, null);
        assertCode(voidVote(a.admin(), a, p2, part2, "motivo"), 409, "ASSEMBLY_ENDED");
        assertThat(jdbc.queryForObject("select count(*) from poll_participations where status = 'VOIDED' and poll_id in (?, ?)",
                Long.class, p.id(), p2.id())).isZero();
    }

    @Test
    @DisplayName("rollback: si falla la auditoria del voto, no queda participacion, ni voto, ni evento")
    void voteRollback() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        AssemblyVoteEvents target = AopTestUtils.getUltimateTargetObject(voteEvents);
        doThrow(new IllegalStateException("fallo simulado de auditoria")).when(target)
                .success(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
        try {
            assertThat(vote(a.owner(), a, p, a.home(), p.opt(0)).getStatus()).isEqualTo(500);
        } finally {
            reset((Object) target);
        }
        assertThat(participations(p, a.home())).isZero();
        assertThat(voteRows(p, a.home())).isZero();
        assertThat(events(p, "VOTE_CAST", null)).isZero();
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0)), 200, null);
    }

    // ── V-4: asamblea finalizada ──────────────────────────────────────────────

    @Test
    @DisplayName("V-4 · iniciar una asamblea finalizada o en curso: 409 sin cambios y auditado; otra comunidad 404; JWT obsoleto denegado")
    void startTransitions() throws Exception {
        Assembly asm = started(a);
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{id}/start", asm.getId())), 409, "ASSEMBLY_ALREADY_IN_PROGRESS");
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{id}/finish", asm.getId())), 200, null);
        String before = jdbc.queryForObject("select status || updated_at::text from assemblies where id = ?", String.class, asm.getId());
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{id}/start", asm.getId())), 409, "ASSEMBLY_ALREADY_ENDED");
        assertThat(jdbc.queryForObject("select status || updated_at::text from assemblies where id = ?", String.class, asm.getId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from assembly_vote_events where assembly_id = ? and action = "
                + "'ASSEMBLY_TRANSITION_REJECTED' and reason_code = 'ASSEMBLY_ALREADY_ENDED' and actor_user_id = ?", Long.class,
                asm.getId(), a.admin().getId())).isEqualTo(1);
        assertThat(send(b.admin(), b, post("/api/v1/assemblies/{id}/start", asm.getId())).getStatus()).isEqualTo(404);
        Assembly scheduled = assembly(a);
        User admin2 = member(a, RoleType.COMMUNITY_ADMIN);
        String stale = fixture.bearer(admin2);
        Membership m = membershipRepository.findByUserIdAndTenantId(admin2.getId(), a.tenantId()).orElseThrow();
        m.setRole(RoleType.STAFF);
        membershipRepository.save(m);
        assertThat(send(stale, a, post("/api/v1/assemblies/{id}/start", scheduled.getId())).getStatus()).isIn(401, 403);
        assertThat(jdbc.queryForObject("select status from assemblies where id = ?", String.class, scheduled.getId())).isEqualTo("SCHEDULED");
        // Defensa en PostgreSQL: ni por SQL directo se reabre.
        Throwable e = catchThrowable(() -> jdbc.update("update assemblies set status = 'IN_PROGRESS' where id = ?", asm.getId()));
        assertThat(e).hasMessageContaining("no se reabre");
        assertThat(jdbc.queryForObject("select status from assemblies where id = ?", String.class, asm.getId())).isEqualTo("ENDED");
    }

    // ── restricciones de PostgreSQL (defensa en profundidad) ──────────────────

    @Test
    @DisplayName("PostgreSQL: votos y participaciones no se modifican ni borran; representante falso y evento editado rechazados")
    void databaseGuards() throws Exception {
        Assembly asm = started(a);
        PollRef p = poll(a, asm);
        assertCode(selfRegister(a.owner(), a, asm, a.home()), 201, null);
        assertCode(vote(a.owner(), a, p, a.home(), p.opt(0)), 200, null);
        UUID part = jdbc.queryForObject("select id from poll_participations where poll_id = ?", UUID.class, p.id());
        assertThat(catchThrowable(() -> jdbc.update("update poll_votes set poll_option_id = ? where participation_id = ?", p.opt(1), part)))
                .hasMessageContaining("no se modifican");
        assertThat(catchThrowable(() -> jdbc.update("delete from poll_votes where participation_id = ?", part))).hasMessageContaining("no se modifican");
        assertThat(catchThrowable(() -> jdbc.update("delete from poll_participations where id = ?", part))).hasMessageContaining("no se eliminan");
        assertThat(catchThrowable(() -> jdbc.update("update poll_participations set actor_user_id = ? where id = ?", a.admin().getId(), part)))
                .hasMessageContaining("inmutables");
        assertThat(catchThrowable(() -> jdbc.update("update poll_participations set status = 'VOIDED', voided_by_user_id = ?, voided_at = now(), "
                + "void_reason = 'x' where id = ?", a.admin().getId(), part))).hasMessageContaining("poll_participations_void_check");
        assertThat(catchThrowable(() -> jdbc.update("update assembly_vote_events set reason = 'x' where participation_id = ?", part)))
                .hasMessageContaining("solo insercion");
        assertThat(catchThrowable(() -> jdbc.update("delete from assembly_vote_events where participation_id = ?", part)))
                .hasMessageContaining("solo insercion");
        // Participacion con un actor que no es el representante registrado (el vecino) para otra unidad registrada.
        Owned u = ownedUnit(a, "0.1");
        assertCode(selfRegister(u.owner(), a, asm, u.unit()), 201, null);
        UUID attendee = jdbc.queryForObject("select id from assembly_attendees where assembly_id = ? and property_id = ?", UUID.class, asm.getId(), u.unit().getId());
        UUID rel = jdbc.queryForObject("select resident_id from assembly_attendees where id = ?", UUID.class, attendee);
        assertThat(catchThrowable(() -> jdbc.update("insert into poll_participations (id, tenant_id, assembly_id, poll_id, property_id, "
                + "attendee_id, representation_type, actor_user_id, resident_id, status, cast_at) values (gen_random_uuid(), ?, ?, ?, ?, ?, "
                + "'OWNER', ?, ?, 'VALID', now())", a.tenantId(), asm.getId(), p.id(), u.unit().getId(), attendee, a.neighbour().getId(), rel)))
                .hasMessageContaining("representante registrado");
        // Participacion de otra comunidad con la asistencia de esta: la FK compuesta lo impide.
        assertThat(catchThrowable(() -> jdbc.update("insert into poll_participations (id, tenant_id, assembly_id, poll_id, property_id, "
                + "attendee_id, representation_type, actor_user_id, resident_id, status, cast_at) values (gen_random_uuid(), ?, ?, ?, ?, ?, "
                + "'OWNER', ?, ?, 'VALID', now())", b.tenantId(), asm.getId(), p.id(), u.unit().getId(), attendee, u.owner().getId(), rel)))
                .isNotNull();
        assertThat(participations(p, u.unit())).isZero();
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

    private static int ok(MockHttpServletResponse r) {
        return r.getStatus() < 300 ? 1 : 0;
    }

    /** Comunidad nueva con asamblea en curso y pregunta abierta, para una ronda. */
    private record World(Side s, Assembly asm, PollRef p) {}

    private World world() throws Exception {
        Side s = fixture.create().a();
        Assembly asm = started(s);
        return new World(s, asm, poll(s, asm));
    }

    @Test
    @DisplayName("carrera 1 · dos votos simultaneos de la unidad con opciones distintas: uno gana (8 rondas)")
    void raceSameUnitDifferentOptions() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            assertCode(selfRegister(w.s().owner(), w.s(), w.asm(), w.s().home()), 201, null);
            var r = concurrently(List.of(() -> vote(w.s().owner(), w.s(), w.p(), w.s().home(), w.p().opt(0)),
                    () -> vote(w.s().owner(), w.s(), w.p(), w.s().home(), w.p().opt(1))));
            assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
            assertThat(code(r.get(ok(r.get(0)) == 1 ? 1 : 0))).isEqualTo("PROPERTY_ALREADY_VOTED");
            assertThat(participations(w.p(), w.s().home())).isEqualTo(1);
            assertThat(voteRows(w.p(), w.s().home())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("carrera 2 · voto directo del propietario frente a voto del apoderado de la unidad: solo vota el poder (8 rondas)")
    void raceDirectVersusProxy() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            UUID proxy = usedAccountProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), w.s().neighbour());
            var r = concurrently(List.of(() -> vote(w.s().owner(), w.s(), w.p(), w.s().home(), w.p().opt(0)),
                    () -> proxyVote(w.s().neighbour(), w.s(), w.p(), proxy, w.p().opt(1))));
            assertThat(r.get(0).getStatus()).as("ronda " + round).isIn(403, 409);
            assertThat(r.get(1).getStatus()).isEqualTo(201);
            assertThat(jdbc.queryForObject("select representation_type from poll_participations where poll_id = ?", String.class, w.p().id()))
                    .isEqualTo("ACCOUNT_PROXY");
        }
    }

    @Test
    @DisplayName("carreras 3 y 4 · dos votos asistidos del mismo apoderado (mismo administrador y dos administradores): uno gana (8 rondas)")
    void raceAssistedVersusAssisted() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            String d = doc();
            UUID proxy = usedExternalProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), d);
            User admin2 = member(w.s(), RoleType.COMMUNITY_ADMIN);
            User second = round % 2 == 0 ? w.s().admin() : admin2;
            var r = concurrently(List.of(() -> assisted(w.s().admin(), w.s(), w.p(), proxy, d, w.p().opt(0)),
                    () -> assisted(second, w.s(), w.p(), proxy, d, w.p().opt(1))));
            assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
            assertThat(code(r.get(ok(r.get(0)) == 1 ? 1 : 0))).isEqualTo("PROPERTY_ALREADY_VOTED");
            assertThat(participations(w.p(), w.s().home())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("carreras 5 y 6 · voto frente a cierre de la pregunta y frente a finalizar la asamblea: o entra antes o se rechaza (8 rondas)")
    void raceVoteVersusCloseAndFinish() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            String d = doc();
            UUID proxy = usedExternalProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), d);
            boolean close = round % 2 == 0;
            var r = concurrently(List.of(() -> assisted(w.s().admin(), w.s(), w.p(), proxy, d, w.p().opt(0)),
                    () -> close ? send(w.s().admin(), w.s(), post("/api/v1/assemblies/polls/{p}/close", w.p().id()))
                            : send(w.s().admin(), w.s(), post("/api/v1/assemblies/{id}/finish", w.asm().getId()))));
            assertThat(r.get(1).getStatus()).as("ronda " + round).isEqualTo(200);
            if (ok(r.get(0)) == 1) {
                assertThat(participations(w.p(), w.s().home())).isEqualTo(1);
            } else {
                assertThat(code(r.get(0))).isIn("POLL_NOT_LIVE", "ASSEMBLY_ENDED", "ASSEMBLY_NOT_IN_PROGRESS");
                assertThat(participations(w.p(), w.s().home())).isZero();
            }
        }
    }

    @Test
    @DisplayName("carreras 7, 8 y 9 · anulacion frente a cierre, dos anulaciones, anulacion frente a nuevo voto y frente a lectura de resultados (8 rondas)")
    void raceVoid() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            String d = doc();
            UUID proxy = usedExternalProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), d);
            UUID part = participationId(assisted(w.s().admin(), w.s(), w.p(), proxy, d, w.p().opt(0)));
            int kind = round % 4;
            List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
            calls.add(() -> voidVote(w.s().admin(), w.s(), w.p(), part, "motivo ronda"));
            calls.add(switch (kind) {
                case 0 -> () -> send(w.s().admin(), w.s(), post("/api/v1/assemblies/polls/{p}/close", w.p().id()));
                case 1 -> () -> voidVote(w.s().admin(), w.s(), w.p(), part, "motivo concurrente");
                case 2 -> () -> assisted(w.s().admin(), w.s(), w.p(), proxy, d, w.p().opt(1));
                default -> () -> send(w.s().admin(), w.s(), get("/api/v1/assemblies/polls/{p}", w.p().id()));
            });
            var r = concurrently(calls);
            String status = jdbc.queryForObject("select status from poll_participations where id = ?", String.class, part);
            switch (kind) {
                case 0 -> {
                    assertThat(r.get(1).getStatus()).as("ronda " + round).isEqualTo(200);
                    assertThat(status).isEqualTo(ok(r.get(0)) == 1 ? "VOIDED" : "VALID");
                    if (ok(r.get(0)) == 0) assertThat(code(r.get(0))).isEqualTo("POLL_NOT_LIVE");
                }
                case 1 -> {
                    assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
                    assertThat(code(r.get(ok(r.get(0)) == 1 ? 1 : 0))).isEqualTo("PARTICIPATION_ALREADY_VOIDED");
                    assertThat(status).isEqualTo("VOIDED");
                }
                case 2 -> {
                    // P5-A: la nueva emision entra si llega despues de la anulacion; si llega antes, la unidad ya voto.
                    assertThat(r.get(0).getStatus()).as("ronda " + round).isEqualTo(200);
                    if (ok(r.get(1)) == 0) assertThat(code(r.get(1))).isEqualTo("PROPERTY_ALREADY_VOTED");
                    assertThat(participations(w.p(), w.s().home())).isEqualTo(1 + ok(r.get(1)));
                }
                default -> {
                    assertThat(r.get(0).getStatus()).as("ronda " + round).isEqualTo(200);
                    long seen = json(r.get(1)).at("/data/totalVotes").asLong();
                    assertThat(seen).as("el resultado es el de antes o el de despues, nunca parcial").isIn(0L, 1L);
                }
            }
            assertThat(jdbc.queryForObject("select count(*) from poll_votes where participation_id = ?", Long.class, part)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("carrera 10 · administrador representante frente a otro copropietario: solo vota el representante registrado (8 rondas)")
    void raceAdminRepresentativeVersusOther() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            relation(w.s(), w.s().home(), w.s().admin(), "Propietario");
            assertCode(selfRegister(w.s().admin(), w.s(), w.asm(), w.s().home()), 201, null);
            var r = concurrently(List.of(() -> vote(w.s().admin(), w.s(), w.p(), w.s().home(), w.p().opt(0)),
                    () -> vote(w.s().owner(), w.s(), w.p(), w.s().home(), w.p().opt(1))));
            assertThat(r.get(0).getStatus()).as("ronda " + round).isEqualTo(200);
            assertThat(code(r.get(1))).isEqualTo("UNAUTHORIZED_VOTE");
            assertThat(jdbc.queryForObject("select actor_user_id from poll_participations where poll_id = ?", UUID.class, w.p().id()))
                    .isEqualTo(w.s().admin().getId());
        }
    }

    @Test
    @DisplayName("carreras 11 y 12 · voto por poder frente a baja de la membresia del apoderado y frente a revocar el poder (8 rondas)")
    void raceProxyVersusMembershipAndRevocation() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            UUID proxy = usedAccountProxy(w.s(), w.asm(), w.s().owner(), w.s().home(), w.s().neighbour());
            String token = fixture.bearer(w.s().neighbour());
            if (round % 2 == 0) {
                var r = concurrently(List.of(() -> proxyVote(token, w.s(), w.p(), proxy, w.p().opt(0)),
                        () -> {
                            jdbc.update("update memberships set status = 'INACTIVE' where user_id = ? and tenant_id = ?",
                                    w.s().neighbour().getId(), w.s().tenantId());
                            return new MockHttpServletResponse();
                        }));
                assertThat(r.get(0).getStatus()).as("ronda " + round).isIn(201, 401, 403, 409);
                assertThat(participations(w.p(), w.s().home())).isEqualTo(ok(r.get(0)));
            } else {
                // Un poder USED no se revoca (R4: solo antes de la asistencia): la revocacion se rechaza, el voto entra.
                var r = concurrently(List.of(() -> proxyVote(token, w.s(), w.p(), proxy, w.p().opt(0)),
                        () -> send(w.s().owner(), w.s(), post("/api/v1/assemblies/{a}/proxies/{p}/revoke", w.asm().getId(), proxy))));
                assertThat(r.get(0).getStatus()).as("ronda " + round).isEqualTo(201);
                assertThat(r.get(1).getStatus()).isEqualTo(409);
                assertThat(jdbc.queryForObject("select status from assembly_proxies where id = ?", String.class, proxy)).isEqualTo("USED");
            }
        }
    }

    @Test
    @DisplayName("V-4 · carreras: dos inicios simultaneos (uno gana) e inicio frente a finalizacion (nunca se reabre) (8 rondas)")
    void raceStart() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Side s = fixture.create().a();
            Assembly asm = assembly(s);
            if (round % 2 == 0) {
                var r = concurrently(List.of(() -> send(s.admin(), s, post("/api/v1/assemblies/{id}/start", asm.getId())),
                        () -> send(s.admin(), s, post("/api/v1/assemblies/{id}/start", asm.getId()))));
                assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
                assertThat(code(r.get(ok(r.get(0)) == 1 ? 1 : 0))).isEqualTo("ASSEMBLY_ALREADY_IN_PROGRESS");
                assertThat(jdbc.queryForObject("select status from assemblies where id = ?", String.class, asm.getId())).isEqualTo("IN_PROGRESS");
            } else {
                var r = concurrently(List.of(() -> send(s.admin(), s, post("/api/v1/assemblies/{id}/start", asm.getId())),
                        () -> send(s.admin(), s, post("/api/v1/assemblies/{id}/finish", asm.getId()))));
                assertThat(r.get(1).getStatus()).as("ronda " + round).isEqualTo(200);
                assertThat(jdbc.queryForObject("select status from assemblies where id = ?", String.class, asm.getId())).isEqualTo("ENDED");
                if (ok(r.get(0)) == 0) assertThat(code(r.get(0))).isEqualTo("ASSEMBLY_ALREADY_ENDED");
            }
        }
    }
}
