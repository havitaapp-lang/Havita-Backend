package com.havita.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.marketplace.domain.ListingStatus;
import com.havita.modules.marketplace.domain.ListingType;
import com.havita.modules.marketplace.domain.MarketplaceListing;
import com.havita.modules.marketplace.domain.PriceType;
import com.havita.modules.marketplace.infrastructure.MarketplaceListingRepository;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-9-9: acceso a archivos por recurso. Ninguna clave autoriza por si misma; los archivos privados exigen identidad y
 * permisos vigentes sobre el recurso que los referencia; no hay URLs publicas. Almacenamiento en un directorio propio de
 * build/ (nunca el ./storage real). Datos sinteticos (example.test).
 */
@TestPropertySource(properties = "storage.local.base-path=build/it-storage/file-access")
class FileAccessSecurityIT extends PostgresIntegrationTest {

    private static final byte[] PDF = "%PDF-1.4 sintetico".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private MarketplaceListingRepository listingRepository;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    // ── utilidades ────────────────────────────────────────────────────────────

    private MvcResult call(String token, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        if (token != null) req.header("Authorization", token);
        if (tenant != null) req.header("X-Tenant-ID", tenant.toString());
        return mvc.perform(req).andReturn();
    }

    private int status(String token, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        return call(token, tenant, req).getResponse().getStatus();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
    }

    private MvcResult upload(User u, UUID tenant, String folder, String fileName, byte[] content, String isPublic) throws Exception {
        var req = multipart("/api/v1/files/upload").file(new MockMultipartFile("file", fileName, "application/octet-stream", content))
                .param("folder", folder);
        if (isPublic != null) req.param("isPublic", isPublic);
        return call(fixture.bearer(u), tenant, req);
    }

