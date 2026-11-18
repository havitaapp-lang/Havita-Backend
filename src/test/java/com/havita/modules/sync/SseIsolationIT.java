package com.havita.modules.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SseService;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import com.havita.support.UnitDataFixture;
import com.havita.support.UnitDataFixture.UnitData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-5: entrega de eventos SSE de paqueteria y reservas. Se abre el stream real (ticket + GET /sync/stream),
 * se provocan los eventos con la API y se inspecciona lo que recibio cada conexion.
 */
class SseIsolationIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UnitDataFixture unitData;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SseService sseService;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private UserRepository userRepository;

    private Side a;
    private Side b;
    private UnitData da;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        da = unitData.seed(a);
    }

    @AfterEach
    void restoreRevalidation() {
        setRevalidation(Duration.ofSeconds(60));
    }

    /** Ajusta el plazo de revalidacion; sin efecto si la version de SseService no lo tiene (ejecucion en rojo). */
    private void setRevalidation(Duration value) {
        if (org.springframework.util.ReflectionUtils.findField(SseService.class, "revalidateAfter") != null) {
            ReflectionTestUtils.setField(sseService, "revalidateAfter", value);
        }
    }

    /** Conexiones abiertas de la comunidad A (en el codigo anterior a SEC-5 se lee el mapa interno). */
    @SuppressWarnings("unchecked")
    private int openConnections() {
        if (org.springframework.util.ReflectionUtils.findMethod(SseService.class, "subscriberCount", UUID.class) != null) {
            return (int) ReflectionTestUtils.invokeMethod(sseService, "subscriberCount", a.tenantId());
        }
        var emitters = (java.util.Map<UUID, java.util.List<?>>) ReflectionTestUtils.getField(sseService, "tenantEmitters");
        return emitters.getOrDefault(a.tenantId(), java.util.List.of()).size();
    }

    private String ticket(User user, Side side) throws Exception {
        String body = mvc.perform(post("/api/v1/sync/stream-ticket")
                        .header("Authorization", fixture.bearer(user))
                        .header("X-Tenant-ID", side.tenantId().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("ticket").asText();
    }

    /** Conexion SSE abierta; su contenido acumula los eventos recibidos. */
    private MvcResult open(User user, Side side) throws Exception {
        return mvc.perform(get("/api/v1/sync/stream").param("ticket", ticket(user, side)))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static String received(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    private void receivePackage(UUID propertyId, String recipient) throws Exception {
        mvc.perform(post("/api/v1/access/packages")
                        .header("Authorization", fixture.bearer(a.guard()))
                        .header("X-Tenant-ID", a.tenantId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyId\":\"%s\",\"recipientName\":\"%s\",\"company\":\"Servientrega\"}"
                                .formatted(propertyId, recipient)))
                .andExpect(status().isCreated());
    }

    private void createReservation(User resident, UUID propertyId, int hour) throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .header("Authorization", fixture.bearer(resident))
                        .header("X-Tenant-ID", a.tenantId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commonZoneId":"%s","propertyId":"%s","reservationDate":"%s",
                                 "startTime":"%02d:00:00","endTime":"%02d:00:00","guestsCount":2,
                                 "observations":"Cumpleanos privado"}
                                """.formatted(da.zone().getId(), propertyId, da.reservationDay(), hour, hour + 1)))
                .andExpect(status().isCreated());
    }

    // ── Aislamiento entre unidades y comunidades ───────────────────────────────

    @Test
    @DisplayName("Paquete de la unidad del vecino: lo reciben el vecino y porteria; no el propietario ni otra comunidad")
    void packageEventsAreUnitScoped() throws Exception {
        MvcResult owner = open(a.owner(), a);
        MvcResult neighbour = open(a.neighbour(), a);
        MvcResult guard = open(a.guard(), a);
        MvcResult otherTenantAdmin = open(b.admin(), b);

        receivePackage(a.neighbourHome().getId(), "Paquete Del Vecino");

        assertThat(received(neighbour)).contains("PACKAGE_CREATED").contains("Paquete Del Vecino");
        assertThat(received(guard)).contains("PACKAGE_CREATED");
        assertThat(received(owner)).contains("CONNECTED").doesNotContain("PACKAGE_CREATED").doesNotContain("Paquete Del Vecino");
        assertThat(received(otherTenantAdmin)).doesNotContain("PACKAGE_CREATED");
    }

    @Test
    @DisplayName("Paquete de su propia unidad: el propietario lo recibe en tiempo real")
    void ownPackageIsDelivered() throws Exception {
        MvcResult owner = open(a.owner(), a);
        receivePackage(a.home().getId(), "Paquete Propio");
        assertThat(received(owner)).contains("PACKAGE_CREATED").contains("Paquete Propio");
    }

    @Test
    @DisplayName("Reserva del vecino: la reciben el vecino y la administracion; no otros residentes")
    void reservationEventsAreScopedToHolder() throws Exception {
        MvcResult owner = open(a.owner(), a);
        MvcResult neighbour = open(a.neighbour(), a);
        MvcResult admin = open(a.admin(), a);

        createReservation(a.neighbour(), a.neighbourHome().getId(), 18);

        assertThat(received(neighbour)).contains("RESERVATION_CREATED");
        assertThat(received(admin)).contains("RESERVATION_CREATED").contains("Cumpleanos privado");
        assertThat(received(owner)).doesNotContain("RESERVATION_CREATED").doesNotContain("Cumpleanos privado");
    }

    @Test
    @DisplayName("El evento enviado conserva su formato (type, tenantId, payload) y no expone la audiencia")
    void wireFormatIsUnchanged() throws Exception {
        MvcResult guard = open(a.guard(), a);
        receivePackage(a.home().getId(), "Formato");
        String data = received(guard).lines()
                .filter(l -> l.startsWith("data:") && l.contains("PACKAGE_CREATED"))
                .findFirst().orElseThrow().substring("data:".length());
        var json = objectMapper.readTree(data);
        assertThat(json.get("type").asText()).isEqualTo("PACKAGE_CREATED");
        assertThat(json.get("tenantId").asText()).isEqualTo(a.tenantId().toString());
        assertThat(json.has("payload")).isTrue();
        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.has("audience")).isFalse();
    }

    // ── Revocacion con la conexion abierta ─────────────────────────────────────

    @Test
    @DisplayName("Membresia revocada con la conexion abierta: al revalidar se cierra y deja de recibir eventos")
    void revokedMembershipClosesOpenConnection() throws Exception {
        MvcResult owner = open(a.owner(), a);
        int before = openConnections();

        var membership = membershipRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId()).orElseThrow();
        membership.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(membership);
        setRevalidation(Duration.ZERO);

        receivePackage(a.home().getId(), "Tras La Revocacion");

        assertThat(received(owner)).doesNotContain("Tras La Revocacion");
        assertThat(openConnections()).isEqualTo(before - 1);
    }

    @Test
    @DisplayName("Relacion con la unidad revocada con la conexion abierta: deja de recibir los eventos de esa unidad")
    void revokedRelationStopsUnitEvents() throws Exception {
        MvcResult owner = open(a.owner(), a);

        var rel = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        rel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(rel);
        setRevalidation(Duration.ZERO);

        receivePackage(a.home().getId(), "Unidad Revocada");
        assertThat(received(owner)).doesNotContain("Unidad Revocada");
    }

    @Test
    @DisplayName("Revocacion por la API: el afectado recibe el aviso de revocacion y luego deja de recibir su unidad")
    void apiRevocationInvalidatesWithoutWaitingForExpiry() throws Exception {
        // Familiar con cuenta en la unidad del propietario; el propietario le retira el acceso desde Mi Hogar.
        User relative = userRepository.save(User.builder()
                .email("familiar-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}not-used").fullName("Familiar con cuenta").phone("3000000000").build());
        membershipRepository.save(Membership.builder()
                .user(relative).tenantId(a.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        Resident relation = Resident.builder()
                .property(a.home()).user(relative).name("Familiar con cuenta").type("Residente")
                .email(relative.getEmail()).status(ResidentStatus.ACTIVE).build();
        relation.setTenantId(a.tenantId());
        relation = residentRepository.save(relation);

        MvcResult relativeStream = open(relative, a);
        receivePackage(a.home().getId(), "Antes De Revocar");
        assertThat(received(relativeStream)).contains("Antes De Revocar");

        mvc.perform(delete("/api/v1/resident/me/people/{id}", relation.getId())
                        .header("Authorization", fixture.bearer(a.owner()))
                        .header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().isOk());
        // Plazo de revalidacion por defecto (60 s): el cambio lo detecta el propio evento de revocacion.
        receivePackage(a.home().getId(), "Despues De Revocar");

        assertThat(received(relativeStream)).contains("RESIDENT_ACCESS_REVOKED").doesNotContain("Despues De Revocar");
    }

    // ── Establecimiento de la conexion ─────────────────────────────────────────

    @Test
    @DisplayName("Ticket: con la membresia revocada en BD (JWT aun vigente) no se emite")
    void ticketRequiresActiveMembership() throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        String token = fixture.bearer(a.owner());
        var membership = membershipRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId()).orElseThrow();
        membership.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(membership);

        mvc.perform(post("/api/v1/sync/stream-ticket")
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @Test
    @DisplayName("Ticket con X-Tenant-ID de otra comunidad: la peticion no se autentica")
    void forgedTenantHeaderGetsNoTicket() throws Exception {
        int s = mvc.perform(post("/api/v1/sync/stream-ticket")
                        .header("Authorization", fixture.bearer(a.owner()))
                        .header("X-Tenant-ID", b.tenantId().toString()))
                .andReturn().getResponse().getStatus();
        assertThat(s).isIn(401, 403);
    }

    @Test
    @DisplayName("Stream con ticket inventado o reutilizado: 401 sin cuerpo, sin abrir conexion")
    void invalidTicketsAreRejectedWithoutDetails() throws Exception {
        var invented = mvc.perform(get("/api/v1/sync/stream").param("ticket", UUID.randomUUID() + "-" + UUID.randomUUID()))
                .andReturn().getResponse();
        assertThat(invented.getStatus()).isEqualTo(401);
        assertThat(invented.getContentAsString()).isEmpty();

        String t = ticket(a.owner(), a);
        mvc.perform(get("/api/v1/sync/stream").param("ticket", t)).andExpect(request().asyncStarted());
        var reused = mvc.perform(get("/api/v1/sync/stream").param("ticket", t)).andReturn().getResponse();
        assertThat(reused.getStatus()).isEqualTo(401);
        assertThat(reused.getContentAsString()).isEmpty();
    }
}
