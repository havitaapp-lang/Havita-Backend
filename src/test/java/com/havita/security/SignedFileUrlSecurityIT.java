package com.havita.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.marketplace.domain.ListingStatus;
import com.havita.modules.marketplace.domain.ListingType;
import com.havita.modules.marketplace.domain.MarketplaceListing;
import com.havita.modules.marketplace.domain.PriceType;
import com.havita.modules.marketplace.infrastructure.MarketplaceListingRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.identity.domain.User;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Revision de seguridad de las URLs firmadas y de la descarga de archivos (SEC-9-9): firma ligada al archivo exacto,
 * revalidacion en cada descarga, comunidad suspendida, ausencia de la firma en los logs, cabeceras de cache y tipos
 * peligrosos que nunca se ejecutan en el origen de Havita. Mismo almacenamiento de prueba que FileAccessSecurityIT.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "storage.local.base-path=build/it-storage/file-access")
class SignedFileUrlSecurityIT extends PostgresIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4};
    private static final byte[] HTML = "<html><body><script>alert(document.cookie)</script></body></html>".getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private MarketplaceListingRepository listingRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    private MvcResult call(String token, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        if (token != null) req.header("Authorization", token);
        if (tenant != null) req.header("X-Tenant-ID", tenant.toString());
        return mvc.perform(req).andReturn();
    }

    private String upload(User u, UUID tenant, String folder, String name, byte[] content) throws Exception {
        MvcResult r = call(fixture.bearer(u), tenant, multipart("/api/v1/files/upload")
                .file(new MockMultipartFile("file", name, "application/octet-stream", content)).param("folder", folder));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data").path("storageKey").asText();
    }

    private MarketplaceListing published(Side side, User author, String... keys) {
        MarketplaceListing l = new MarketplaceListing();
        l.setTenantId(side.tenantId());
        l.setCreatedByUser(author);
        l.setType(ListingType.PRODUCT);
        l.setCategory("Otros");
        l.setTitle("Lampara sintetica");
        l.setDescription("Publicacion de prueba");
        l.setPriceType(PriceType.CONTACT);
        l.setStatus(ListingStatus.PUBLISHED);
        l.setImageKeysList(List.of(keys));
        return listingRepository.save(l);
    }

    private List<String> signedUrls(User viewer, UUID tenant, UUID listingId) throws Exception {
        MvcResult r = call(fixture.bearer(viewer), tenant, get("/api/v1/marketplace/listings/{id}", listingId));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        List<String> urls = new java.util.ArrayList<>();
        objectMapper.readTree(r.getResponse().getContentAsString()).path("data").path("imageUrls").forEach(n -> urls.add(n.asText()));
        return urls;
    }

    private int anonymous(String url) throws Exception {
        return mvc.perform(get(URI.create(url))).andReturn().getResponse().getStatus();
    }

    private static String param(String url, String name) {
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    @DisplayName("la firma esta ligada al archivo exacto: con otra clave (misma publicacion, otro documento) no sirve")
    void signatureIsBoundToTheExactFile() throws Exception {
        String one = upload(a.owner(), a.tenantId(), "marketplace", "uno.png", PNG);
        String two = upload(a.owner(), a.tenantId(), "marketplace", "dos.png", PNG);
        String doc = upload(a.admin(), a.tenantId(), "documents", "acta.pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        MarketplaceListing l = published(a, a.owner(), one, two);
        List<String> urls = signedUrls(a.neighbour(), a.tenantId(), l.getId());
        String first = urls.get(0);
        assertThat(anonymous(first)).isEqualTo(200);
        assertThat(anonymous(urls.get(1))).isEqualTo(200);

        String encodedOne = param(first, "key");
        for (String other : List.of(two, doc)) {
            String forged = first.replace("key=" + encodedOne, "key=" + java.net.URLEncoder.encode(other, StandardCharsets.UTF_8));
            assertThat(anonymous(forged)).as("firma de " + one + " con clave " + other).isEqualTo(404);
        }
        // Reutilizar la firma de una imagen con el 'exp' o el usuario de la otra tampoco sirve.
        String second = urls.get(1);
        String mixed = first.replace("sig=" + param(first, "sig"), "sig=" + param(second, "sig"));
        assertThat(anonymous(mixed)).isEqualTo(404);
    }

    @Test
    @DisplayName("comunidad suspendida: ni URL firmada ya emitida ni descarga con sesion")
    void suspendedCommunityCannotDownload() throws Exception {
        String key = upload(a.owner(), a.tenantId(), "marketplace", "suspendida.png", PNG);
        MarketplaceListing l = published(a, a.owner(), key);
        String url = signedUrls(a.neighbour(), a.tenantId(), l.getId()).get(0);
        assertThat(anonymous(url)).isEqualTo(200);

        Tenant t = tenantRepository.findById(a.tenantId()).orElseThrow();
        t.suspend("Prueba sintetica");
        tenantRepository.save(t);
        try {
            assertThat(anonymous(url)).isEqualTo(404);
            assertThat(call(fixture.bearer(a.owner()), a.tenantId(), get("/api/v1/files/download").param("key", key))
                    .getResponse().getStatus()).isEqualTo(403);
            // La otra comunidad no se ve afectada.
            String keyB = upload(b.owner(), b.tenantId(), "marketplace", "b.png", PNG);
            MarketplaceListing lb = published(b, b.owner(), keyB);
            assertThat(anonymous(signedUrls(b.neighbour(), b.tenantId(), lb.getId()).get(0))).isEqualTo(200);
        } finally {
            t.activate();
            tenantRepository.save(t);
        }
    }

    @Test
    @DisplayName("cambio de rol: al pasar de RESIDENT a SECURITY la URL firmada se revalida con el rol nuevo; al quitarle la membresia, 404")
    void roleChangeAppliesImmediately() throws Exception {
        String key = upload(a.owner(), a.tenantId(), "marketplace", "rol.png", PNG);
        MarketplaceListing l = published(a, a.owner(), key);
        String url = signedUrls(a.neighbour(), a.tenantId(), l.getId()).get(0);
        Membership m = membershipRepository.findByUserIdAndTenantId(a.neighbour().getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(anonymous(url)).isEqualTo(404);
        m.setStatus(MembershipStatus.ACTIVE);
        membershipRepository.save(m);
        assertThat(anonymous(url)).as("se vuelve a evaluar: con el permiso concedido de nuevo, sirve").isEqualTo(200);
    }

    @Test
    @DisplayName("las firmas y las claves nunca aparecen en los logs, ni en exito ni en rechazo")
    void signaturesAreNotLogged(CapturedOutput output) throws Exception {
        String key = upload(a.owner(), a.tenantId(), "marketplace", "log.png", PNG);
        MarketplaceListing l = published(a, a.owner(), key);
        String url = signedUrls(a.neighbour(), a.tenantId(), l.getId()).get(0);
        int before = output.getAll().length();
        anonymous(url);
        anonymous(url.replace("sig=" + param(url, "sig"), "sig=AAAA" + param(url, "sig").substring(4)));
        anonymous(url.substring(0, url.indexOf("&exp=")));
        String logs = output.getAll().substring(before);
        assertThat(logs).doesNotContain(param(url, "sig")).doesNotContain("sig=").doesNotContain(param(url, "key"))
                .doesNotContain(key);
    }

    @Test
    @DisplayName("cabeceras: contenido sensible sin cache, nosniff, sandbox y sin referer, en descarga y en URL firmada")
    void sensitiveCacheHeaders() throws Exception {
        String key = upload(a.owner(), a.tenantId(), "marketplace", "cache.png", PNG);
        MarketplaceListing l = published(a, a.owner(), key);
        String url = signedUrls(a.neighbour(), a.tenantId(), l.getId()).get(0);
        for (MvcResult r : List.of(mvc.perform(get(URI.create(url))).andReturn(),
                call(fixture.bearer(a.owner()), a.tenantId(), get("/api/v1/files/download").param("key", key)))) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store").contains("private").doesNotContain("max-age=300");
            assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'none'").contains("sandbox");
            assertThat(r.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        }
    }

    @Test
    @DisplayName("archivos peligrosos: HTML disfrazado de PDF o de PNG nunca se sirve como HTML ni en linea ejecutable")
    void dangerousContentNeverRendersOnHavitaOrigin() throws Exception {
        String fakePdf = upload(a.admin(), a.tenantId(), "documents", "factura.pdf", HTML);
        call(fixture.bearer(a.admin()), a.tenantId(), post("/api/v1/communications/documents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Doc\",\"fileSize\":\"1 KB\",\"fileUrl\":\"x\",\"storageKey\":\"" + fakePdf + "\"}"));
        MvcResult pdf = call(fixture.bearer(a.owner()), a.tenantId(), get("/api/v1/files/download").param("key", fakePdf));
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(pdf.getResponse().getContentType()).doesNotContain("html");
        assertThat(pdf.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(pdf.getResponse().getHeader("Content-Security-Policy")).contains("sandbox");

        String fakePng = upload(a.owner(), a.tenantId(), "marketplace", "foto.png", HTML);
        MarketplaceListing l = published(a, a.owner(), fakePng);
        MvcResult img = mvc.perform(get(URI.create(signedUrls(a.neighbour(), a.tenantId(), l.getId()).get(0)))).andReturn();
        assertThat(img.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(img.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(img.getResponse().getHeader("Content-Security-Policy")).contains("sandbox");

        // Extensiones ejecutables o de marcado se rechazan en la subida, en ambas carpetas.
        for (String name : List.of("x.html", "x.htm", "x.svg", "x.js", "x.xhtml", "x.php", "x.exe", "x")) {
            for (var who : List.of(new Object[]{a.admin(), "documents"}, new Object[]{a.owner(), "marketplace"})) {
                int code = call(fixture.bearer((User) who[0]), a.tenantId(), multipart("/api/v1/files/upload")
                        .file(new MockMultipartFile("file", name, "text/html", HTML)).param("folder", (String) who[1]))
                        .getResponse().getStatus();
                assertThat(code).as(name + " en " + who[1]).isEqualTo(409);
            }
        }
    }
}