    private String uploadKey(User u, UUID tenant, String folder, String fileName, byte[] content) throws Exception {
        MvcResult r = upload(u, tenant, folder, fileName, content, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return data(r).path("storageKey").asText();
    }

    private MockHttpServletRequestBuilder download(String key) {
        return get("/api/v1/files/download").param("key", key);
    }

    private MvcResult createDocument(User admin, UUID tenant, String key) throws Exception {
        String body = "{\"title\":\"Acta sintetica\",\"fileSize\":\"1 KB\",\"fileUrl\":\"https://ejemplo.invalid/x\",\"storageKey\":\"%s\"}".formatted(key);
        return call(fixture.bearer(admin), tenant, post("/api/v1/communications/documents").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MarketplaceListing listing(Side side, User author, ListingStatus status, String imageKey) {
        MarketplaceListing l = new MarketplaceListing();
        l.setTenantId(side.tenantId());
        l.setCreatedByUser(author);
        l.setType(ListingType.PRODUCT);
        l.setCategory("Otros");
        l.setTitle("Bicicleta sintetica");
        l.setDescription("Publicacion de prueba");
        l.setPriceType(PriceType.CONTACT);
        l.setStatus(status);
        l.setImageKeysList(List.of(imageKey));
        return listingRepository.save(l);
    }

    /** Ruta relativa (path + query) de la primera imagen firmada de la publicacion, vista por {@code viewer}. */
    private String signedUrlFor(User viewer, UUID tenant, UUID listingId) throws Exception {
        MvcResult r = call(fixture.bearer(viewer), tenant, get("/api/v1/marketplace/listings/{id}", listingId));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String url = data(r).path("imageUrls").get(0).asText();
        assertThat(url).startsWith("/api/v1/files/signed?").doesNotContain("/public/");
        return url;
    }

    private MockHttpServletRequestBuilder anonymousGet(String relativeUrl) {
        return get(java.net.URI.create(relativeUrl));
    }

    // ── 1. Superficie publica ─────────────────────────────────────────────────

    @Test
    @DisplayName("SEC-9-9: /public/files ya no existe: ni anonimo ni con sesion se sirve un archivo por su clave")
    void publicFileEndpointIsGone() throws Exception {
        String key = uploadKey(a.admin(), a.tenantId(), "documents", "acta.pdf", PDF);
        createDocument(a.admin(), a.tenantId(), key);
        assertThat(mvc.perform(get("/api/v1/public/files/" + key)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(status(fixture.bearer(a.owner()), a.tenantId(), get("/api/v1/public/files/" + key))).isEqualTo(404);
        // Tampoco la descarga privada sin sesion.
        assertThat(mvc.perform(download(key)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ── 2. Documentos ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("documentos: los roles de la comunidad descargan el archivo de un documento, como descarga y con nosniff")
    void documentFileIsReadableByCommunityRoles() throws Exception {
        String key = uploadKey(a.admin(), a.tenantId(), "documents", "reglamento.pdf", PDF);
        assertThat(createDocument(a.admin(), a.tenantId(), key).getResponse().getStatus()).isEqualTo(201);
        for (User u : new User[]{a.admin(), a.owner(), a.guard()}) {
            MvcResult r = call(fixture.bearer(u), a.tenantId(), download(key));
            assertThat(r.getResponse().getStatus()).as(u.getEmail()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(PDF);
            assertThat(r.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
            assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("sandbox");
        }
    }

    @Test
    @DisplayName("documentos: otra comunidad no accede (404), ni con su comunidad ni pidiendo la ajena")
    void otherCommunityCannotReadDocument() throws Exception {
        String key = uploadKey(a.admin(), a.tenantId(), "documents", "balance.pdf", PDF);
        createDocument(a.admin(), a.tenantId(), key);
        assertThat(status(fixture.bearer(b.admin()), b.tenantId(), download(key))).isEqualTo(404);
        assertThat(status(fixture.bearer(b.owner()), b.tenantId(), download(key))).isEqualTo(404);
        assertThat(status(fixture.bearer(b.admin()), a.tenantId(), download(key))).isIn(401, 403);
    }

    @Test
    @DisplayName("documentos: un archivo sin documento asociado solo lo lee la administracion; no se asocian claves ajenas")
    void unattachedDocumentFileAndForeignKeys() throws Exception {
        String key = uploadKey(a.admin(), a.tenantId(), "documents", "borrador.pdf", PDF);
        assertThat(status(fixture.bearer(a.owner()), a.tenantId(), download(key))).isEqualTo(404);
        assertThat(status(fixture.bearer(a.guard()), a.tenantId(), download(key))).isEqualTo(404);
        assertThat(status(fixture.bearer(a.admin()), a.tenantId(), download(key))).isEqualTo(200);

        // Un documento de A no puede apuntar a un archivo de B ni a una imagen del marketplace.
        String keyB = uploadKey(b.admin(), b.tenantId(), "documents", "secreto.pdf", PDF);
        String image = uploadKey(a.owner(), a.tenantId(), "marketplace", "foto.png", PNG);
        assertThat(createDocument(a.admin(), a.tenantId(), keyB).getResponse().getStatus()).isEqualTo(409);
        assertThat(createDocument(a.admin(), a.tenantId(), image).getResponse().getStatus()).isEqualTo(409);
        assertThat(status(fixture.bearer(a.owner()), a.tenantId(), download(image))).as("su propia imagen").isEqualTo(200);
        assertThat(status(fixture.bearer(a.neighbour()), a.tenantId(), download(image))).as("imagen sin publicacion").isEqualTo(404);
    }

    // ── 3. Claves manipuladas ─────────────────────────────────────────────────

    @Test
    @DisplayName("claves manipuladas: traversal, absolutas, codificadas, de otra comunidad o de carpetas sin regla -> 404")
    void tamperedKeysAreRejected() throws Exception {
        String key = uploadKey(a.admin(), a.tenantId(), "documents", "acta.pdf", PDF);
        createDocument(a.admin(), a.tenantId(), key);
        // Un archivo real en una carpeta sin regla (escrito directamente en el almacenamiento de la prueba).
        Path stray = Paths.get("build/it-storage/file-access/tenants/" + a.tenantId() + "/branding/2026/11/aaaaaaaa-logo.png");
        Files.createDirectories(stray.getParent());
        Files.write(stray, PNG);
        String strayKey = "tenants/" + a.tenantId() + "/branding/2026/11/aaaaaaaa-logo.png";

        String admin = fixture.bearer(a.admin());
        List<String> bad = List.of(
                strayKey,
                "tenants/" + a.tenantId() + "/documents/../../" + b.tenantId() + "/documents/2026/11/x.pdf",
                "tenants/" + a.tenantId() + "/documents/2026/11/..%2F..%2Fapplication.yml",
                "/etc/passwd",
                "..\\..\\application.yml",
                "tenants/" + a.tenantId() + "//documents/2026/11/x.pdf",
                "tenants/" + a.tenantId() + "/documents/2026/11/x.pdf\u0000.png",
                "TENANTS/" + a.tenantId() + "/documents/2026/11/x.pdf",
                key.replace(a.tenantId().toString(), b.tenantId().toString()));
        for (String k : bad) {
            assertThat(status(admin, a.tenantId(), download(k))).as(k).isEqualTo(404);
        }
        // DELETE tampoco acepta claves manipuladas ni de carpetas sin regla.
        assertThat(status(admin, a.tenantId(), delete("/api/v1/files").param("key", strayKey))).isEqualTo(404);
        assertThat(Files.exists(stray)).isTrue();
    }

    // ── 4. Subidas ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("subidas: carpeta y rol los decide el servidor; isPublic se ignora; tipos peligrosos rechazados")
    void uploadPolicy() throws Exception {
        assertThat(upload(a.owner(), a.tenantId(), "documents", "x.pdf", PDF, null).getResponse().getStatus()).isEqualTo(403);
        assertThat(upload(a.guard(), a.tenantId(), "marketplace", "x.png", PNG, null).getResponse().getStatus()).isEqualTo(403);
        assertThat(upload(a.admin(), a.tenantId(), "branding", "logo.png", PNG, null).getResponse().getStatus()).isEqualTo(409);
        assertThat(upload(a.admin(), a.tenantId(), "../tenants", "x.pdf", PDF, null).getResponse().getStatus()).isEqualTo(409);
        assertThat(upload(a.owner(), a.tenantId(), "marketplace", "pagina.html", "<script>x</script>".getBytes(StandardCharsets.UTF_8), null)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(upload(a.owner(), a.tenantId(), "marketplace", "dibujo.svg", "<svg/>".getBytes(StandardCharsets.UTF_8), null)
                .getResponse().getStatus()).isEqualTo(409);

        // La subcarpeta del cliente se ignora: siempre la del propio autor. isPublic=true no crea nada publico.
        MvcResult r = upload(a.owner(), a.tenantId(), "marketplace/" + a.neighbour().getId(), "foto.png", PNG, "true");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        String key = data(r).path("storageKey").asText();
        assertThat(key).startsWith("tenants/" + a.tenantId() + "/marketplace/" + a.owner().getId() + "/");
        assertThat(data(r).path("isPublic").asBoolean()).isFalse();
        assertThat(data(r).path("url").asText()).startsWith("/api/v1/files/signed?").doesNotContain("/public/");
    }

    // ── 5. Marketplace y URLs firmadas ────────────────────────────────────────

    @Test
    @DisplayName("marketplace: la imagen de una publicacion PUBLISHED se ve con URL firmada sin cabecera; la firma no se puede alterar")
    void publishedListingImageViaSignedUrl() throws Exception {
        String key = uploadKey(a.owner(), a.tenantId(), "marketplace", "bici.png", PNG);
        MarketplaceListing l = listing(a, a.owner(), ListingStatus.PUBLISHED, key);
        String url = signedUrlFor(a.neighbour(), a.tenantId(), l.getId());

        MvcResult img = mvc.perform(anonymousGet(url)).andReturn();
        assertThat(img.getResponse().getStatus()).isEqualTo(200);
        assertThat(img.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(img.getResponse().getHeader("Content-Disposition")).startsWith("inline");
        assertThat(img.getResponse().getContentAsByteArray()).isEqualTo(PNG);

        // Firma alterada, usuario cambiado, comunidad cambiada o sin firma -> 404.
        String sig = url.substring(url.indexOf("sig=") + 4);
        List<String> forged = List.of(
                url.replace("sig=" + sig, "sig=" + (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1)),
                url.replace("u=" + a.neighbour().getId(), "u=" + a.owner().getId()),
                url.replace("t=" + a.tenantId(), "t=" + b.tenantId()),
                url.substring(0, url.indexOf("&sig=")));
        for (String f : forged) {
            assertThat(mvc.perform(anonymousGet(f)).andReturn().getResponse().getStatus()).as(f).isIn(400, 404);
        }
        // Otra comunidad no obtiene URL ni ve la publicacion.
        assertThat(status(fixture.bearer(b.owner()), b.tenantId(), get("/api/v1/marketplace/listings/{id}", l.getId()))).isEqualTo(404);
    }

    @Test
    @DisplayName("revocacion: una URL firmada emitida antes deja de servir al perder la membresia, la residencia o la cuenta")
    void signedUrlDoesNotSurviveRevocation() throws Exception {
        String key = uploadKey(a.owner(), a.tenantId(), "marketplace", "mesa.png", PNG);
        MarketplaceListing l = listing(a, a.owner(), ListingStatus.PUBLISHED, key);

        String byNeighbour = signedUrlFor(a.neighbour(), a.tenantId(), l.getId());
        String byGuard = signedUrlFor(a.guard(), a.tenantId(), l.getId());
        User extra = userRepository.save(User.builder().email("vecina-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}x").fullName("Cuenta sintetica").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(extra).tenantId(a.tenantId())
                .role(com.havita.modules.identity.domain.RoleType.SECURITY).status(MembershipStatus.ACTIVE).build());
        String byExtra = signedUrlFor(extra, a.tenantId(), l.getId());
        for (String u : List.of(byNeighbour, byGuard, byExtra)) {
            assertThat(mvc.perform(anonymousGet(u)).andReturn().getResponse().getStatus()).isEqualTo(200);
        }

        // Residente sin unidad activa, porteria con membresia INACTIVE y cuenta desactivada.
        var res = a.neighbourResident();
        res.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(res);
        Membership guard = membershipRepository.findByUserIdAndTenantId(a.guard().getId(), a.tenantId()).orElseThrow();
        guard.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(guard);
        extra.setStatus(UserStatus.INACTIVE);
        userRepository.save(extra);

        for (String u : List.of(byNeighbour, byGuard, byExtra)) {
            assertThat(mvc.perform(anonymousGet(u)).andReturn().getResponse().getStatus()).as(u).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("marketplace: al ocultar la publicacion, sus imagenes solo las ven el autor y la administracion")
    void hiddenListingImagesFollowListingVisibility() throws Exception {
        String key = uploadKey(a.owner(), a.tenantId(), "marketplace", "silla.png", PNG);
        MarketplaceListing l = listing(a, a.owner(), ListingStatus.PUBLISHED, key);
        String neighbourUrl = signedUrlFor(a.neighbour(), a.tenantId(), l.getId());
        l.setStatus(ListingStatus.HIDDEN);
        listingRepository.save(l);

        assertThat(mvc.perform(anonymousGet(neighbourUrl)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(status(fixture.bearer(a.neighbour()), a.tenantId(), download(key))).isEqualTo(404);
        assertThat(status(fixture.bearer(a.owner()), a.tenantId(), download(key))).isEqualTo(200);
        assertThat(status(fixture.bearer(a.admin()), a.tenantId(), download(key))).isEqualTo(200);
    }

    @Test
    @DisplayName("marketplace: una publicacion no puede referenciar documentos, imagenes ajenas ni claves de otra comunidad")
    void listingCannotReferenceForeignFiles() throws Exception {
        String own = uploadKey(a.owner(), a.tenantId(), "marketplace", "propia.png", PNG);
        String neighbours = uploadKey(a.neighbour(), a.tenantId(), "marketplace", "ajena.png", PNG);
        String doc = uploadKey(a.admin(), a.tenantId(), "documents", "acta.pdf", PDF);
        String other = uploadKey(b.owner(), b.tenantId(), "marketplace", "otra.png", PNG);
        MarketplaceListing l = listing(a, a.owner(), ListingStatus.PUBLISHED, own);
        String owner = fixture.bearer(a.owner());
        for (String foreign : List.of(neighbours, doc, other, "tenants/" + a.tenantId() + "/marketplace/x/2026/11/aaaaaaaa-y.png")) {
            MvcResult r = call(owner, a.tenantId(), patch("/api/v1/marketplace/listings/{id}", l.getId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"contactPhone\":\"3000000000\",\"imageKeys\":[\"" + foreign + "\"]}"));
            assertThat(r.getResponse().getStatus()).as(foreign).isEqualTo(409);
        }
        assertThat(listingRepository.findById(l.getId()).orElseThrow().getImageKeysList()).containsExactly(own);
        // Edicion legitima con sus propias claves (las que devuelve imageKeys).
        MvcResult mine = call(owner, a.tenantId(), get("/api/v1/marketplace/my-listings"));
        assertThat(mine.getResponse().getContentAsString()).contains(own);
        assertThat(status(owner, a.tenantId(), patch("/api/v1/marketplace/listings/{id}", l.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"contactPhone\":\"3000000000\",\"imageKeys\":[\"" + own + "\"]}"))).isEqualTo(200);
    }

    // ── 6. Actuator y OpenAPI ─────────────────────────────────────────────────

    @Test
    @DisplayName("Actuator: health publico sin detalles; metrics, env, info y configprops no se exponen")
    void actuatorExposure() throws Exception {
        MvcResult health = mvc.perform(get("/actuator/health")).andReturn();
        assertThat(health.getResponse().getStatus()).isEqualTo(200);
        assertThat(health.getResponse().getContentAsString()).contains("\"status\"").doesNotContain("components").doesNotContain("details");
        for (String p : List.of("/actuator/metrics", "/actuator/env", "/actuator/info", "/actuator/configprops", "/actuator/beans", "/actuator")) {
            assertThat(mvc.perform(get(p)).andReturn().getResponse().getStatus()).as("anonimo " + p).isNotEqualTo(200);
            assertThat(status(fixture.bearer(a.admin()), a.tenantId(), get(p))).as("con sesion " + p).isNotEqualTo(200);
        }
    }

    @Test
    @DisplayName("OpenAPI: desactivado por defecto (sin documentacion ni Swagger UI publicos)")
    void openApiDisabledByDefault() throws Exception {
        for (String p : List.of("/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html")) {
            assertThat(mvc.perform(get(p)).andReturn().getResponse().getStatus()).as(p).isNotEqualTo(200);
        }
    }
}
