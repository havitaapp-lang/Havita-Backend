package com.havita.modules.assembly.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.common.storage.ObjectStorageService;
import com.havita.common.storage.StorageUnavailableException;
import com.havita.modules.assembly.application.AssemblyProxyEvents;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.Property;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fase 2 / P5-C — documentos de poderes de extremo a extremo con {@code storage.provider=r2} contra un EMULADOR S3
 * local (adobe/s3mock) y PostgreSQL aislado (Flyway V100-V106). Nunca R2 real ni credenciales reales. Bandera de poderes
 * activa SOLO en este contexto. Cubre validacion por contenido, privacidad y autorizacion por comunidad, integridad
 * SHA-256, documento ausente, fallos parciales PostgreSQL/almacenamiento, reintentos concurrentes y conciliacion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProxyDocumentR2IT {

    static final String BUCKET = "havita-p5-docs";

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("p5_docs_it");

    @SuppressWarnings("resource")
    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:3.11.0")
            .withExposedPorts(9090)
            .withEnv("initialBuckets", BUCKET)
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
            .waitingFor(Wait.forLogMessage(".*Started S3MockApplication.*", 1)
                    .withStartupTimeout(java.time.Duration.ofMinutes(3)));

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            PG.start();
            S3.start();
        }
    }

    static String endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
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
        r.add("storage.provider", () -> "r2");
        r.add("storage.r2.endpoint", ProxyDocumentR2IT::endpoint);
        r.add("storage.r2.bucket-name", () -> BUCKET);
        r.add("storage.r2.access-key-id", () -> "test-key-not-a-secret");
        r.add("storage.r2.secret-access-key", () -> "test-secret-not-a-secret");
        r.add("storage.r2.max-attempts", () -> "2");
    }

    private static final int ROUNDS = 8;
    static final byte[] PDF = "%PDF-1.7\n% poder sintetico P5 R2\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = concat(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, "IHDR sintetico".getBytes());
    static final byte[] JPEG = concat(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, "JFIF sintetico".getBytes());

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private ObjectStorageService storage;
    @SpyBean private AssemblyProxyEvents events;

    private Side a;
    private Side b;
    private Assembly asmA;
    private static S3Client s3;

    @BeforeEach
    void seed() {
        reset((Object) AopTestUtils.getUltimateTargetObject(storage));
        reset((Object) AopTestUtils.getUltimateTargetObject(events));
        var c = fixture.create();
        a = c.a();
        b = c.b();
        asmA = assembly(a);
        if (s3 == null) {
            s3 = com.havita.common.storage.R2ObjectStorageService.buildClient(endpoint(), BUCKET, "test-key-not-a-secret",
                    "test-secret-not-a-secret", "auto", Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(10),
                    Duration.ofSeconds(30), 2);
        }
    }

    private Assembly assembly(Side s) {
        Assembly x = Assembly.builder().title("Asamblea P5 R2").assemblyDate(LocalDate.now().plusDays(3)).startTime(LocalTime.NOON).build();
        x.setTenantId(s.tenantId());
        return assemblyRepository.save(x);
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

    private MockHttpServletResponse request(User grantor, Side s, Assembly asm, Property unit, byte[] doc) throws Exception {
        String data = "{\"propertyId\":\"" + unit.getId() + "\",\"proxyFullName\":\"Apoderada R2\",\"proxyDocumentType\":\"CC\","
                + "\"proxyDocumentNumber\":\"R2" + UUID.randomUUID().toString().substring(0, 8) + "\"}";
        return send(grantor, s, multipart("/api/v1/assemblies/{a}/proxies", asm.getId())
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, data.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("document", "poder.pdf", "application/pdf", doc)));
    }

    private UUID created(MockHttpServletResponse r) throws Exception {
        assertCode(r, 201, null);
        return UUID.fromString(json(r).at("/data/id").asText());
    }

    private MockHttpServletResponse download(User u, Side s, Assembly asm, UUID proxy) throws Exception {
        return send(u, s, get("/api/v1/assemblies/{a}/proxies/{p}/document", asm.getId(), proxy));
    }

    private String keyOf(UUID proxy) {
        return jdbc.queryForObject("select document_key from assembly_proxies where id = ?", String.class, proxy);
    }

    private List<String> objects(Side s) {
        List<String> keys = new ArrayList<>();
        for (var page : s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(BUCKET)
                .prefix("tenants/" + s.tenantId() + "/assembly-proxies/").build())) {
            page.contents().forEach(o -> keys.add(o.key()));
        }
        return keys;
    }

    private long proxyEvents(UUID proxy, String action) {
        Long n = jdbc.queryForObject("select count(*) from assembly_proxy_events where proxy_id = ? and action = ?", Long.class, proxy, action);
        return n == null ? 0 : n;
    }

    // ── subida y validacion ───────────────────────────────────────────────────

    @Test
    @DisplayName("PDF, PNG y JPEG validos en R2 (emulado): clave privada con el id de la operacion, SHA-256 y descarga autorizada")
    void validUploadsAndDownload() throws Exception {
        UUID pdf = created(request(a.owner(), a, asmA, a.home(), PDF));
        UUID png = created(request(a.neighbour(), a, asmA, a.neighbourHome(), PNG));
        assertCode(send(a.owner(), a, post("/api/v1/assemblies/{a}/proxies/{p}/revoke", asmA.getId(), pdf)), 200, null);
        UUID jpeg = created(request(a.owner(), a, asmA, a.home(), JPEG));
        for (UUID id : List.of(pdf, png, jpeg)) {
            String key = keyOf(id);
            assertThat(key).startsWith("tenants/" + a.tenantId() + "/assembly-proxies/").contains("-poder-" + id + ".");
            assertThat(key).doesNotContain("Apoderada").doesNotContain("poder.pdf");
        }
        assertThat(objects(a)).containsExactlyInAnyOrder(keyOf(pdf), keyOf(png), keyOf(jpeg));
        assertThat(jdbc.queryForObject("select document_sha256 from assembly_proxies where id = ?", String.class, png))
                .isEqualTo(com.havita.modules.assembly.application.ProxyDocumentInspector.sha256(PNG));
        // Descarga: administracion y otorgante; adjunto, sin cache, sin sniffing; contenido intacto y auditado.
        MockHttpServletResponse r = download(a.admin(), a, asmA, png);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentAsByteArray()).isEqualTo(PNG);
        assertThat(r.getContentType()).isEqualTo("image/png");
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
        assertThat(r.getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download(a.neighbour(), a, asmA, png).getContentAsByteArray()).isEqualTo(PNG);
        assertThat(proxyEvents(png, "PROXY_DOCUMENT_ACCESSED")).isEqualTo(2);
        assertThat(download(a.owner(), a, asmA, jpeg).getContentAsByteArray()).isEqualTo(JPEG);
    }

    @Test
    @DisplayName("archivo falsificado (extension .pdf sin firma) y demasiado grande: 400, sin objeto ni fila")
    void invalidUploads() throws Exception {
        assertCode(request(a.owner(), a, asmA, a.home(), "MZ ejecutable disfrazado".getBytes()), 400, "INVALID_PROXY_DOCUMENT");
        byte[] big = concat("%PDF-1.7\n".getBytes(), new byte[70_000]);
        assertCode(request(a.owner(), a, asmA, a.home(), big), 400, "PROXY_DOCUMENT_TOO_LARGE");
        assertThat(objects(a)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from assembly_proxies where tenant_id = ?", Long.class, a.tenantId())).isZero();
    }

    // ── autorizacion de la descarga ───────────────────────────────────────────

    @Test
    @DisplayName("descarga no autorizada: otro residente, otra comunidad, administrador desactivado y otorgante dado de baja")
    void unauthorizedDownloads() throws Exception {
        UUID id = created(request(a.owner(), a, asmA, a.home(), PDF));
        assertThat(download(a.neighbour(), a, asmA, id).getStatus()).isEqualTo(404);
        assertThat(download(b.admin(), b, asmA, id).getStatus()).isEqualTo(404);
        assertThat(download(b.admin(), a, asmA, id).getStatus()).isIn(401, 403);
        String adminToken = fixture.bearer(a.admin());
        Membership m = membershipRepository.findByUserIdAndTenantId(a.admin().getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(send(adminToken, a, get("/api/v1/assemblies/{a}/proxies/{p}/document", asmA.getId(), id)).getStatus()).isIn(401, 403);
        m.setStatus(MembershipStatus.ACTIVE);
        membershipRepository.save(m);
        // El otorgante deja de ser propietario activo (conserva otra relacion para seguir autenticado).
        var rel = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        rel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(rel);
        assertThat(download(a.owner(), a, asmA, id).getStatus()).isIn(401, 403, 404);
        assertThat(proxyEvents(id, "PROXY_DOCUMENT_ACCESSED")).isZero();
    }

    // ── integridad y documento ausente ────────────────────────────────────────

    @Test
    @DisplayName("integridad: objeto alterado en el almacenamiento → 409 sin entregar el contenido; objeto ausente → 409; ambos auditados")
    void integrityAndMissing() throws Exception {
        UUID id = created(request(a.owner(), a, asmA, a.home(), PDF));
        String key = keyOf(id);
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromBytes("%PDF-1.7 ALTERADO".getBytes()));
        MockHttpServletResponse altered = download(a.admin(), a, asmA, id);
        assertCode(altered, 409, "PROXY_DOCUMENT_INTEGRITY_FAILED");
        assertThat(altered.getContentAsString()).doesNotContain("ALTERADO");
        assertThat(proxyEvents(id, "PROXY_DOCUMENT_INTEGRITY_FAILED")).isEqualTo(1);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build());
        assertCode(download(a.admin(), a, asmA, id), 409, "PROXY_DOCUMENT_MISSING");
        assertThat(proxyEvents(id, "PROXY_DOCUMENT_MISSING")).isEqualTo(1);
        assertThat(proxyEvents(id, "PROXY_DOCUMENT_ACCESSED")).isZero();
        // La conciliacion lo informa como ausente (sin claves en la respuesta).
        MockHttpServletResponse rep = send(a.admin(), a, get("/api/v1/assemblies/proxy-settings/document-reconciliation"));
        assertCode(rep, 200, null);
        assertThat(json(rep).at("/data/proxiesWithMissingDocument/0").asText()).isEqualTo(id.toString());
        assertThat(rep.getContentAsString()).doesNotContain("tenants/");
        assertThat(send(a.owner(), a, get("/api/v1/assemblies/proxy-settings/document-reconciliation")).getStatus()).isEqualTo(403);
    }

    // ── fallos parciales PostgreSQL / almacenamiento ──────────────────────────

    @Test
    @DisplayName("fallo de PostgreSQL despues de subir: la fila no queda y el objeto se compensa (borra); sin huerfanos")
    void databaseFailureAfterUpload() throws Exception {
        AssemblyProxyEvents target = AopTestUtils.getUltimateTargetObject(events);
        doThrow(new IllegalStateException("fallo simulado")).when(target).success(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("PROXY_REQUESTED"), ArgumentMatchers.any(), ArgumentMatchers.any());
        try {
            assertThat(request(a.owner(), a, asmA, a.home(), PDF).getStatus()).isEqualTo(500);
        } finally {
            reset((Object) target);
        }
        assertThat(jdbc.queryForObject("select count(*) from assembly_proxies where tenant_id = ?", Long.class, a.tenantId())).isZero();
        assertThat(objects(a)).as("compensacion: objeto borrado").isEmpty();
    }

    @Test
    @DisplayName("la compensacion tambien falla: queda un huerfano, nunca una fila sin objeto; la conciliacion lo identifica por operacion")
    void compensationFailureLeavesReportedOrphan() throws Exception {
        AssemblyProxyEvents ev = AopTestUtils.getUltimateTargetObject(events);
        ObjectStorageService st = AopTestUtils.getUltimateTargetObject(storage);
        doThrow(new IllegalStateException("fallo simulado")).when(ev).success(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("PROXY_REQUESTED"), ArgumentMatchers.any(), ArgumentMatchers.any());
        doThrow(new StorageUnavailableException("simulado")).when(st).delete(ArgumentMatchers.anyString());
        try {
            assertThat(request(a.owner(), a, asmA, a.home(), PDF).getStatus()).isEqualTo(500);
        } finally {
            reset((Object) ev);
            reset((Object) st);
        }
        assertThat(jdbc.queryForObject("select count(*) from assembly_proxies where tenant_id = ?", Long.class, a.tenantId())).isZero();
        List<String> left = objects(a);
        assertThat(left).hasSize(1);
        JsonNode rep = json(send(a.admin(), a, get("/api/v1/assemblies/proxy-settings/document-reconciliation"))).at("/data");
        assertThat(rep.path("orphanObjects").asInt()).isEqualTo(1);
        assertThat(left.get(0)).contains("-poder-" + rep.at("/orphanOperationIds/0").asText() + ".");
        assertThat(rep.path("proxiesWithMissingDocument")).isEmpty();
    }

    @Test
    @DisplayName("almacenamiento no disponible al subir: 503 STORAGE_UNAVAILABLE, sin fila del poder")
    void uploadFailure() throws Exception {
        ObjectStorageService st = AopTestUtils.getUltimateTargetObject(storage);
        doThrow(new StorageUnavailableException("El almacenamiento de documentos no está disponible; intenta de nuevo."))
                .when(st).upload(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(byte[].class), ArgumentMatchers.anyBoolean());
        try {
            assertCode(request(a.owner(), a, asmA, a.home(), PDF), 503, "STORAGE_UNAVAILABLE");
        } finally {
            reset((Object) st);
        }
        assertThat(jdbc.queryForObject("select count(*) from assembly_proxies where tenant_id = ?", Long.class, a.tenantId())).isZero();
        assertThat(objects(a)).isEmpty();
        created(request(a.owner(), a, asmA, a.home(), PDF)); // recuperado: el reintento funciona
    }

    @Test
    @DisplayName("documento de un poder aprobado: no se reemplaza (409) y el objeto no cambia")
    void approvedDocumentImmutable() throws Exception {
        UUID id = created(request(a.owner(), a, asmA, a.home(), PDF));
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{a}/proxies/{p}/approve", asmA.getId(), id)), 200, null);
        String key = keyOf(id);
        MockHttpServletResponse r = send(a.owner(), a, multipart("/api/v1/assemblies/{a}/proxies/{p}/document", asmA.getId(), id)
                .file(new MockMultipartFile("document", "otro.pdf", "application/pdf", "%PDF-1.7 otro".getBytes())));
        assertCode(r, 409, "PROXY_NOT_EDITABLE");
        assertThat(keyOf(id)).isEqualTo(key);
        assertThat(objects(a)).containsExactly(key);
        assertThat(download(a.admin(), a, asmA, id).getContentAsByteArray()).isEqualTo(PDF);
    }

    // ── reintentos concurrentes ───────────────────────────────────────────────

    @Test
    @DisplayName("reintento de subida: dos solicitudes identicas a la vez → un poder y un objeto (el otro se compensa) (8 rondas)")
    void concurrentRetries() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Side s = fixture.create().a();
            Assembly asm = assembly(s);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<MockHttpServletResponse> c = () -> {
                    go.await();
                    return request(s.owner(), s, asm, s.home(), PDF);
                };
                fs.add(pool.submit(c));
            }
            go.countDown();
            List<MockHttpServletResponse> rs = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : fs) rs.add(f.get(60, TimeUnit.SECONDS));
            pool.shutdown();
            for (MockHttpServletResponse r : rs) assertThat(r.getStatus()).as("nunca 5xx: " + r.getContentAsString()).isLessThan(500);
            assertThat(rs.stream().filter(r -> r.getStatus() == 201).count()).as("ronda " + round).isEqualTo(1);
            assertThat(rs.stream().filter(r -> r.getStatus() == 409).count()).isEqualTo(1);
            List<String> keys = jdbc.queryForList("select document_key from assembly_proxies where tenant_id = ?", String.class, s.tenantId());
            assertThat(keys).hasSize(1);
            assertThat(objects(s)).as("sin huerfanos tras la compensacion").containsExactlyElementsOf(keys);
        }
    }
}
