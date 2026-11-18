package com.havita.modules.assembly.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.domain.AssemblyStatus;
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
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fase 2 / P1 — poderes de asamblea por HTTP, sobre el ESQUEMA DE FLYWAY con V102 (contenedor propio) y con el modelo
 * habilitado solo en este contexto ({@code havita.assembly-proxies.enabled=true}). Almacenamiento local en un directorio
 * temporal propio. Limite de unidades por apoderado = 1 en este contexto (en la aplicacion no hay limite por defecto).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AssemblyProxyIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("assembly_proxy_it");
    static final Path STORAGE;

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            PG.start();
        }
        try {
            STORAGE = Files.createTempDirectory("havita-p1-proxies-");
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
        r.add("havita.assembly-proxies.max-document-bytes", () -> "65536");
        r.add("havita.assembly-proxies.max-units-per-proxy", () -> "1");
        r.add("storage.provider", () -> "local");
        r.add("storage.local.base-path", STORAGE::toString);
    }

    private static final int ROUNDS = 5;
    static final byte[] PDF = "%PDF-1.7\n% poder sintetico\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private VotingDelegationRepository delegationRepository;
    @Autowired private AssemblyAttendeeRepository attendeeRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private Side a;
    private Side b;
    private Assembly asmA;
    private Assembly asmB;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        asmA = assembly(a);
        asmB = assembly(b);
    }

    // ── datos ─────────────────────────────────────────────────────────────────

    private Assembly assembly(Side s) {
        Assembly x = Assembly.builder().title("Asamblea sintetica").assemblyDate(LocalDate.now().plusDays(7)).startTime(LocalTime.NOON).build();
        x.setTenantId(s.tenantId());
        return assemblyRepository.save(x);
    }

    private User member(Side s, RoleType role) {
        User u = userRepository.save(User.builder().email("p1-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}x").fullName("Persona P1").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(s.tenantId()).role(role).status(MembershipStatus.ACTIVE).build());
        return u;
    }

    private Resident relation(Side s, Property unit, User u, String type) {
        Resident r = Resident.builder().property(unit).user(u).name(u.getFullName()).type(type).email(u.getEmail())
                .status(ResidentStatus.ACTIVE).build();
        r.setTenantId(s.tenantId());
        return residentRepository.save(r);
    }

    private static String doc() {
        return "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String data(UUID propertyId, UUID grantorResidentId, UUID proxyUserId, String docNumber) {
        StringBuilder sb = new StringBuilder("{\"propertyId\":\"" + propertyId + "\"");
        if (grantorResidentId != null) sb.append(",\"grantorResidentId\":\"").append(grantorResidentId).append('"');
        if (proxyUserId != null) sb.append(",\"proxyUserId\":\"").append(proxyUserId).append('"');
        return sb.append(",\"proxyFullName\":\"Apoderada Sintetica\",\"proxyDocumentType\":\"CC\",\"proxyDocumentNumber\":\"")
                .append(docNumber).append("\"}").toString();
    }

    private MockHttpServletResponse send(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()))
                .andReturn().getResponse();
    }

    private MockMultipartHttpServletRequestBuilder withParts(MockMultipartHttpServletRequestBuilder req, String json, byte[] document,
                                                             String contentType) {
        req.file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)));
        if (document != null) req.file(new MockMultipartFile("document", "poder.pdf", contentType, document));
        return req;
    }

    private MockHttpServletResponse ownerRequest(User user, Side side, Assembly asm, String json, byte[] document) throws Exception {
        return send(user, side, withParts(multipart("/api/v1/assemblies/{a}/proxies", asm.getId()), json, document, "application/pdf"));
    }

    private MockHttpServletResponse adminRegister(User admin, Side side, Assembly asm, String json, byte[] document) throws Exception {
        // P7 (G3): la administracion indica la evidencia del poder firmado y lo declara.
        json = json.replaceFirst("\\{", "{\"evidenceType\":\"SIGNED_DOCUMENT_IN_PERSON\",\"grantorConsentAttested\":true,");
        return send(admin, side, withParts(multipart("/api/v1/assemblies/{a}/proxies/admin", asm.getId()), json, document, "application/pdf"));
    }

    private MockHttpServletResponse action(User user, Side side, Assembly asm, UUID proxyId, String verb, String body) throws Exception {
        var req = post("/api/v1/assemblies/{a}/proxies/{p}/" + verb, asm.getId(), proxyId);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return send(user, side, req);
    }

    private JsonNode json(MockHttpServletResponse r) throws Exception {
        return objectMapper.readTree(r.getContentAsString());
    }

    private UUID created(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return UUID.fromString(json(r).at("/data/id").asText());
    }

    /** Solicitud del propietario de la unidad principal de A (apoderado externo), ya creada. */
    private UUID pendingByOwner(String docNumber) throws Exception {
        return created(ownerRequest(a.owner(), a, asmA, data(a.home().getId(), null, null, docNumber), PDF));
    }

    private UUID approved(String docNumber) throws Exception {
        UUID id = pendingByOwner(docNumber);
        assertThat(action(a.admin(), a, asmA, id, "approve", null).getStatus()).isEqualTo(200);
        return id;
    }

    private String status(UUID proxyId) {
        return jdbc.queryForObject("select status from assembly_proxies where id = ?", String.class, proxyId);
    }

    private long events(UUID proxyId, String action, String result) {
        Long n = jdbc.queryForObject("select count(*) from assembly_proxy_events where proxy_id = ? and action = ? and result = ?",
                Long.class, proxyId, action, result);
        return n == null ? 0 : n;
    }

    private long liveProxies(Assembly asm, Property unit) {
        Long n = jdbc.queryForObject("select count(*) from assembly_proxies where assembly_id = ? and property_id = ? "
                + "and status in ('PENDING_REVIEW','APPROVED','USED')", Long.class, asm.getId(), unit.getId());
        return n == null ? 0 : n;
    }

    private long storedFiles(Side s) throws IOException {
        Path dir = STORAGE.resolve("tenants").resolve(s.tenantId().toString()).resolve("assembly-proxies");
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private void assertCode(MockHttpServletResponse r, int status, String code) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        if (code != null) assertThat(json(r).path("code").asText()).isEqualTo(code);
    }

    /**
     * Nada de la comunidad B (comunidad, nombres, unidad, otras filas) aparece en la respuesta. Un id que el propio
     * llamante envio puede repetirse en el mensaje (no revela nada nuevo).
     */
    private void assertNoLeakOfB(MockHttpServletResponse r, String... sentByCaller) throws Exception {
        String raw = r.getContentAsString();
        List<String> sent = List.of(sentByCaller);
        for (String f : List.of(b.tenantId().toString(), asmB.getId().toString(), b.home().getId().toString(),
                b.home().getNumber(), b.home().getName(), b.ownerResident().getId().toString(), b.owner().getEmail())) {
            if (!sent.contains(f)) assertThat(raw).as(f).doesNotContain(f);
        }
    }

    /** La respuesta para un recurso de B es la misma que para uno inexistente (salvo el id enviado). */
    private void assertSameAsMissing(MockHttpServletResponse foreign, MockHttpServletResponse missing, String foreignId, String missingId)
            throws Exception {
        assertThat(foreign.getStatus()).isEqualTo(missing.getStatus());
        assertThat(json(foreign).path("code").asText()).isEqualTo(json(missing).path("code").asText());
        assertThat(json(foreign).path("message").asText().replace(foreignId, "X"))
                .isEqualTo(json(missing).path("message").asText().replace(missingId, "X"));
    }

    // ── solicitud ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("propietario activo solicita: PENDING_REVIEW, documento privado guardado, eventos y ningun permiso para el apoderado")
    void ownerCreatesRequest() throws Exception {
        long membershipsBefore = membershipRepository.count();
        long residentsBefore = residentRepository.count();
        MockHttpServletResponse r = ownerRequest(a.owner(), a, asmA, data(a.home().getId(), null, null, doc()), PDF);
        UUID id = created(r);
        JsonNode d = json(r).path("data");
        assertThat(d.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(d.path("createdChannel").asText()).isEqualTo("OWNER");
        assertThat(d.path("documentContentType").asText()).isEqualTo("application/pdf");
        assertThat(r.getContentAsString()).doesNotContain("tenants/").doesNotContain("assembly-proxies/");
        assertThat(events(id, "PROXY_REQUESTED", "SUCCESS")).isEqualTo(1);
        assertThat(events(id, "PROXY_DOCUMENT_REGISTERED", "SUCCESS")).isEqualTo(1);
        assertThat(storedFiles(a)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select grantor_resident_id from assembly_proxies where id = ?", UUID.class, id))
                .isEqualTo(a.ownerResident().getId());
        // P1-R6/R10: ni cuenta, ni membresia, ni relacion de residente (ni de propietario) para el apoderado externo.
        assertThat(membershipRepository.count()).isEqualTo(membershipsBefore);
        assertThat(residentRepository.count()).isEqualTo(residentsBefore);
        // El controlador generico de archivos no sirve la carpeta privada de poderes.
        String key = jdbc.queryForObject("select document_key from assembly_proxies where id = ?", String.class, id);
        MockHttpServletResponse generic = send(a.admin(), a, get("/api/v1/files/download").param("key", key));
        assertThat(generic.getStatus()).isIn(403, 404);
    }

    @Test
    @DisplayName("copropietario activo solicita; el otro copropietario ya no puede crear un segundo poder vigente (409)")
    void coOwnerCreatesRequest() throws Exception {
        User coOwner = member(a, RoleType.RESIDENT);
        Resident coRel = relation(a, a.home(), coOwner, "Propietario");
        UUID id = created(ownerRequest(coOwner, a, asmA, data(a.home().getId(), null, null, doc()), PDF));
        assertThat(jdbc.queryForObject("select grantor_resident_id from assembly_proxies where id = ?", UUID.class, id)).isEqualTo(coRel.getId());
        assertCode(ownerRequest(a.owner(), a, asmA, data(a.home().getId(), null, null, doc()), PDF), 409, "PROXY_ALREADY_EXISTS");
        assertThat(liveProxies(asmA, a.home())).isEqualTo(1);
        assertThat(storedFiles(a)).as("sin documentos huerfanos").isEqualTo(1);
    }

    @Test
    @DisplayName("arrendatario, residente, propietario inactivo y propietario de OTRA unidad: no otorgan poderes")
    void nonOwnersCannotRequest() throws Exception {
        User tenantUser = member(a, RoleType.RESIDENT);
        relation(a, a.home(), tenantUser, "Arrendatario");
        User household = member(a, RoleType.RESIDENT);
        relation(a, a.home(), household, "Residente");
        User formerOwner = member(a, RoleType.RESIDENT);
        Resident former = relation(a, a.home(), formerOwner, "Propietario");
        former.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(former);
        relation(a, a.vacant(), formerOwner, "Propietario"); // conserva acceso a la comunidad por otra unidad
        for (User u : List.of(tenantUser, household, formerOwner, a.neighbour())) {
            assertCode(ownerRequest(u, a, asmA, data(a.home().getId(), null, null, doc()), PDF), 409, "NOT_THE_OWNER");
        }
        // El portero (SECURITY) ni siquiera pasa el filtro de rol.
        assertCode(ownerRequest(a.guard(), a, asmA, data(a.home().getId(), null, null, doc()), PDF), 403, null);
        // Sin autenticacion: 401.
        assertThat(mvc.perform(withParts(multipart("/api/v1/assemblies/{a}/proxies", asmA.getId()),
                data(a.home().getId(), null, null, doc()), PDF, "application/pdf").header("X-Tenant-ID", a.tenantId().toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        // El otorgante no se elige en la solicitud del propietario.
        assertCode(ownerRequest(a.owner(), a, asmA, data(a.home().getId(), a.censusOnly().getId(), null, doc()), PDF), 400, "GRANTOR_NOT_SELECTABLE");
        assertThat(liveProxies(asmA, a.home())).isZero();
        assertThat(storedFiles(a)).isZero();
    }

    @Test
    @DisplayName("unidad o asamblea de OTRA comunidad: 404 sin revelar nada de ella")
    void foreignUnitOrAssembly() throws Exception {
        MockHttpServletResponse unit = ownerRequest(a.owner(), a, asmA, data(b.home().getId(), null, null, doc()), PDF);
        assertCode(unit, 404, null);
        assertNoLeakOfB(unit, b.home().getId().toString());
        UUID nowhere = UUID.randomUUID();
        assertSameAsMissing(unit, ownerRequest(a.owner(), a, asmA, data(nowhere, null, null, doc()), PDF),
                b.home().getId().toString(), nowhere.toString());
        MockHttpServletResponse asm = ownerRequest(a.owner(), a, asmB, data(a.home().getId(), null, null, doc()), PDF);
        assertCode(asm, 404, null);
        assertNoLeakOfB(asm, asmB.getId().toString());
        Assembly missingAsm = Assembly.builder().title("x").assemblyDate(LocalDate.now()).startTime(LocalTime.NOON).build();
        missingAsm.setId(UUID.randomUUID());
        assertSameAsMissing(asm, ownerRequest(a.owner(), a, missingAsm, data(a.home().getId(), null, null, doc()), PDF),
                asmB.getId().toString(), missingAsm.getId().toString());
        // La administracion de A tampoco puede usar un otorgante de B.
        MockHttpServletResponse grantor = adminRegister(a.admin(), a, asmA, data(a.home().getId(), b.ownerResident().getId(), null, doc()), PDF);
        assertCode(grantor, 409, "GRANTOR_NOT_ACTIVE_OWNER");
        assertNoLeakOfB(grantor);
        assertThat(jdbc.queryForObject("select count(*) from assembly_proxies where tenant_id in (?, ?)", Long.class, a.tenantId(), b.tenantId())).isZero();
    }

    @Test
    @DisplayName("sin documento, vacio, de tipo falso (por contenido) o demasiado grande: 400 sin poder ni archivo")
    void documentRequiredAndValidated() throws Exception {
        String json = data(a.home().getId(), null, null, doc());
        assertCode(ownerRequest(a.owner(), a, asmA, json, null), 400, null);
        assertCode(ownerRequest(a.owner(), a, asmA, json, new byte[0]), 400, "PROXY_DOCUMENT_REQUIRED");
        assertCode(ownerRequest(a.owner(), a, asmA, json, "texto plano con extension .pdf".getBytes(StandardCharsets.UTF_8)), 400, "INVALID_PROXY_DOCUMENT");
        assertCode(ownerRequest(a.owner(), a, asmA, json, "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)), 400, "INVALID_PROXY_DOCUMENT");
        byte[] big = new byte[70_000];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertCode(ownerRequest(a.owner(), a, asmA, json, big), 400, "PROXY_DOCUMENT_TOO_LARGE");
        // Datos del apoderado: tipo de documento no admitido.
        assertCode(ownerRequest(a.owner(), a, asmA, json.replace("\"CC\"", "\"LICENCIA\""), PDF), 400, "VALIDATION_FAILED");
        assertThat(liveProxies(asmA, a.home())).isZero();
        assertThat(storedFiles(a)).isZero();
        // PNG valido por contenido aunque el cliente diga application/pdf: se guarda el tipo detectado.
        MockHttpServletResponse png = ownerRequest(a.owner(), a, asmA, json, PNG);
        created(png);
        assertThat(json(png).at("/data/documentContentType").asText()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("apoderado con cuenta de la comunidad; cuenta de otra comunidad (404); el propio otorgante (400); externo")
    void proxyWithAccountOrExternal() throws Exception {
        MockHttpServletResponse linked = ownerRequest(a.owner(), a, asmA, data(a.home().getId(), null, a.neighbour().getId(), doc()), PDF);
        created(linked);
        assertThat(json(linked).at("/data/proxyUserId").asText()).isEqualTo(a.neighbour().getId().toString());

        User coOwner = member(a, RoleType.RESIDENT);
        relation(a, a.neighbourHome(), coOwner, "Propietario");
        MockHttpServletResponse foreign = ownerRequest(coOwner, a, asmA, data(a.neighbourHome().getId(), null, b.owner().getId(), doc()), PDF);
        assertCode(foreign, 404, null);
        assertNoLeakOfB(foreign, b.owner().getId().toString());
        assertCode(ownerRequest(coOwner, a, asmA, data(a.neighbourHome().getId(), null, coOwner.getId(), doc()), PDF), 400, "PROXY_CANNOT_BE_GRANTOR");
        MockHttpServletResponse external = ownerRequest(coOwner, a, asmA, data(a.neighbourHome().getId(), null, null, doc()), PDF);
        created(external);
        assertThat(json(external).at("/data/proxyUserId").isNull()).isTrue();
    }

    // ── revision ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("la misma administracion registra y aprueba; aprobacion duplicada 409 con intento auditado")
    void adminRegistersAndApproves() throws Exception {
        UUID id = created(adminRegister(a.admin(), a, asmA, data(a.home().getId(), a.ownerResident().getId(), null, doc()), PDF));
        assertThat(jdbc.queryForObject("select created_channel from assembly_proxies where id = ?", String.class, id)).isEqualTo("ADMIN");
        assertCode(adminRegister(a.admin(), a, asmA, data(a.home().getId(), null, null, doc()), PDF), 400, "GRANTOR_REQUIRED");
        MockHttpServletResponse ok = action(a.admin(), a, asmA, id, "approve", null);
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select reviewed_by_user_id from assembly_proxies where id = ?", UUID.class, id)).isEqualTo(a.admin().getId());
        assertCode(action(a.admin(), a, asmA, id, "approve", null), 409, "INVALID_PROXY_TRANSITION");
        assertThat(events(id, "PROXY_APPROVED", "SUCCESS")).isEqualTo(1);
        assertThat(events(id, "PROXY_TRANSITION_DENIED", "DENIED")).as("el intento rechazado queda auditado").isEqualTo(1);
        // Un propietario no aprueba.
        assertCode(action(a.owner(), a, asmA, pendingOther(), "approve", null), 403, null);
    }

    private UUID pendingOther() throws Exception {
        return created(ownerRequest(a.neighbour(), a, asmA, data(a.neighbourHome().getId(), null, null, doc()), PDF));
    }

    @Test
    @DisplayName("rechazo: motivo obligatorio; un poder rechazado no se aprueba ni se revoca, y libera la unidad")
    void rejection() throws Exception {
        UUID id = pendingByOwner(doc());
        assertCode(action(a.admin(), a, asmA, id, "reject", "{\"reason\":\"  \"}"), 400, "REJECTION_REASON_REQUIRED");
        assertThat(action(a.admin(), a, asmA, id, "reject", "{\"reason\":\"Firma ilegible\"}").getStatus()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("REJECTED");
        assertCode(action(a.admin(), a, asmA, id, "approve", null), 409, "INVALID_PROXY_TRANSITION");
        assertCode(action(a.owner(), a, asmA, id, "revoke", null), 409, "INVALID_PROXY_TRANSITION");
        assertThat(events(id, "PROXY_TRANSITION_DENIED", "DENIED")).isEqualTo(2);
        pendingByOwner(doc()); // nueva solicitud: nuevo proceso
    }

    @Test
    @DisplayName("revocacion valida (otorgante en revision, administracion aprobado) e invalida (otro residente 404, USED 409)")
    void revocation() throws Exception {
        UUID pending = pendingByOwner(doc());
        assertCode(action(a.neighbour(), a, asmA, pending, "revoke", null), 404, null);
        assertThat(action(a.owner(), a, asmA, pending, "revoke", "{\"reason\":\"Asistire en persona\"}").getStatus()).isEqualTo(200);
        assertThat(status(pending)).isEqualTo("REVOKED");
        assertThat(events(pending, "PROXY_REVOKED", "SUCCESS")).isEqualTo(1);

        UUID approved = approved(doc());
        assertThat(action(a.admin(), a, asmA, approved, "revoke", null).getStatus()).isEqualTo(200);
        assertThat(status(approved)).isEqualTo("REVOKED");

        // P2: USED lo fija la asistencia real por poder (antes, en P1, se simulaba con SQL; V103 exige la asistencia).
        String usedDoc = doc();
        UUID used = approved(usedDoc);
        MockHttpServletResponse attended = send(a.admin(), a, post("/api/v1/assemblies/{a}/proxies/{p}/attendance", asmA.getId(), used)
                .contentType(MediaType.APPLICATION_JSON).content("{\"documentType\":\"CC\",\"documentNumber\":\"" + usedDoc + "\"}"));
        assertThat(attended.getStatus()).as(attended.getContentAsString()).isEqualTo(201);
        assertCode(action(a.owner(), a, asmA, used, "revoke", null), 409, "PROXY_ALREADY_USED");
        assertCode(action(a.admin(), a, asmA, used, "revoke", null), 409, "PROXY_ALREADY_USED");
        assertThat(status(used)).isEqualTo("USED");
        assertThat(events(used, "PROXY_TRANSITION_DENIED", "DENIED")).isEqualTo(2);
    }

    @Test
    @DisplayName("documento: reemplazable en revision; despues de aprobar 409 y la aprobacion se mantiene")
    void documentAfterApproval() throws Exception {
        UUID id = pendingByOwner(doc());
        String shaBefore = jdbc.queryForObject("select document_sha256 from assembly_proxies where id = ?", String.class, id);
        MockHttpServletResponse replaced = send(a.owner(), a, multipart("/api/v1/assemblies/{a}/proxies/{p}/document", asmA.getId(), id)
                .file(new MockMultipartFile("document", "x.png", "image/png", PNG)));
        assertThat(replaced.getStatus()).as(replaced.getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select document_sha256 from assembly_proxies where id = ?", String.class, id)).isNotEqualTo(shaBefore);
        assertThat(events(id, "PROXY_DOCUMENT_REPLACED", "SUCCESS")).isEqualTo(1);

        assertThat(action(a.admin(), a, asmA, id, "approve", null).getStatus()).isEqualTo(200);
        String shaApproved = jdbc.queryForObject("select document_sha256 from assembly_proxies where id = ?", String.class, id);
        for (User u : List.of(a.owner(), a.admin())) {
            assertCode(send(u, a, multipart("/api/v1/assemblies/{a}/proxies/{p}/document", asmA.getId(), id)
                    .file(new MockMultipartFile("document", "y.pdf", "application/pdf", PDF))), 409, "PROXY_NOT_EDITABLE");
        }
        assertThat(status(id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select document_sha256 from assembly_proxies where id = ?", String.class, id)).isEqualTo(shaApproved);
    }

    @Test
    @DisplayName("asamblea finalizada: no se crean ni aprueban poderes; vencimiento solo tras finalizar; vencido no se aprueba")
    void endedAssemblyAndExpiration() throws Exception {
        UUID pending = pendingByOwner(doc());
        UUID approvedProxy = created(ownerRequest(a.neighbour(), a, asmA, data(a.neighbourHome().getId(), null, null, doc()), PDF));
        assertThat(action(a.admin(), a, asmA, approvedProxy, "approve", null).getStatus()).isEqualTo(200);
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{a}/proxies/expire", asmA.getId())), 409, "ASSEMBLY_NOT_ENDED");

        assertThat(send(a.admin(), a, post("/api/v1/assemblies/{id}/finish", asmA.getId())).getStatus()).isEqualTo(200);
        assertCode(action(a.admin(), a, asmA, pending, "approve", null), 409, "ASSEMBLY_ENDED");
        User coOwner = member(a, RoleType.RESIDENT);
        relation(a, a.vacant(), coOwner, "Propietario");
        assertCode(ownerRequest(coOwner, a, asmA, data(a.vacant().getId(), null, null, doc()), PDF), 409, "ASSEMBLY_ENDED");

        MockHttpServletResponse exp = send(a.admin(), a, post("/api/v1/assemblies/{a}/proxies/expire", asmA.getId()));
        assertThat(exp.getStatus()).isEqualTo(200);
        assertThat(json(exp).at("/data/expired").asInt()).isEqualTo(2);
        assertThat(status(pending)).isEqualTo("EXPIRED");
        assertThat(status(approvedProxy)).isEqualTo("EXPIRED");
        assertThat(events(approvedProxy, "PROXY_EXPIRED", "SUCCESS")).isEqualTo(1);
        assertCode(action(a.admin(), a, asmA, approvedProxy, "approve", null), 409, "INVALID_PROXY_TRANSITION");
        // Repetir no cambia nada.
        assertThat(json(send(a.admin(), a, post("/api/v1/assemblies/{a}/proxies/expire", asmA.getId()))).at("/data/expired").asInt()).isZero();
        assertThat(assemblyRepository.findById(asmA.getId()).orElseThrow().getStatus()).isEqualTo(AssemblyStatus.ENDED);
    }

    @Test
    @DisplayName("delegacion de voto ACTIVA o asistencia ya registrada de la unidad: el poder no se crea ni se aprueba")
    void competingRepresentation() throws Exception {
        UUID pending = pendingByOwner(doc());
        User tenantUser = member(a, RoleType.RESIDENT);
        relation(a, a.home(), tenantUser, "Arrendatario");
        VotingDelegation d = VotingDelegation.builder().property(a.home()).ownerUser(a.owner()).delegateUser(tenantUser)
                .status(VotingDelegationStatus.ACTIVE).build();
        d.setTenantId(a.tenantId());
        delegationRepository.save(d);
        assertCode(action(a.admin(), a, asmA, pending, "approve", null), 409, "PROXY_CONFLICTS_WITH_DELEGATION");
        assertThat(status(pending)).isEqualTo("PENDING_REVIEW");

        AssemblyAttendee att = AssemblyAttendee.builder().assembly(asmA).property(a.neighbourHome()).build();
        att.setTenantId(a.tenantId());
        attendeeRepository.save(att);
        assertCode(ownerRequest(a.neighbour(), a, asmA, data(a.neighbourHome().getId(), null, null, doc()), PDF), 409, "PROXY_BLOCKED_BY_ATTENDANCE");
        User coOwner = member(a, RoleType.RESIDENT);
        relation(a, a.vacant(), coOwner, "Propietario");
        VotingDelegation d2 = VotingDelegation.builder().property(a.vacant()).ownerUser(coOwner).delegateUser(tenantUser)
                .status(VotingDelegationStatus.ACTIVE).build();
        d2.setTenantId(a.tenantId());
        delegationRepository.save(d2);
        assertCode(ownerRequest(coOwner, a, asmA, data(a.vacant().getId(), null, null, doc()), PDF), 409, "PROXY_CONFLICTS_WITH_DELEGATION");
    }

    @Test
    @DisplayName("el otorgante dejo de ser propietario: el poder no se aprueba ni lo revoca el (404)")
    void grantorNoLongerOwner() throws Exception {
        UUID id = pendingByOwner(doc());
        Resident rel = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        rel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(rel);
        relation(a, a.vacant(), a.owner(), "Propietario"); // sigue en la comunidad por otra unidad
        assertCode(action(a.admin(), a, asmA, id, "approve", null), 409, "GRANTOR_NOT_ACTIVE_OWNER");
        assertCode(action(a.owner(), a, asmA, id, "revoke", null), 404, null);
        // Sin ninguna relacion activa, el filtro existente (D-4) ya le corta el acceso a la comunidad.
        User exOwner = member(a, RoleType.RESIDENT);
        Resident exRel = relation(a, a.neighbourHome(), exOwner, "Propietario");
        exRel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(exRel);
        assertThat(ownerRequest(exOwner, a, asmA, data(a.neighbourHome().getId(), null, null, doc()), PDF).getStatus()).isEqualTo(401);
        assertThat(status(id)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @DisplayName("administracion de OTRA comunidad (404, o 401/403 con la cabecera de A) y administrador desactivado (401/403)")
    void foreignOrDisabledAdmin() throws Exception {
        UUID id = pendingByOwner(doc());
        MockHttpServletResponse own = action(b.admin(), b, asmA, id, "approve", null);
        assertCode(own, 404, null);
        assertThat(action(b.admin(), a, asmA, id, "approve", null).getStatus()).as("sin membresia en A").isIn(401, 403);
        MockHttpServletResponse list = send(b.admin(), b, get("/api/v1/assemblies/{a}/proxies", asmA.getId()));
        assertThat(list.getStatus()).isEqualTo(200);
        assertThat(json(list).path("data").size()).isZero();

        Membership m = membershipRepository.findByUserIdAndTenantId(a.admin().getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(action(a.admin(), a, asmA, id, "approve", null).getStatus()).as("membresia desactivada").isIn(401, 403);
        assertThat(status(id)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @DisplayName("apoderado con cuenta: sin permisos de propietario (no revoca, no lee el documento, no solicita por la unidad)")
    void proxyHasNoOwnerPermissions() throws Exception {
        UUID id = created(ownerRequest(a.owner(), a, asmA, data(a.home().getId(), null, a.neighbour().getId(), doc()), PDF));
        assertThat(action(a.admin(), a, asmA, id, "approve", null).getStatus()).isEqualTo(200);
        assertCode(action(a.neighbour(), a, asmA, id, "revoke", null), 404, null);
        assertCode(send(a.neighbour(), a, get("/api/v1/assemblies/{a}/proxies/{p}/document", asmA.getId(), id)), 404, null);
        assertThat(residentRepository.findByUserIdAndTenantId(a.neighbour().getId(), a.tenantId()))
                .noneMatch(r -> r.getProperty().getId().equals(a.home().getId()));
        assertCode(send(a.neighbour(), a, post("/api/v1/properties/{p}/voting-delegation", a.home().getId())), 403, null);
        assertThat(status(id)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("documento: lo leen la administracion y el otorgante (no-store, adjunto, auditado); nadie mas")
    void documentAccess() throws Exception {
        UUID id = pendingByOwner(doc());
        for (User u : List.of(a.admin(), a.owner())) {
            MockHttpServletResponse r = send(u, a, get("/api/v1/assemblies/{a}/proxies/{p}/document", asmA.getId(), id));
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(r.getContentAsByteArray()).isEqualTo(PDF);
            assertThat(r.getHeader("Cache-Control")).contains("no-store");
            assertThat(r.getHeader("Content-Disposition")).startsWith("attachment");
        }
        assertThat(events(id, "PROXY_DOCUMENT_ACCESSED", "SUCCESS")).isEqualTo(2);
        assertCode(send(a.guard(), a, get("/api/v1/assemblies/{a}/proxies/{p}/document", asmA.getId(), id)), 403, null);
        assertCode(send(b.admin(), b, get("/api/v1/assemblies/{a}/proxies/{p}/document", asmA.getId(), id)), 404, null);
    }

    @Test
    @DisplayName("limite de unidades por apoderado (configurable; 1 en esta prueba): segunda unidad del mismo apoderado 409")
    void representationLimit() throws Exception {
        String same = doc();
        UUID first = pendingByOwner(same);
        UUID second = created(ownerRequest(a.neighbour(), a, asmA, data(a.neighbourHome().getId(), null, null, same.toLowerCase()), PDF));
        assertThat(action(a.admin(), a, asmA, first, "approve", null).getStatus()).isEqualTo(200);
        assertCode(action(a.admin(), a, asmA, second, "approve", null), 409, "PROXY_REPRESENTATION_LIMIT_REACHED");
        assertThat(status(second)).isEqualTo("PENDING_REVIEW");
    }

    // ── concurrencia (peticiones HTTP simultaneas, transacciones independientes) ───────────────

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

    private static List<Integer> statuses(List<MockHttpServletResponse> r) {
        return r.stream().map(MockHttpServletResponse::getStatus).toList();
    }

    @Test
    @DisplayName("carrera 1 · dos copropietarios solicitan a la vez: un poder, un 409, sin documentos huerfanos")
    void raceTwoCoOwners() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var c = fixture.create();
            Side s = c.a();
            Assembly asm = assembly(s);
            User co = member(s, RoleType.RESIDENT);
            relation(s, s.home(), co, "Propietario");
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> ownerRequest(s.owner(), s, asm, data(s.home().getId(), null, null, doc()), PDF),
                    () -> ownerRequest(co, s, asm, data(s.home().getId(), null, null, doc()), PDF)));
            assertThat(statuses(r)).as("ronda " + round).containsExactlyInAnyOrder(201, 409);
            r.stream().filter(x -> x.getStatus() == 409).forEach(x -> {
                try { assertThat(json(x).path("code").asText()).isEqualTo("PROXY_ALREADY_EXISTS"); } catch (Exception e) { throw new AssertionError(e); }
            });
            assertThat(liveProxies(asm, s.home())).isEqualTo(1);
            assertThat(storedFiles(s)).as("ronda " + round + ": el documento del perdedor se elimina").isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from assembly_proxy_events where assembly_id = ? and action = 'PROXY_REQUESTED'",
                    Long.class, asm.getId())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("carrera 2 · dos administradores aprueban a la vez el mismo poder, y dos poderes del mismo apoderado (limite 1)")
    void raceTwoAdminsApprove() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var c = fixture.create();
            Side s = c.a();
            Assembly asm = assembly(s);
            User admin2 = member(s, RoleType.COMMUNITY_ADMIN);
            UUID id = created(ownerRequest(s.owner(), s, asm, data(s.home().getId(), null, null, doc()), PDF));
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> action(s.admin(), s, asm, id, "approve", null), () -> action(admin2, s, asm, id, "approve", null)));
            assertThat(statuses(r)).as("ronda " + round).containsExactlyInAnyOrder(200, 409);
            assertThat(events(id, "PROXY_APPROVED", "SUCCESS")).isEqualTo(1);

            String same = doc();
            UUID p1 = created(ownerRequest(s.neighbour(), s, asm, data(s.neighbourHome().getId(), null, null, same), PDF));
            User coOwner = member(s, RoleType.RESIDENT);
            relation(s, s.vacant(), coOwner, "Propietario");
            UUID p2 = created(ownerRequest(coOwner, s, asm, data(s.vacant().getId(), null, null, same), PDF));
            List<MockHttpServletResponse> r2 = concurrently(List.of(
                    () -> action(s.admin(), s, asm, p1, "approve", null), () -> action(admin2, s, asm, p2, "approve", null)));
            assertThat(statuses(r2)).as("ronda " + round + " limite").containsExactlyInAnyOrder(200, 409);
            assertThat(Stream.of(p1, p2).filter(p -> status(p).equals("APPROVED")).count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("carrera 3 · aprobacion frente a creacion de delegacion: serializadas por la unidad; exactamente una gana (P2)")
    void raceApprovalVersusDelegation() throws Exception {
        int gap = 0, blocked = 0;
        for (int round = 0; round < ROUNDS; round++) {
            var c = fixture.create();
            Side s = c.a();
            Assembly asm = assembly(s);
            User tenantUser = member(s, RoleType.RESIDENT);
            relation(s, s.home(), tenantUser, "Arrendatario");
            UUID id = created(ownerRequest(s.owner(), s, asm, data(s.home().getId(), null, null, doc()), PDF));
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> action(s.admin(), s, asm, id, "approve", null),
                    () -> send(s.owner(), s, post("/api/v1/properties/{p}/voting-delegation", s.home().getId()))));
            int approve = r.get(0).getStatus(), delegate = r.get(1).getStatus();
            // P1 documento la brecha (la delegacion ignoraba el poder aprobado, 5/5). P2 la cierra: exactamente una gana.
            assertThat(approve).as("ronda " + round).isIn(200, 409);
            assertThat(delegate).as("ronda " + round).isIn(200, 409);
            assertThat((approve == 200 ? 1 : 0) + (delegate == 200 ? 1 : 0)).as("ronda " + round + ": exclusividad").isEqualTo(1);
            if (approve == 409) {
                blocked++;
                assertThat(json(r.get(0)).path("code").asText()).isEqualTo("PROXY_CONFLICTS_WITH_DELEGATION");
                assertThat(status(id)).isEqualTo("PENDING_REVIEW");
            } else {
                gap++; // aprobacion primero: la delegacion posterior se rechaza (cerrado en P2)
                assertThat(json(r.get(1)).path("code").asText()).isEqualTo("DELEGATION_BLOCKED_BY_PROXY");
                assertThat(status(id)).isEqualTo("APPROVED");
            }
        }
        System.out.printf("P1 carrera 3 (P2): aprobacion bloqueada=%d, delegacion bloqueada=%d%n", blocked, gap);
    }

    @Test
    @DisplayName("carrera 4 · revocacion frente a aprobacion: el estado final es siempre REVOKED; sin 5xx")
    void raceRevokeVersusApprove() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var c = fixture.create();
            Side s = c.a();
            Assembly asm = assembly(s);
            UUID id = created(ownerRequest(s.owner(), s, asm, data(s.home().getId(), null, null, doc()), PDF));
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> action(s.admin(), s, asm, id, "approve", null), () -> action(s.owner(), s, asm, id, "revoke", null)));
            assertThat(r.get(1).getStatus()).isEqualTo(200);
            assertThat(r.get(0).getStatus()).isIn(200, 409);
            assertThat(status(id)).as("ronda " + round).isEqualTo("REVOKED");
            assertThat(events(id, "PROXY_APPROVED", "SUCCESS")).isEqualTo(r.get(0).getStatus() == 200 ? 1 : 0);
        }
    }

    @Test
    @DisplayName("carrera 5 · aprobacion frente a finalizacion de la asamblea: nunca se aprueba despues de finalizar; sin 5xx")
    void raceApproveVersusFinish() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var c = fixture.create();
            Side s = c.a();
            Assembly asm = assembly(s);
            UUID id = created(ownerRequest(s.owner(), s, asm, data(s.home().getId(), null, null, doc()), PDF));
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> action(s.admin(), s, asm, id, "approve", null),
                    () -> send(s.admin(), s, post("/api/v1/assemblies/{id}/finish", asm.getId()))));
            assertThat(r.get(1).getStatus()).isEqualTo(200);
            int approve = r.get(0).getStatus();
            assertThat(approve).as("ronda " + round).isIn(200, 409);
            if (approve == 409) assertThat(json(r.get(0)).path("code").asText()).isEqualTo("ASSEMBLY_ENDED");
            assertThat(status(id)).isEqualTo(approve == 200 ? "APPROVED" : "PENDING_REVIEW");
            if (approve == 200) {
                // La aprobacion se confirmo antes que la finalizacion (FOR SHARE frente a FOR NO KEY UPDATE de la asamblea).
                java.time.OffsetDateTime reviewed = jdbc.queryForObject("select reviewed_at from assembly_proxies where id = ?",
                        java.time.OffsetDateTime.class, id);
                java.time.OffsetDateTime finished = jdbc.queryForObject("select updated_at from assemblies where id = ?",
                        java.time.OffsetDateTime.class, asm.getId());
                assertThat(reviewed).isBeforeOrEqualTo(finished);
            }
        }
    }
}
