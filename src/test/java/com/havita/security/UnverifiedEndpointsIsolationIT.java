package com.havita.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.User;
import com.havita.modules.marketplace.domain.ListingStatus;
import com.havita.modules.marketplace.domain.ListingType;
import com.havita.modules.marketplace.domain.MarketplaceListing;
import com.havita.modules.marketplace.domain.PriceType;
import com.havita.modules.marketplace.infrastructure.MarketplaceListingRepository;
import com.havita.modules.subscription.domain.SaaSInvoice;
import com.havita.modules.subscription.domain.SaaSInvoiceStatus;
import com.havita.modules.subscription.infrastructure.SaaSInvoiceRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import com.havita.support.UnitDataFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Auditoria de los endpoints UNVERIFIED (2026-11-06): autorizacion EFECTIVA sobre el recurso concreto. Para cada
 * recurso real de la comunidad A, la administracion, la porteria y los residentes de B (y, donde aplica, otro residente
 * de A) intentan leerlo o modificarlo con su id: la respuesta no es 2xx y la fila completa (huella JSON de todas sus
 * columnas) queda identica. Las llamadas usan rutas literales para que la prueba de cobertura las reconozca.
 */
class UnverifiedEndpointsIsolationIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UnitDataFixture unitData;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MarketplaceListingRepository listingRepository;
    @Autowired private SaaSInvoiceRepository invoiceRepository;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    // ── utilidades ────────────────────────────────────────────────────────────

    private MvcResult call(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(u)).header("X-Tenant-ID", side.tenantId().toString())).andReturn();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UUID created(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = call(u, side, req);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 299);
        JsonNode data = objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
        return UUID.fromString(data.path("id").asText());
    }

    /** Huella de todas las columnas de la fila (o "ausente"). */
    private String fingerprint(String table, UUID id) {
        List<String> rows = jdbc.queryForList("select md5(to_jsonb(t)::text) from " + table + " t where t.id = ?", String.class, id);
        return rows.isEmpty() ? "ausente" : rows.get(0);
    }

    /** Cada intento devuelve un estado no 2xx y no cambia la fila. */
    private void denied(String table, UUID id, User attacker, Side side, MockHttpServletRequestBuilder... attempts) throws Exception {
        String before = fingerprint(table, id);
        assertThat(before).isNotEqualTo("ausente");
        for (MockHttpServletRequestBuilder req : attempts) {
            MvcResult r = call(attacker, side, req);
            String target = r.getRequest().getMethod() + " " + r.getRequest().getRequestURI();
            assertThat(r.getResponse().getStatus()).as(attacker.getEmail() + " " + target).isNotIn(200, 201, 202, 204);
            assertThat(fingerprint(table, id)).as("fila intacta tras " + target).isEqualTo(before);
        }
    }

    /** Lecturas de coleccion: pueden responder 200, pero sin datos de la otra comunidad. */
    private void noLeak(User attacker, Side side, String forbiddenId, MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = call(attacker, side, req);
        if (r.getResponse().getStatus() >= 200 && r.getResponse().getStatus() < 300) {
            assertThat(r.getResponse().getContentAsString()).doesNotContain(forbiddenId);
        }
    }

    // ── CRITICA: facturacion, unidades y estructuras ──────────────────────────

    @Test
    @DisplayName("CRITICA · factura SaaS de A: la administracion de B no puede pagarla")
    void saasInvoicePayment() throws Exception {
        SaaSInvoice inv = SaaSInvoice.builder().invoiceNumber("INV-" + UUID.randomUUID()).period("2026-11")
                .amountCop(new BigDecimal("1000")).taxCop(BigDecimal.ZERO).totalCop(new BigDecimal("1000"))
                .issuedAt(OffsetDateTime.now()).dueAt(OffsetDateTime.now().plusDays(10)).status(SaaSInvoiceStatus.PENDING).build();
        inv.setTenantId(a.tenantId());
        inv = invoiceRepository.save(inv);
        denied("saas_invoices", inv.getId(), b.admin(), b, post("/api/v1/saas/invoices/{invoiceId}/pay", inv.getId()));
        denied("saas_invoices", inv.getId(), a.owner(), a, post("/api/v1/saas/invoices/{invoiceId}/pay", inv.getId()));
    }

    @Test
    @DisplayName("CRITICA · unidades, historial, estructuras y zonas de A: B no las borra, no lee su historial ni las modifica")
    void propertiesStructuresAndZones() throws Exception {
        UUID structure = created(a.admin(), a, json(post("/api/v1/properties/structures"), "{\"name\":\"Torre Sintetica " + UUID.randomUUID().toString().substring(0, 6) + "\",\"type\":\"Torre\"}"));
        String structureName = jdbc.queryForObject("select name from structures where id = ?", String.class, structure);
        UnitDataFixture.UnitData da = unitData.seed(a);

        denied("properties", a.vacant().getId(), b.admin(), b,
                delete("/api/v1/properties/{id}", a.vacant().getId()),
                get("/api/v1/properties/{propertyId}/history", a.home().getId()));
        denied("structures", structure, b.admin(), b, delete("/api/v1/properties/structures/by-name/{name}", structureName));
        denied("common_zones", da.zone().getId(), b.admin(), b,
                json(put("/api/v1/properties/zones/{id}", da.zone().getId()), "{\"name\":\"Renombrada por B\"}"));
        noLeak(b.admin(), b, a.home().getId().toString(), get("/api/v1/properties/by-structure/{structureId}", structure));
        // Roles no administrativos de A tampoco.
        denied("properties", a.vacant().getId(), a.guard(), a, delete("/api/v1/properties/{id}", a.vacant().getId()));
    }

    // ── ALTA: paquetes, visitantes, reservas, panico ──────────────────────────

    @Test
    @DisplayName("ALTA · paquetes de A: B (administracion o porteria) no los entrega, notifica ni borra")
    void packages() throws Exception {
        UnitDataFixture.UnitData da = unitData.seed(a);
        UUID pkg = da.neighbourPackage().getId();
        for (User u : new User[]{b.admin(), b.guard()}) {
            denied("packages", pkg, u, b,
                    delete("/api/v1/access/packages/{id}", pkg),
                    post("/api/v1/access/packages/{id}/deliver", pkg),
                    post("/api/v1/access/packages/{id}/notify", pkg));
        }
    }

    @Test
    @DisplayName("ALTA · visitantes de A: B no los lee, autoriza, registra entrada/salida ni borra; ni los lista por unidad")
    void visitors() throws Exception {
        UUID visitor = created(a.guard(), a, json(post("/api/v1/access/visitors"),
                "{\"propertyId\":\"%s\",\"name\":\"Visitante Sintetico\",\"documentId\":\"V%s\"}".formatted(a.home().getId(), UUID.randomUUID().toString().substring(0, 6))));
        for (User u : new User[]{b.admin(), b.guard()}) {
            denied("visitors", visitor, u, b,
                    get("/api/v1/access/visitors/{id}", visitor),
                    post("/api/v1/access/visitors/{id}/authorize", visitor),
                    post("/api/v1/access/visitors/{id}/entry", visitor),
                    post("/api/v1/access/visitors/{id}/exit", visitor),
                    delete("/api/v1/access/visitors/{id}", visitor));
            noLeak(u, b, visitor.toString(), get("/api/v1/access/visitors/by-property/{propertyId}", a.home().getId()));
        }
    }

    @Test
    @DisplayName("ALTA · reservas de A: la administracion de B no las confirma ni rechaza")
    void reservations() throws Exception {
        UnitDataFixture.UnitData da = unitData.seed(a);
        UUID res = da.ownReservation().getId();
        denied("reservations", res, b.admin(), b,
                post("/api/v1/reservations/{id}/confirm", res),
                post("/api/v1/reservations/{id}/reject", res));
        denied("reservations", res, a.owner(), a,
                post("/api/v1/reservations/{id}/confirm", res),
                post("/api/v1/reservations/{id}/reject", res));
    }

    @Test
    @DisplayName("ALTA · alertas de panico de A: B no las lee, reconoce ni resuelve")
    void panicAlerts() throws Exception {
        UUID alert = created(a.owner(), a, json(post("/api/v1/realtime/panic"), "{\"alertType\":\"MEDICAL\",\"notes\":\"prueba sintetica\"}"));
        for (User u : new User[]{b.admin(), b.guard()}) {
            denied("security_panic_alerts", alert, u, b,
                    get("/api/v1/realtime/panic/{id}", alert),
                    post("/api/v1/realtime/panic/{id}/acknowledge", alert),
                    post("/api/v1/realtime/panic/{id}/resolve", alert));
        }
    }

    // ── ALTA: comunicaciones, documentos, asambleas ───────────────────────────

    @Test
    @DisplayName("ALTA · comunicados y documentos de A: B no los lee, modifica, publica, archiva, descarga ni borra")
    void communicationsAndDocuments() throws Exception {
        UUID comm = created(a.admin(), a, json(post("/api/v1/communications"), "{\"title\":\"Borrador A\",\"content\":\"Contenido\",\"status\":\"DRAFT\"}"));
        UUID doc = created(a.admin(), a, json(post("/api/v1/communications/documents"), "{\"title\":\"Doc A\",\"fileSize\":\"1 KB\",\"fileUrl\":\"https://ejemplo.invalid/a.pdf\"}"));
        denied("communications", comm, b.admin(), b,
                json(put("/api/v1/communications/{id}", comm), "{\"title\":\"Cambiado por B\",\"content\":\"x\"}"),
                post("/api/v1/communications/{id}/publish", comm),
                post("/api/v1/communications/{id}/archive", comm),
                delete("/api/v1/communications/{id}", comm));
        for (User u : new User[]{b.admin(), b.owner(), b.guard()}) {
            denied("community_documents", doc, u, b,
                    get("/api/v1/communications/documents/{id}", doc),
                    post("/api/v1/communications/documents/{id}/download", doc));
        }
        denied("community_documents", doc, b.admin(), b,
                json(put("/api/v1/communications/documents/{id}", doc), "{\"title\":\"Cambiado por B\"}"),
                delete("/api/v1/communications/documents/{id}", doc));
        // Roles de A sin permiso de escritura.
        denied("community_documents", doc, a.owner(), a, delete("/api/v1/communications/documents/{id}", doc));
    }

    @Test
    @DisplayName("ALTA · asambleas y votaciones de A: B no las lee, inicia, finaliza, registra asistentes, abre ni cierra votaciones")
    void assembliesAndPolls() throws Exception {
        UUID assembly = created(a.admin(), a, json(post("/api/v1/assemblies"),
                "{\"title\":\"Asamblea A\",\"assemblyDate\":\"%s\",\"startTime\":\"18:00\"}".formatted(LocalDate.now().plusDays(7))));
        UUID poll = created(a.admin(), a, json(post("/api/v1/assemblies/polls"),
                "{\"assemblyId\":\"%s\",\"question\":\"Pregunta\",\"options\":[\"Si\",\"No\"]}".formatted(assembly)));
        denied("assemblies", assembly, b.admin(), b,
                get("/api/v1/assemblies/{id}", assembly),
                post("/api/v1/assemblies/{id}/start", assembly),
                post("/api/v1/assemblies/{id}/finish", assembly),
                json(post("/api/v1/assemblies/{id}/attendees", assembly), "{\"propertyId\":\"%s\"}".formatted(a.home().getId())),
                get("/api/v1/assemblies/{id}/voting-rights", assembly));
        denied("polls", poll, b.admin(), b,
                post("/api/v1/assemblies/polls/{pollId}/open", poll),
                post("/api/v1/assemblies/polls/{pollId}/close", poll));
        denied("assemblies", assembly, b.owner(), b, get("/api/v1/assemblies/{id}", assembly));
        denied("polls", poll, a.owner(), a, post("/api/v1/assemblies/polls/{pollId}/open", poll));
    }

    // ── ALTA: personal y turnos ───────────────────────────────────────────────

    @Test
    @DisplayName("ALTA · personal y turnos de A: B no lee la ficha, ni asistencia o turnos, ni marca salida, cancela, reasigna o borra turnos")
    void staffAndShifts() throws Exception {
        UUID staff = created(a.admin(), a, json(post("/api/v1/staff"),
                "{\"firstName\":\"Personal\",\"lastName\":\"A\",\"documentNumber\":\"S%s\",\"position\":\"Aseo\"}".formatted(UUID.randomUUID().toString().substring(0, 8))));
        UUID otherStaffB = created(b.admin(), b, json(post("/api/v1/staff"),
                "{\"firstName\":\"Personal\",\"lastName\":\"B\",\"documentNumber\":\"S%s\",\"position\":\"Aseo\"}".formatted(UUID.randomUUID().toString().substring(0, 8))));
        UUID shift = created(a.admin(), a, json(post("/api/v1/staff/shifts"),
                "{\"staffMemberId\":\"%s\",\"shiftDate\":\"%s\",\"shiftName\":\"Manana\",\"startTime\":\"08:00\",\"endTime\":\"16:00\"}".formatted(staff, LocalDate.now().plusDays(1))));
        denied("staff_members", staff, b.admin(), b,
                get("/api/v1/staff/{id}", staff),
                post("/api/v1/staff/attendance/{staffMemberId}/check-out", staff));
        noLeak(b.admin(), b, staff.toString(), get("/api/v1/staff/attendance/by-staff/{staffMemberId}", staff));
        noLeak(b.admin(), b, shift.toString(), get("/api/v1/staff/shifts/by-staff/{staffMemberId}", staff));
        denied("shifts", shift, b.admin(), b,
                patch("/api/v1/staff/shifts/{id}/cancel", shift),
                patch("/api/v1/staff/shifts/{id}/reassign", shift).param("newStaffMemberId", otherStaffB.toString()),
                delete("/api/v1/staff/shifts/{id}", shift));
        // Ni siquiera la administracion de A puede reasignar a personal de otra comunidad.
        denied("shifts", shift, a.admin(), a,
                patch("/api/v1/staff/shifts/{id}/reassign", shift).param("newStaffMemberId", otherStaffB.toString()));
    }

    // ── ALTA: marketplace (administracion y residentes) ───────────────────────

    @Test
    @DisplayName("ALTA · marketplace: B no modera publicaciones de A, y otro residente de A no edita, oculta ni borra las ajenas")
    void marketplace() throws Exception {
        MarketplaceListing l = new MarketplaceListing();
        l.setTenantId(a.tenantId());
        l.setCreatedByUser(a.owner());
        l.setType(ListingType.PRODUCT);
        l.setCategory("Otros");
        l.setTitle("Silla sintetica");
        l.setDescription("Publicacion de prueba");
        l.setPriceType(PriceType.CONTACT);
        l.setStatus(ListingStatus.PENDING_APPROVAL);
        l = listingRepository.save(l);
        UUID id = l.getId();
        denied("marketplace_listings", id, b.admin(), b,
                post("/api/v1/admin/marketplace/listings/{id}/approve", id),
                json(post("/api/v1/admin/marketplace/listings/{id}/reject", id), "{\"reason\":\"B\"}"),
                json(post("/api/v1/admin/marketplace/listings/{id}/hide", id), "{\"reason\":\"B\"}"),
                post("/api/v1/admin/marketplace/listings/{id}/unhide", id));
        denied("marketplace_listings", id, a.neighbour(), a,
                json(patch("/api/v1/marketplace/listings/{id}", id), "{\"title\":\"Cambiado\",\"contactPhone\":\"3000000000\"}"),
                delete("/api/v1/marketplace/listings/{id}", id),
                post("/api/v1/marketplace/listings/{id}/hide", id),
                post("/api/v1/marketplace/listings/{id}/unhide", id),
                delete("/api/v1/marketplace/listings/{id}/permanent", id));
        denied("marketplace_listings", id, b.owner(), b,
                get("/api/v1/marketplace/listings/{id}", id),
                json(post("/api/v1/marketplace/listings/{id}/report", id), "{\"reason\":\"SPAM\",\"description\":\"x\"}"));
    }

    // ── ALTA: vehiculos, mascotas y visitantes del residente ──────────────────

    @Test
    @DisplayName("ALTA · vehiculos, mascotas y visitantes del propietario de A: ni otro residente de A, ni B, los modifican o borran")
    void residentOwnedResources() throws Exception {
        UUID vehicle = created(a.owner(), a, json(post("/api/v1/resident/me/vehicles"),
                "{\"plate\":\"TST%s\",\"type\":\"Automóvil\",\"brand\":\"Marca\",\"model\":\"Modelo\",\"color\":\"Gris\"}".formatted(UUID.randomUUID().toString().substring(0, 3).toUpperCase())));
        UUID pet = created(a.owner(), a, json(post("/api/v1/resident/me/pets"),
                "{\"name\":\"Mascota Sintetica\",\"type\":\"Perro\",\"breed\":\"Criollo\",\"color\":\"Cafe\",\"isDangerousBreed\":false}"));
        UUID visitor = created(a.owner(), a, json(post("/api/v1/resident/me/visitors"),
                "{\"name\":\"Visita Sintetica\",\"documentId\":\"R%s\"}".formatted(UUID.randomUUID().toString().substring(0, 6))));
        for (Object[] who : new Object[][]{{a.neighbour(), a}, {b.owner(), b}}) {
            User u = (User) who[0];
            Side s = (Side) who[1];
            denied("vehicles", vehicle, u, s, json(put("/api/v1/resident/me/vehicles/{vehicleId}", vehicle), "{\"plate\":\"HACK1\",\"type\":\"Moto\"}"));
            denied("pets", pet, u, s, json(put("/api/v1/resident/me/pets/{petId}", pet), "{\"name\":\"Cambiada\",\"type\":\"Gato\"}"));
            denied("visitors", visitor, u, s,
                    json(put("/api/v1/resident/me/visitors/{visitorId}", visitor), "{\"name\":\"Cambiada\",\"documentId\":\"X1\"}"),
                    delete("/api/v1/resident/me/visitors/{visitorId}", visitor));
        }
        denied("vehicles", vehicle, b.admin(), b, delete("/api/v1/residents/vehicles/{id}", vehicle));
        denied("pets", pet, b.admin(), b, delete("/api/v1/residents/pets/{id}", pet));
    }

    // ── ALTA: citofonia ───────────────────────────────────────────────────────

    @Autowired private com.havita.modules.intercom.infrastructure.IntercomCallRepository intercomCallRepository;
    @Autowired private com.havita.modules.marketplace.infrastructure.MarketplaceReportRepository reportRepository;

    @Test
    @DisplayName("ALTA · citofonia de A: B (porteria, administracion, residentes) no lee, acepta, rechaza, conecta, termina ni cancela la llamada; ni ve residentes de sus unidades")
    void intercomCalls() throws Exception {
        // Fila sintetica (sin llamar a Cloudflare): la busqueda por comunidad rechaza antes de cualquier llamada externa.
        var call = com.havita.modules.intercom.domain.IntercomCall.builder()
                .property(a.home()).callerUser(a.guard()).callerName("Porteria A").recipientResident(a.ownerResident())
                .recipientUser(a.owner()).recipientName("Propietario A").propertyUnit(a.home().getNumber())
                .realtimeMeetingId("sintetico-" + UUID.randomUUID()).status(com.havita.modules.intercom.domain.IntercomCallStatus.RINGING)
                .ringingAt(OffsetDateTime.now()).build();
        call.setTenantId(a.tenantId());
        UUID id = intercomCallRepository.save(call).getId();
        for (User u : new User[]{b.guard(), b.admin()}) {
            denied("intercom_calls", id, u, b,
                    get("/api/v1/intercom/calls/{id}", id),
                    post("/api/v1/intercom/calls/{id}/cancel", id),
                    post("/api/v1/intercom/calls/{id}/connected", id),
                    post("/api/v1/intercom/calls/{id}/end", id));
            noLeak(u, b, a.ownerResident().getId().toString(), get("/api/v1/intercom/units/{propertyId}/residents", a.home().getId()));
            noLeak(u, b, a.ownerResident().getId().toString(), get("/api/v1/intercom/directory/units/{propertyId}/residents", a.home().getId()));
        }
        denied("intercom_calls", id, b.owner(), b,
                post("/api/v1/intercom/calls/{id}/accept", id),
                post("/api/v1/intercom/calls/{id}/reject", id));
        // Otro residente de A (no es el destinatario) tampoco.
        denied("intercom_calls", id, a.neighbour(), a,
                post("/api/v1/intercom/calls/{id}/accept", id),
                post("/api/v1/intercom/calls/{id}/reject", id));
    }

    @Test
    @DisplayName("ALTA · reportes del marketplace de A: la administracion de B no los resuelve")
    void marketplaceReports() throws Exception {
        MarketplaceListing l = new MarketplaceListing();
        l.setTenantId(a.tenantId());
        l.setCreatedByUser(a.owner());
        l.setType(ListingType.SERVICE);
        l.setCategory("Otros");
        l.setTitle("Servicio sintetico");
        l.setDescription("Publicacion de prueba");
        l.setPriceType(PriceType.CONTACT);
        l.setStatus(ListingStatus.PUBLISHED);
        l = listingRepository.save(l);
        var report = com.havita.modules.marketplace.domain.MarketplaceReport.builder().tenantId(a.tenantId()).listing(l)
                .reportedByUserId(a.neighbour().getId()).reason(com.havita.modules.marketplace.domain.ReportReason.SPAM)
                .description("Reporte sintetico").build();
        UUID id = reportRepository.save(report).getId();
        denied("marketplace_reports", id, b.admin(), b,
                json(post("/api/v1/admin/marketplace/reports/{id}/resolve", id), "{\"action\":\"RESOLVE\"}"));
        denied("marketplace_reports", id, a.owner(), a,
                json(post("/api/v1/admin/marketplace/reports/{id}/resolve", id), "{\"action\":\"DISMISS\"}"));
    }
}
