package com.havita.modules.intercom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.intercom.application.IntercomPushDeliveryRegistry;
import com.havita.modules.intercom.application.IntercomPushEvent;
import com.havita.modules.intercom.application.IntercomService;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import com.havita.modules.push.application.WebPushService;
import com.havita.modules.push.domain.PushSubscription;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P8-B — Web Push de la citofonia contra PostgreSQL aislado y un simulador local de RealtimeKit. El envio de push se
 * REGISTRA en memoria (no se contacta ningun servicio de push, no hay claves VAPID reales). Datos sinteticos.
 *
 * Cubre: destinatarios (solo el llamado, todos sus dispositivos, nunca otra comunidad u otro residente), contenido sin
 * datos personales ni tokens, TTL/Urgency/Topic, avisos de fin con el mismo Topic, exclusion del dispositivo que
 * contesta o rechaza, deduplicacion, perdida de acceso, revocacion (dispositivo, membresia, cierre de sesion),
 * llamada entrante (recuperacion) con aislamiento, recibos y la fuga de notificaciones de unidad (D3).
 */
@Import(IntercomPushIT.PushCapture.class)
class IntercomPushIT extends PostgresIntegrationTest {

    static final String API_TOKEN = "synthetic-api-token-p8b-not-real";
    static final String PARTICIPANT_TOKEN_PREFIX = "rtk-participant-token-synthetic-p8b-";
    static final HttpServer STUB;

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                String body;
                if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/meetings")) {
                    body = "{\"success\":true,\"data\":{\"id\":\"meeting-" + UUID.randomUUID() + "\"}}";
                } else if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/participants")) {
                    body = "{\"success\":true,\"data\":{\"id\":\"participant-" + UUID.randomUUID() + "\",\"token\":\""
                            + PARTICIPANT_TOKEN_PREFIX + UUID.randomUUID() + "\"}}";
                } else if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/participants")) {
                    body = "{\"success\":true,\"data\":[]}";
                } else {
                    body = "{\"success\":true}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
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
        registry.add("havita.intercom.scheduler.enabled", () -> "false");
        registry.add("havita.intercom.reconciliation.enabled", () -> "false");
    }

    /** Un envio registrado (no se contacta ningun servicio de push). */
    record Sent(UUID subscriptionId, String endpoint, UUID userId, UUID tenantId, Map<String, Object> payload,
                WebPushService.PushOptions options) {}

    /** Sustituye el envio real: registra y responde segun el endpoint (2xx por defecto). */
    static class RecordingWebPush extends WebPushService {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final Map<String, Integer> statusByEndpoint = new ConcurrentHashMap<>();
        volatile boolean enabled = true;
        private final PushSubscriptionRepository repo;

        RecordingWebPush(PushSubscriptionRepository repo, ObjectMapper mapper) {
            super(repo, mapper);
            this.repo = repo;
        }

        @Override
        public boolean isConfigured() {
            return enabled;
        }

        @Override
        public PushResult send(PushSubscription s, Map<String, Object> payload, PushOptions options) {
            sent.add(new Sent(s.getId(), s.getEndpoint(), s.getUserId(), s.getTenantId(), Map.copyOf(payload), options));
            int code = statusByEndpoint.getOrDefault(s.getEndpoint(), 201);
            if (code == 410) {
                s.deactivate();
                repo.save(s);
                return new PushResult(Outcome.GONE, code);
            }
            return code < 300 ? new PushResult(Outcome.SENT, code) : new PushResult(Outcome.FAILED, code);
        }
    }

    @TestConfiguration
    static class PushCapture {
        @Bean
        @Primary
        RecordingWebPush recordingWebPush(PushSubscriptionRepository repo, ObjectMapper mapper) {
            return new RecordingWebPush(repo, mapper);
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RecordingWebPush push;
    @Autowired private PushSubscriptionRepository subscriptions;
    @Autowired private MembershipRepository memberships;
    @Autowired private IntercomCallRepository calls;
    @Autowired private IntercomService intercomService;
    @Autowired private IntercomPushDeliveryRegistry registry;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        push.enabled = true;
        push.statusByEndpoint.clear();
    }

    // ---- utilidades ------------------------------------------------------------------------------------------------

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private JsonNode data(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString()).path("data");
    }

    /** Suscribe un dispositivo sintetico del usuario en la comunidad del lado; devuelve su endpoint. */
    private String subscribe(User user, Side side) throws Exception {
        String endpoint = "https://push.example.test/p8b/" + UUID.randomUUID();
        as(user, side, post("/api/v1/push-subscriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpoint\":\"%s\",\"keys\":{\"p256dh\":\"BSyntheticKey%s\",\"auth\":\"syntheticAuth\"},\"deviceName\":\"Android\"}"
                        .formatted(endpoint, UUID.randomUUID().toString().replace("-", ""))))
                .andExpect(status().isCreated());
        return endpoint;
    }

    private PushSubscription sub(String endpoint) {
        return subscriptions.findByEndpoint(endpoint).orElseThrow();
    }

    private UUID initiate() throws Exception {
        JsonNode d = data(as(a.guard(), a, post("/api/v1/intercom/calls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"recipientResidentId\":\"%s\"}"
                        .formatted(a.neighbourHome().getId(), a.neighbourResident().getId())))
                .andExpect(status().isCreated()));
        return UUID.fromString(d.path("callId").asText());
    }

    private List<Sent> sentFor(UUID callId) {
        return push.sent.stream().filter(s -> callId.toString().equals(s.payload().get("callId"))).toList();
    }

    private List<Sent> await(Predicate<List<Sent>> condition, java.util.function.Supplier<List<Sent>> source) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            List<Sent> current = source.get();
            if (condition.test(current)) return current;
            Thread.sleep(50);
        }
        throw new AssertionError("no se cumplio la condicion de envio: " + source.get());
    }

    private static String kind(Sent s) {
        return String.valueOf(s.payload().get("kind"));
    }

    /** Espera a que termine el despacho asincrono y comprueba que no hay envios extra. */
    private void settle() throws InterruptedException {
        Thread.sleep(400);
    }

    // ---- pruebas ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Timbre: solo el residente llamado, todos sus dispositivos; texto fijo sin datos personales ni tokens; TTL≤45, Urgency high, Topic")
    void ringingTargetsOnlyRecipientDevices() throws Exception {
        String phone = subscribe(a.neighbour(), a);
        String tablet = subscribe(a.neighbour(), a);
        String otherResident = subscribe(a.owner(), a);
        String guardDevice = subscribe(a.guard(), a);
        String otherTenant = subscribe(b.neighbour(), b);

        UUID callId = initiate();
        List<Sent> sent = await(l -> l.size() >= 2, () -> sentFor(callId));
        settle();
        sent = sentFor(callId);

        assertThat(sent).extracting(Sent::endpoint).containsExactlyInAnyOrder(phone, tablet);
        assertThat(sent).extracting(Sent::endpoint).doesNotContain(otherResident, guardDevice, otherTenant);
        for (Sent s : sent) {
            assertThat(s.payload()).containsEntry("title", "Havita")
                    .containsEntry("body", "Llamada de portería")
                    .containsEntry("detail", "Toca para abrir y contestar")
                    .containsEntry("type", "INTERCOM_CALL")
                    .containsEntry("kind", "RINGING")
                    .containsEntry("url", "/resident?intercomCall=" + callId)
                    .containsEntry("tag", "intercom-" + callId);
            String json = objectMapper.writeValueAsString(s.payload());
            // Sin nombres, unidad, porteria ni tokens de RealtimeKit.
            assertThat(json).doesNotContain(a.neighbour().getFullName() == null ? "§" : a.neighbour().getFullName())
                    .doesNotContain(a.neighbourResident().getName())
                    .doesNotContain(a.neighbourHome().getNumber())
                    .doesNotContain(PARTICIPANT_TOKEN_PREFIX)
                    .doesNotContain("token");
            assertThat(s.options().ttlSeconds()).isBetween(1, IntercomService.RING_TIMEOUT_SECONDS);
            assertThat(s.options().urgency()).isEqualTo(nl.martijndwars.webpush.Urgency.HIGH);
            assertThat(s.options().topic()).matches("[A-Za-z0-9_-]{32}");
            assertThat((String) s.payload().get("receipt")).matches("[A-Za-z0-9_-]{22}");
        }
        assertThat(sent.get(0).options().topic()).isEqualTo(sent.get(1).options().topic());
        assertThat(sent.get(0).payload().get("receipt")).isNotEqualTo(sent.get(1).payload().get("receipt"));
    }

    @Test
    @DisplayName("Cancelada: aviso de fin con el MISMO Topic a los dispositivos que recibieron el timbre; aceptar despues → 409")
    void cancelledReplacesRinging() throws Exception {
        String phone = subscribe(a.neighbour(), a);
        String tablet = subscribe(a.neighbour(), a);
        UUID callId = initiate();
        List<Sent> ringing = await(l -> l.size() >= 2, () -> sentFor(callId));

        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/cancel", callId)).andExpect(status().isOk());
        List<Sent> all = await(l -> l.stream().filter(s -> kind(s).equals("CANCELLED")).count() >= 2, () -> sentFor(callId));
        List<Sent> ends = all.stream().filter(s -> kind(s).equals("CANCELLED")).toList();
        assertThat(ends).extracting(Sent::endpoint).containsExactlyInAnyOrder(phone, tablet);
        assertThat(ends).allSatisfy(s -> {
            assertThat(s.options().topic()).isEqualTo(ringing.get(0).options().topic());
            assertThat(s.payload()).containsEntry("body", "Llamada de portería cancelada").containsEntry("tag", "intercom-" + callId);
            assertThat(s.payload()).doesNotContainKey("detail");
        });

        // Un aviso antiguo no da acceso: la llamada ya no timbra.
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", callId)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Contestar en un dispositivo: «contestada en otro dispositivo» SOLO a los demas; rechazar excluye al que rechaza")
    void answeredAndRejectedExcludeActingDevice() throws Exception {
        String phone = subscribe(a.neighbour(), a);
        String tablet = subscribe(a.neighbour(), a);

        UUID answered = initiate();
        await(l -> l.size() >= 2, () -> sentFor(answered));
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", answered).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pushEndpoint\":\"" + phone + "\"}")).andExpect(status().isOk());
        await(l -> l.stream().anyMatch(s -> kind(s).equals("ANSWERED")), () -> sentFor(answered));
        settle();
        assertThat(sentFor(answered).stream().filter(s -> kind(s).equals("ANSWERED")))
                .extracting(Sent::endpoint).containsExactly(tablet);
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/end", answered)).andExpect(status().isOk());

        UUID rejected = initiate();
        await(l -> l.size() >= 2, () -> sentFor(rejected));
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/reject", rejected).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pushEndpoint\":\"" + tablet + "\"}")).andExpect(status().isOk());
        await(l -> l.stream().anyMatch(s -> kind(s).equals("REJECTED")), () -> sentFor(rejected));
        settle();
        assertThat(sentFor(rejected).stream().filter(s -> kind(s).equals("REJECTED")))
                .extracting(Sent::endpoint).containsExactly(phone);

        // Un endpoint ajeno no amplia destinatarios: solo excluye una suscripcion propia (aqui, ninguna).
        UUID third = initiate();
        await(l -> l.size() >= 2, () -> sentFor(third));
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/accept", third).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pushEndpoint\":\"https://push.example.test/ajeno\"}")).andExpect(status().isOk());
        await(l -> l.stream().filter(s -> kind(s).equals("ANSWERED")).count() >= 2, () -> sentFor(third));
        assertThat(sentFor(third).stream().filter(s -> kind(s).equals("ANSWERED")))
                .extracting(Sent::endpoint).containsExactlyInAnyOrder(phone, tablet);
        as(a.neighbour(), a, post("/api/v1/intercom/calls/{id}/end", third)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Expirada (45 s): aviso «perdida»; la llamada entrante deja de ofrecerse")
    void missedAfterTimeout() throws Exception {
        String phone = subscribe(a.neighbour(), a);
        UUID callId = initiate();
        await(l -> !l.isEmpty(), () -> sentFor(callId));
        jdbc.update("update intercom_calls set created_at = created_at - interval '60 seconds',"
                + " ringing_at = ringing_at - interval '60 seconds' where id = ?", callId);

        // Antes de que la tarea la marque, la recuperacion ya no la ofrece (fuera de plazo).
        assertThat(data(as(a.neighbour(), a, get("/api/v1/intercom/calls/incoming")).andExpect(status().isOk())).isNull()).isTrue();

        intercomService.handleRingTimeouts();
        assertThat(calls.findById(callId).orElseThrow().getStatus()).isEqualTo(IntercomCallStatus.MISSED);
        List<Sent> all = await(l -> l.stream().anyMatch(s -> kind(s).equals("MISSED")), () -> sentFor(callId));
        assertThat(all.stream().filter(s -> kind(s).equals("MISSED"))).extracting(Sent::endpoint).containsExactly(phone);
    }

    @Test
    @DisplayName("Deduplicacion: un evento de timbre repetido no reenvia; tampoco un fin repetido")
    void duplicateEventsDoNotResend() throws Exception {
        subscribe(a.neighbour(), a);
        UUID callId = initiate();
        await(l -> l.size() == 1, () -> sentFor(callId));
        events.publishEvent(new IntercomPushEvent(callId, a.tenantId(), IntercomPushEvent.Kind.RINGING, null));
        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/cancel", callId)).andExpect(status().isOk());
        await(l -> l.stream().anyMatch(s -> kind(s).equals("CANCELLED")), () -> sentFor(callId));
        events.publishEvent(new IntercomPushEvent(callId, a.tenantId(), IntercomPushEvent.Kind.CANCELLED, null));
        settle();
        assertThat(sentFor(callId).stream().filter(s -> kind(s).equals("RINGING"))).hasSize(1);
        assertThat(sentFor(callId).stream().filter(s -> kind(s).equals("CANCELLED"))).hasSize(1);
    }

    @Test
    @DisplayName("Suscripcion caducada (410) se desactiva y no recibe avisos de fin; suscripcion revocada no recibe")
    void goneAndRevokedSubscriptions() throws Exception {
        String gone = subscribe(a.neighbour(), a);
        String ok = subscribe(a.neighbour(), a);
        String revoked = subscribe(a.neighbour(), a);
        push.statusByEndpoint.put(gone, 410);
        as(a.neighbour(), a, post("/api/v1/push-subscriptions/unsubscribe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpoint\":\"" + revoked + "\"}")).andExpect(status().isOk());
        assertThat(sub(revoked).getActive()).isFalse();

        UUID callId = initiate();
        await(l -> l.size() >= 2, () -> sentFor(callId));
        settle();
        assertThat(sentFor(callId)).extracting(Sent::endpoint).containsExactlyInAnyOrder(gone, ok);
        assertThat(sub(gone).getActive()).isFalse();

        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/cancel", callId)).andExpect(status().isOk());
        await(l -> l.stream().anyMatch(s -> kind(s).equals("CANCELLED")), () -> sentFor(callId));
        settle();
        assertThat(sentFor(callId).stream().filter(s -> kind(s).equals("CANCELLED"))).extracting(Sent::endpoint).containsExactly(ok);
    }

    @Test
    @DisplayName("Revocar el dispositivo: solo una suscripcion PROPIA en la comunidad actual (un endpoint ajeno no se toca)")
    void unsubscribeOnlyOwnDevice() throws Exception {
        String mine = subscribe(a.neighbour(), a);
        String ownerDevice = subscribe(a.owner(), a);
        as(a.neighbour(), a, post("/api/v1/push-subscriptions/unsubscribe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpoint\":\"" + ownerDevice + "\"}")).andExpect(status().isOk());
        assertThat(sub(ownerDevice).getActive()).isTrue();
        // Desde otra comunidad tampoco.
        as(b.neighbour(), b, post("/api/v1/push-subscriptions/unsubscribe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpoint\":\"" + mine + "\"}")).andExpect(status().isOk());
        assertThat(sub(mine).getActive()).isTrue();
        as(a.neighbour(), a, post("/api/v1/push-subscriptions/unsubscribe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpoint\":\"" + mine + "\"}")).andExpect(status().isOk());
        assertThat(sub(mine).getActive()).isFalse();
        // Sin sesion: 401/403.
        mvc.perform(post("/api/v1/push-subscriptions/unsubscribe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"" + ownerDevice + "\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    @DisplayName("Perdida de acceso: relacion con la unidad inactiva o membresia inactiva → sin aviso; la membresia inactiva desactiva sus suscripciones de esa comunidad")
    void lostAccessGetsNoPush() throws Exception {
        String device = subscribe(a.neighbour(), a);

        // 1) Relacion con la unidad dada de baja entre la llamada y el despacho.
        push.enabled = false;
        UUID callId = initiate();
        jdbc.update("update residents set status = 'INACTIVE' where id = ?", a.neighbourResident().getId());
        push.enabled = true;
        events.publishEvent(new IntercomPushEvent(callId, a.tenantId(), IntercomPushEvent.Kind.RINGING, null));
        settle();
        assertThat(sentFor(callId)).isEmpty();
        jdbc.update("update residents set status = 'ACTIVE' where id = ?", a.neighbourResident().getId());
        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/cancel", callId)).andExpect(status().isOk());

        // 2) Membresia de la comunidad inactiva: el evento SEC-9 desactiva sus suscripciones de esa comunidad.
        String elsewhere = "https://push.example.test/p8b/otra-" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(st -> {
            PushSubscription other = PushSubscription.builder().userId(a.neighbour().getId()).endpoint(elsewhere)
                    .p256dh("BSynthetic").auth("synthetic").deviceName("iOS").active(true).build();
            other.setTenantId(b.tenantId()); // suscripcion sintetica en otra comunidad SIN membresia → se desactivara
            subscriptions.save(other);
        });
        transactionTemplate.executeWithoutResult(st -> {
            var m = memberships.findByUserIdAndTenantId(a.neighbour().getId(), a.tenantId()).orElseThrow();
            m.setStatus(MembershipStatus.INACTIVE);
            memberships.save(m);
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (sub(device).getActive() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(sub(device).getActive()).isFalse();
        assertThat(sub(elsewhere).getActive()).isFalse();
    }

    @Test
    @DisplayName("Cierre de sesion en el servidor: todas las suscripciones del usuario dejan de recibir")
    void serverLogoutRevokesAll() throws Exception {
        String one = subscribe(a.neighbour(), a);
        String two = subscribe(a.neighbour(), a);
        as(a.neighbour(), a, post("/api/v1/auth/logout")).andExpect(status().isOk());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while ((sub(one).getActive() || sub(two).getActive()) && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(sub(one).getActive()).isFalse();
        assertThat(sub(two).getActive()).isFalse();
    }

    @Test
    @DisplayName("Llamada entrante (recuperacion): solo el llamado; otro residente, porteria y otra comunidad no; sin token; tras cancelar, nada")
    void incomingCallIsolation() throws Exception {
        UUID callId = initiate();
        JsonNode mine = data(as(a.neighbour(), a, get("/api/v1/intercom/calls/incoming")).andExpect(status().isOk()));
        assertThat(mine.path("callId").asText()).isEqualTo(callId.toString());
        assertThat(mine.path("status").asText()).isEqualTo("RINGING");
        assertThat(mine.path("participantToken").isNull() || mine.path("participantToken").isMissingNode()).isTrue();

        assertThat(data(as(a.owner(), a, get("/api/v1/intercom/calls/incoming")).andExpect(status().isOk())).isNull()).isTrue();
        as(a.guard(), a, get("/api/v1/intercom/calls/incoming")).andExpect(status().isForbidden());
        assertThat(data(as(b.neighbour(), b, get("/api/v1/intercom/calls/incoming")).andExpect(status().isOk())).isNull()).isTrue();
        // Sesion de B enviada a la comunidad A.
        mvc.perform(get("/api/v1/intercom/calls/incoming")
                        .header("Authorization", fixture.bearer(b.neighbour()))
                        .header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 403));

        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/cancel", callId)).andExpect(status().isOk());
        assertThat(data(as(a.neighbour(), a, get("/api/v1/intercom/calls/incoming")).andExpect(status().isOk())).isNull()).isTrue();
    }

    @Test
    @DisplayName("Recibos: publicos con nonce; fijan solo la primera marca; nonce desconocido o etapa invalida no hacen nada; siempre 204")
    void receipts() throws Exception {
        subscribe(a.neighbour(), a);
        UUID callId = initiate();
        Sent s = await(l -> !l.isEmpty(), () -> sentFor(callId)).get(0);
        String nonce = (String) s.payload().get("receipt");

        mvc.perform(post("/api/v1/intercom/push-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"r\":\"" + nonce + "\",\"s\":\"RECEIVED\"}")).andExpect(status().isNoContent());
        var delivery = registry.deliveriesFor(callId).get(0);
        var firstReceived = delivery.receivedAt();
        assertThat(firstReceived).isNotNull();
        mvc.perform(post("/api/v1/intercom/push-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"r\":\"" + nonce + "\",\"s\":\"RECEIVED\"}")).andExpect(status().isNoContent());
        assertThat(registry.deliveriesFor(callId).get(0).receivedAt()).isEqualTo(firstReceived);
        mvc.perform(post("/api/v1/intercom/push-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"r\":\"" + nonce + "\",\"s\":\"DISPLAYED\"}")).andExpect(status().isNoContent());
        assertThat(registry.deliveriesFor(callId).get(0).displayedAt()).isNotNull();

        mvc.perform(post("/api/v1/intercom/push-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"r\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"s\":\"CLICKED\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/intercom/push-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"r\":\"" + nonce + "\",\"s\":\"HACKED\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/intercom/push-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNoContent());
        assertThat(registry.deliveriesFor(callId).get(0).clickedAt()).isNull();
        as(a.guard(), a, post("/api/v1/intercom/calls/{id}/cancel", callId)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Fuga D3: una notificacion de UNIDAD solo llega a los residentes activos de esa unidad (no al resto de la comunidad ni a otra)")
    void propertyNotificationOnlyToItsResidents() throws Exception {
        String neighbourDevice = subscribe(a.neighbour(), a);
        String ownerDevice = subscribe(a.owner(), a);
        String guardDevice = subscribe(a.guard(), a);
        String otherTenant = subscribe(b.neighbour(), b);
        String title = "Paquete P8B " + UUID.randomUUID();

        as(a.admin(), a, post("/api/v1/realtime/notifications").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"title\":\"%s\",\"message\":\"sintetico\",\"type\":\"PACKAGE_RECEIVED\"}"
                        .formatted(a.neighbourHome().getId(), title)))
                .andExpect(status().isCreated());
        java.util.function.Supplier<List<Sent>> byTitle = () -> push.sent.stream().filter(s -> title.equals(s.payload().get("title"))).toList();
        await(l -> !l.isEmpty(), byTitle);
        settle();
        assertThat(byTitle.get()).extracting(Sent::endpoint).containsExactly(neighbourDevice);
        assertThat(byTitle.get()).extracting(Sent::endpoint).doesNotContain(ownerDevice, guardDevice, otherTenant);
    }
}
