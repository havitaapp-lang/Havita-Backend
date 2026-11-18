package com.havita.modules.intercom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.intercom.api.dto.IntercomCallResponse;
import com.havita.modules.intercom.application.IntercomService;
import com.havita.modules.intercom.domain.IntercomCall;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import com.havita.security.TenantContextHolder;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P8-A9 — ciclo de vida de la citofonia contra PostgreSQL aislado y un servidor RealtimeKit SIMULADO en 127.0.0.1.
 * Ningun token ni identificador es real; no se contacta con Cloudflare.
 */
@ExtendWith(OutputCaptureExtension.class)
class IntercomLifecycleIT extends PostgresIntegrationTest {

    static final String API_TOKEN = "synthetic-api-token-p8a-not-real";
    static final String PARTICIPANT_TOKEN_PREFIX = "rtk-participant-token-synthetic-";
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    static final AtomicBoolean FAIL_MEETING = new AtomicBoolean(false);
    static final AtomicInteger PARTICIPANTS = new AtomicInteger();
    static final AtomicBoolean AUTH_HEADER_OK = new AtomicBoolean(true);
    static final HttpServer STUB;

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // P8-A3: se registra el cuerpo de PATCH (estado de la reunion) y si el alta pide vigencia del token.
                String detail = "PATCH".equals(exchange.getRequestMethod()) ? " " + reqBody
                        : (path.endsWith("/participants") && reqBody.contains("\"expires_on\"") ? " [expires_on]" : "");
                REQUESTS.add(exchange.getRequestMethod() + " " + path + detail);
                if (!("Bearer " + API_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    AUTH_HEADER_OK.set(false);
                }
                String body;
                int code = 200;
                if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/meetings")) {
                    if (FAIL_MEETING.get()) {
                        code = 500;
                        body = "{\"success\":false}";
                    } else {
                        body = "{\"success\":true,\"data\":{\"id\":\"meeting-" + UUID.randomUUID() + "\"}}";
                    }
                } else if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/participants")) {
                    int n = PARTICIPANTS.incrementAndGet();
                    body = "{\"success\":true,\"data\":{\"id\":\"participant-" + n + "\",\"token\":\"" + PARTICIPANT_TOKEN_PREFIX + n + "\"}}";
                } else if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/participants")) {
                    body = "{\"success\":true,\"data\":[]}";
                } else {
                    body = "{\"success\":true}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            STUB.setExecutor(Executors.newFixedThreadPool(4));
            STUB.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void realtimeKit(DynamicPropertyRegistry registry) {
        registry.add("app.realtimekit.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("app.cloudflare.api-token", () -> API_TOKEN);
        registry.add("app.cloudflare.account-id", () -> "0123456789abcdef0123456789abcdef");
        registry.add("app.realtimekit.app-id", () -> "00000000-0000-4000-8000-000000000000");
        // Este contexto comparte PostgreSQL con otros: sus tareas programadas no deben procesar llamadas ajenas
        // (las pruebas llaman a las tareas explicitamente).
        registry.add("havita.intercom.scheduler.enabled", () -> "false");
        registry.add("havita.intercom.reconciliation.enabled", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IntercomCallRepository calls;
    @Autowired private IntercomService intercomService;
    @Autowired private JdbcTemplate jdbc;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        FAIL_MEETING.set(false);
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    // Acceso para subclases (p. ej. IntercomEarlyTokenIT) sin exponer el fixture.
    protected UUID neighbourHomeId() { return a.neighbourHome().getId(); }
    protected UUID neighbourResidentId() { return a.neighbourResident().getId(); }
    protected User guard() { return a.guard(); }
    protected User neighbour() { return a.neighbour(); }
    protected User owner() { return a.owner(); }
    protected ResultActions mvcAs(User user, MockHttpServletRequestBuilder req) throws Exception { return as(user, a, req); }
    /** La misma petición desde la comunidad B (residente de B, cabecera de B). */
    protected ResultActions mvcOtherTenant(MockHttpServletRequestBuilder req) throws Exception { return as(b.neighbour(), b, req); }

    private JsonNode data(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString()).path("data");
    }

    private UUID initiate(User caller, Side side) throws Exception {
        ResultActions r = as(caller, side, post("/api/v1/intercom/calls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"recipientResidentId\":\"%s\"}"
                        .formatted(side.neighbourHome().getId(), side.neighbourResident().getId())))
                .andExpect(status().isCreated());
        JsonNode d = data(r);
        assertThat(d.path("status").asText()).isEqualTo("RINGING");
        assertThat(d.path("participantToken").asText()).startsWith(PARTICIPANT_TOKEN_PREFIX);
        return UUID.fromString(d.path("callId").asText());
    }

    private IntercomCall load(UUID id) {
        return calls.findById(id).orElseThrow();
    }

    private void awaitRequest(String suffix) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (REQUESTS.stream().anyMatch(r -> r.endsWith(suffix) || r.contains(suffix))) return;
            Thread.sleep(50);
        }
        throw new AssertionError("no se llamo al simulador: " + suffix);
    }

    @Test
    @DisplayName("Ciclo completo: timbre, aceptar, conectar ambos, colgar; duracion desde connected_at y cierre de la reunion")
    void fullLifecycle(CapturedOutput output) throws Exception {
        int before = REQUESTS.size();
        UUID id = initiate(a.guard(), a);
        // Solo las altas (reunion + 2 participantes); se ignoran limpiezas asincronas de pruebas anteriores y PATCH.
        List<String> created = List.copyOf(REQUESTS.subList(before, REQUESTS.size())).stream()
                .filter(r -> r.startsWith("POST ") && (r.endsWith("/meetings") || r.contains("/participants")))
                .toList();
        assertThat(created).hasSize(3);
        assertThat(created.get(0)).endsWith("/meetings");
        // P8-A3: cada alta de participante pide una vigencia limitada del token (token.expires_on).
        assertThat(created.subList(1, 3)).allMatch(r -> r.contains("/participants") && r.endsWith("[expires_on]"));
        assertThat(AUTH_HEADER_OK.get()).isTrue();

        // El residente, mientras timbra, NO recibe token por la consulta de detalle.
        JsonNode ringingDetail = data(as(a.neighbour(), a, get("/api/v1/intercom/calls/{id}", id)).andExpect(status().isOk()));
        assertThat(ringingDetail.path("participantToken").isNull()).isTrue();

        JsonNode accepted = data(as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", id)).andExpect(status().isOk()));
        assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.path("participantToken").asText()).startsWith(PARTICIPANT_TOKEN_PREFIX);

        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/connected", id)).andExpect(status().isOk());
        assertThat(load(id).getConnectedAt()).isNull();
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/connected", id)).andExpect(status().isOk());
        assertThat(load(id).getConnectedAt()).isNotNull();

        jdbc.update("update intercom_calls set connected_at = connected_at - interval '7 seconds' where id = ?", id);
        JsonNode ended = data(as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/end", id)).andExpect(status().isOk()));
        assertThat(ended.path("status").asText()).isEqualTo("ENDED");
        assertThat(ended.path("durationSeconds").asInt()).isBetween(7, 9);
        assertThat(ended.path("endReason").asText()).isEqualTo("HUNG_UP_BY_RESIDENT");

        awaitRequest("/active-session/kick-all");
        awaitRequest("PATCH ");
        assertThat(REQUESTS).anyMatch(r -> r.startsWith("DELETE") && r.contains("/participants/"));

        // Ningun token (API ni de participante) en los registros.
        assertThat(output.getAll()).doesNotContain(API_TOKEN).doesNotContain(PARTICIPANT_TOKEN_PREFIX);
    }

    @Test
    @DisplayName("Autorizacion y aislamiento: otro residente, otra comunidad y un residente que intenta llamar")
    void authorizationAndTenantIsolation() throws Exception {
        UUID id = initiate(a.guard(), a);

        as(a.owner(), a, post("/api/v1/intercom/calls/{id}/accept", id)).andExpect(status().isForbidden());
        as(a.owner(), a, get("/api/v1/intercom/calls/{id}", id)).andExpect(status().isForbidden());
        as(a.owner(), a, post("/api/v1/intercom/calls/{id}/end", id)).andExpect(status().isForbidden());
        // Residente no puede iniciar llamadas.
        as(a.neighbour(), a, post("/api/v1/intercom/calls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"recipientResidentId\":\"%s\"}"
                        .formatted(a.home().getId(), a.ownerResident().getId())))
                .andExpect(status().isForbidden());
        // Otra comunidad: el id manipulado no existe en su tenant.
        as(b.guard(), b, get("/api/v1/intercom/calls/{id}", id)).andExpect(status().isNotFound());
        as(b.guard(), b, post("/api/v1/intercom/calls/{id}/end", id)).andExpect(status().isNotFound());
        as(b.neighbour(), b, post("/api/v1/intercom/calls/{id}/accept", id)).andExpect(status().isNotFound());
        // Sesion de la comunidad B enviada a la comunidad A.
        mvc.perform(post("/api/v1/intercom/calls/{id}/accept", id)
                        .header("Authorization", fixture.bearer(b.neighbour()))
                        .header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 403));
        // Porteria de A no puede iniciar hacia una unidad de B.
        as(a.guard(), a, post("/api/v1/intercom/calls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"recipientResidentId\":\"%s\"}"
                        .formatted(b.neighbourHome().getId(), b.neighbourResident().getId())))
                .andExpect(status().isNotFound());

        assertThat(load(id).getStatus()).isEqualTo(IntercomCallStatus.RINGING);
    }

    @Test
    @DisplayName("Residente dado de baja de la unidad: no puede aceptar y la llamada sigue timbrando")
    void revokedResidentCannotAccept() throws Exception {
        UUID id = initiate(a.guard(), a);
        jdbc.update("update residents set status = 'INACTIVE' where id = ?", a.neighbourResident().getId());

        // La membresia del residente puede seguir activa (403 del servicio) o el filtro puede rechazar antes (401).
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", id))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 403));
        assertThat(load(id).getStatus()).isEqualTo(IntercomCallStatus.RINGING);
        assertThat(load(id).getAcceptedAt()).isNull();
    }

    @Test
    @DisplayName("Duplicados y eventos tardios: aceptar dos veces, colgar dos veces, cancelar o conectar tras terminar")
    void duplicatesAndLateEvents() throws Exception {
        UUID id = initiate(a.guard(), a);
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", id)).andExpect(status().isOk());
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", id)).andExpect(status().isConflict());
        // Cancelar una llamada ya aceptada no la modifica.
        assertThat(data(as(a.guard(), a, post("/api/v1/intercom/calls/{id}/cancel", id)).andExpect(status().isOk()))
                .path("status").asText()).isEqualTo("ACCEPTED");

        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/end", id)).andExpect(status().isOk());
        IntercomCall ended = load(id);
        assertThat(ended.getStatus()).isEqualTo(IntercomCallStatus.ENDED);
        assertThat(ended.getEndReason()).isEqualTo("ACCEPTED_NEVER_CONNECTED");
        assertThat(ended.getDurationSeconds()).isZero();

        // Segundo colgado, conexion tardia y rechazo tardio: no reabren ni cambian la llamada.
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/end", id)).andExpect(status().isOk());
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/connected", id)).andExpect(status().isOk());
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/reject", id)).andExpect(status().isOk());
        IntercomCall after = load(id);
        assertThat(after.getStatus()).isEqualTo(IntercomCallStatus.ENDED);
        assertThat(after.getConnectedAt()).isNull();
        assertThat(after.getEndedAt()).isEqualTo(ended.getEndedAt());
    }

    @Test
    @DisplayName("Rechazo y cancelacion: terminales con duracion 0; la porteria queda libre para otra llamada")
    void rejectAndCancel() throws Exception {
        UUID rejected = initiate(a.guard(), a);
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/reject", rejected)).andExpect(status().isOk());
        assertThat(load(rejected).getStatus()).isEqualTo(IntercomCallStatus.REJECTED);
        assertThat(load(rejected).getDurationSeconds()).isZero();
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", rejected)).andExpect(status().isConflict());

        UUID cancelled = initiate(a.guard(), a);
        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/cancel", cancelled)).andExpect(status().isOk());
        assertThat(load(cancelled).getStatus()).isEqualTo(IntercomCallStatus.CANCELLED);
        assertThat(load(cancelled).getEndReason()).isEqualTo("CANCELLED_BY_CALLER");
    }

    @Test
    @DisplayName("Fallo del proveedor al crear la sala: 409 sin llamada guardada ni porteria bloqueada")
    void providerFailure() throws Exception {
        FAIL_MEETING.set(true);
        long before = calls.count();
        as(a.guard(), a, post("/api/v1/intercom/calls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"recipientResidentId\":\"%s\"}"
                        .formatted(a.neighbourHome().getId(), a.neighbourResident().getId())))
                .andExpect(status().isConflict());
        assertThat(calls.count()).isEqualTo(before);
        FAIL_MEETING.set(false);
        initiate(a.guard(), a);
    }

    @Test
    @DisplayName("Aceptada que nunca conecta: el job la cierra (ENDED, 0 s) y libera a los participantes; la conectada no se toca")
    void acceptedNeverConnectedTimeout() throws Exception {
        UUID stalled = initiate(a.guard(), a);
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", stalled)).andExpect(status().isOk());
        // Antes del plazo: no se toca.
        intercomService.handleAcceptedNeverConnectedTimeouts();
        assertThat(load(stalled).getStatus()).isEqualTo(IntercomCallStatus.ACCEPTED);
        // Mientras sigue ACCEPTED, la porteria esta ocupada.
        as(a.guard(), a, post("/api/v1/intercom/calls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"recipientResidentId\":\"%s\"}"
                        .formatted(a.home().getId(), a.ownerResident().getId())))
                .andExpect(status().isConflict());

        jdbc.update("update intercom_calls set accepted_at = now() - interval '2 minutes' where id = ?", stalled);
        intercomService.handleAcceptedNeverConnectedTimeouts();
        IntercomCall closed = load(stalled);
        assertThat(closed.getStatus()).isEqualTo(IntercomCallStatus.ENDED);
        assertThat(closed.getEndReason()).isEqualTo("ACCEPTED_NEVER_CONNECTED_TIMEOUT");
        assertThat(closed.getTechnicalFailureReason()).isEqualTo("AUDIO_HANDSHAKE_TIMEOUT");
        assertThat(closed.getDurationSeconds()).isZero();

        // La porteria ya puede llamar; una llamada conectada antigua no la cierra el job.
        UUID connected = initiate(a.guard(), a);
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", connected)).andExpect(status().isOk());
        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/connected", connected)).andExpect(status().isOk());
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/connected", connected)).andExpect(status().isOk());
        jdbc.update("update intercom_calls set accepted_at = now() - interval '10 minutes' where id = ?", connected);
        intercomService.handleAcceptedNeverConnectedTimeouts();
        assertThat(load(connected).getStatus()).isEqualTo(IntercomCallStatus.ACCEPTED);
        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/end", connected)).andExpect(status().isOk());
    }

    /** Ejecuta las tareas a la vez desde una barrera comun; devuelve sus resultados o excepciones. */
    private List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    } finally {
                        TenantContextHolder.clear();
                    }
                }));
            }
            start.countDown();
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : futures) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Carrera aceptar contra caducidad del timbre (8 rondas): el estado final coincide con la respuesta")
    void acceptVersusRingTimeout() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID id = initiate(a.guard(), a);
            jdbc.update("update intercom_calls set created_at = now() - interval '5 minutes' where id = ?", id);
            UUID tenant = a.tenantId();
            UUID resident = a.neighbour().getId();
            List<Object> results = race(List.of(
                    () -> {
                        TenantContextHolder.setTenantId(tenant);
                        return intercomService.acceptCall(id, resident);
                    },
                    () -> {
                        intercomService.handleRingTimeouts();
                        return "job";
                    }));
            IntercomCall finalState = load(id);
            boolean acceptWon = results.get(0) instanceof IntercomCallResponse;
            if (acceptWon) {
                assertThat(finalState.getStatus()).as("ronda %d", round).isEqualTo(IntercomCallStatus.ACCEPTED);
                as(a.guard(), a, post("/api/v1/intercom/calls/{id}/end", id)).andExpect(status().isOk());
            } else {
                assertThat(results.get(0)).as("ronda %d", round).isInstanceOf(BusinessRuleConflictException.class);
                assertThat(finalState.getStatus()).as("ronda %d", round).isEqualTo(IntercomCallStatus.MISSED);
            }
            assertThat(results.get(1)).isEqualTo("job");
        }
    }

    @Test
    @DisplayName("Dos aceptaciones simultaneas (8 rondas): exactamente una gana")
    void simultaneousAccepts() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID id = initiate(a.guard(), a);
            UUID tenant = a.tenantId();
            UUID resident = a.neighbour().getId();
            Callable<Object> accept = () -> {
                TenantContextHolder.setTenantId(tenant);
                return intercomService.acceptCall(id, resident);
            };
            List<Object> results = race(List.of(accept, accept));
            long wins = results.stream().filter(r -> r instanceof IntercomCallResponse).count();
            assertThat(wins).as("ronda %d", round).isEqualTo(1);
            assertThat(results).filteredOn(r -> !(r instanceof IntercomCallResponse))
                    .allMatch(r -> r instanceof BusinessRuleConflictException);
            as(a.guard(), a, post("/api/v1/intercom/calls/{id}/end", id)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Dos porterias llaman a la vez al mismo residente (8 rondas): como mucho una llamada activa")
    void simultaneousCallsToSameRecipient() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID tenant = a.tenantId();
            String body = "{\"propertyId\":\"%s\",\"recipientResidentId\":\"%s\"}"
                    .formatted(a.neighbourHome().getId(), a.neighbourResident().getId());
            Callable<Object> byGuard = () -> as(a.guard(), a, post("/api/v1/intercom/calls")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
            Callable<Object> byAdmin = () -> as(a.admin(), a, post("/api/v1/intercom/calls")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
            race(List.of(byGuard, byAdmin));
            Integer active = jdbc.queryForObject(
                    "select count(*) from intercom_calls where tenant_id = ? and recipient_user_id = ? and status in ('RINGING','ACCEPTED','INITIATING')",
                    Integer.class, tenant, a.neighbour().getId());
            assertThat(active).as("ronda %d", round).isLessThanOrEqualTo(1);
            jdbc.update("update intercom_calls set status = 'CANCELLED', ended_at = now() where tenant_id = ? and status = 'RINGING'", tenant);
        }
    }
}
