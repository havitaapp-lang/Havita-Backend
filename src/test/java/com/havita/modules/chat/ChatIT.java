package com.havita.modules.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.chat.application.ChatNotifier;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 3 — chat interno residente ↔ porteria: reglas de negocio, autorizacion, aislamiento entre comunidades y
 * usuarios, concurrencia, tiempo real y notificaciones. Datos sinteticos (TwoTenantFixture).
 */
@RecordApplicationEvents
class ChatIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private ApplicationEvents applicationEvents;
    @Autowired private com.havita.modules.chat.application.ChatService chatService;

    private Side a;
    private Side b;
    private User guard2;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        guard2 = extraGuard(a);
    }

    // ── utilidades ───────────────────────────────────────────────────────────

    private User extraGuard(Side side) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder().email("porteria2-" + tag + "@example.test")
                .passwordHash("{noop}not-used").fullName("Portero Dos " + tag).phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(side.tenantId())
                .role(RoleType.SECURITY).status(MembershipStatus.ACTIVE).build());
        return u;
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode data(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    private String code(ResultActions r) throws Exception {
        JsonNode n = objectMapper.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        return n.has("code") ? n.get("code").asText() : n.path("error").path("code").asText(n.toString());
    }

    private String bodyJson(String text, UUID clientId) throws Exception {
        var node = objectMapper.createObjectNode().put("body", text);
        if (clientId != null) node.put("clientMessageId", clientId.toString());
        return objectMapper.writeValueAsString(node);
    }

    /** El residente (owner de A, una sola unidad) escribe a porteria. Devuelve el id de la conversacion. */
    private String residentWrites(User resident, String text) throws Exception {
        JsonNode r = data(as(resident, a, json(post("/api/v1/chat/me/messages"), bodyJson(text, null)))
                .andExpect(status().isCreated()));
        return r.get("conversation").get("id").asText();
    }

    private ResultActions staffSends(User guard, Side side, String conversationId, String text) throws Exception {
        return as(guard, side, json(post("/api/v1/chat/inbox/conversations/%s/messages".formatted(conversationId)),
                bodyJson(text, null)));
    }

    private JsonNode conversation(User guard, String id) throws Exception {
        return data(as(guard, a, get("/api/v1/chat/inbox/conversations/%s".formatted(id))).andExpect(status().isOk()));
    }

    private List<String> eventTypes(User guard, String id) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode e : data(as(guard, a, get("/api/v1/chat/inbox/conversations/%s/events".formatted(id)))
                .andExpect(status().isOk()))) {
            out.add(e.get("type").asText());
        }
        return out;
    }

    private static JsonNode findById(JsonNode list, String id) {
        for (JsonNode n : list) if (id.equals(n.get("id").asText())) return n;
        return null;
    }

    private MvcResult openStream(User user, Side side) throws Exception {
        String body = mvc.perform(post("/api/v1/sync/stream-ticket")
                        .header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String ticket = objectMapper.readTree(body).get("ticket").asText();
        return mvc.perform(get("/api/v1/sync/stream").param("ticket", ticket)).andExpect(request().asyncStarted()).andReturn();
    }

    private List<JsonNode> sseEvents(MvcResult stream, String type) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String line : stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("data:") && line.contains("\"type\":\"" + type + "\"")) {
                out.add(objectMapper.readTree(line.substring("data:".length())));
            }
        }
        return out;
    }

    private List<ChatNotifier.ChatPushRequested> pushes() {
        return applicationEvents.stream(ChatNotifier.ChatPushRequested.class).toList();
    }

    // ── creacion, bandeja y aislamiento ──────────────────────────────────────

    @Test
    @DisplayName("El residente escribe: se crea la conversacion PENDING de su unidad y aparece en la bandeja de porteria de SU comunidad")
    void residentStartsConversation() throws Exception {
        JsonNode sent = data(as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("Hola portería", null)))
                .andExpect(status().isCreated()));
        String id = sent.get("conversation").get("id").asText();
        assertThat(sent.get("conversation").get("status").asText()).isEqualTo("PENDING");
        assertThat(sent.get("conversation").get("propertyId").asText()).isEqualTo(a.home().getId().toString());
        assertThat(sent.get("message").get("seq").asLong()).isEqualTo(1);
        assertThat(sent.get("message").get("authorRole").asText()).isEqualTo("RESIDENT");

        JsonNode inbox = data(as(a.guard(), a, get("/api/v1/chat/inbox/conversations")).andExpect(status().isOk()));
        JsonNode row = findById(inbox.get("content"), id);
        assertThat(row).isNotNull();
        assertThat(row.get("status").asText()).isEqualTo("PENDING");
        assertThat(row.get("unread").asInt()).isEqualTo(1);
        assertThat(row.get("residentName").asText()).isEqualTo(a.owner().getFullName());
        assertThat(row.get("unitLabel").asText()).contains(a.home().getNumber());
        assertThat(row.get("lastMessagePreview").asText()).isEqualTo("Hola portería");

        // Busqueda por unidad y por residente.
        assertThat(findById(data(as(a.guard(), a, get("/api/v1/chat/inbox/conversations").param("q", a.home().getNumber())))
                .get("content"), id)).isNotNull();
        assertThat(findById(data(as(a.guard(), a, get("/api/v1/chat/inbox/conversations").param("q", "no-existe-xyz")))
                .get("content"), id)).isNull();

        JsonNode mine = data(as(a.owner(), a, get("/api/v1/chat/me/conversations")).andExpect(status().isOk()));
        assertThat(findById(mine, id)).isNotNull();

        // Otra comunidad: ni en su bandeja ni por id.
        JsonNode otherInbox = data(as(b.guard(), b, get("/api/v1/chat/inbox/conversations")).andExpect(status().isOk()));
        assertThat(findById(otherInbox.get("content"), id)).isNull();
        as(b.guard(), b, get("/api/v1/chat/inbox/conversations/%s".formatted(id))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Aislamiento: otro residente, otra comunidad y roles ajenos no ven ni operan la conversacion")
    void isolation() throws Exception {
        String id = residentWrites(a.owner(), "Mensaje privado del propietario");

        // Otro residente de la misma comunidad.
        as(a.neighbour(), a, get("/api/v1/chat/me/conversations/%s/messages".formatted(id))).andExpect(status().isNotFound());
        as(a.neighbour(), a, json(post("/api/v1/chat/me/conversations/%s/read".formatted(id)), "{\"upToSeq\":1}"))
                .andExpect(status().isNotFound());
        assertThat(findById(data(as(a.neighbour(), a, get("/api/v1/chat/me/conversations"))), id)).isNull();

        // Roles: residente no usa la bandeja; porteria no usa /me; la administracion solo consulta (ver adminReadOnly).
        as(a.owner(), a, get("/api/v1/chat/inbox/conversations")).andExpect(status().isForbidden());
        as(a.owner(), a, get("/api/v1/chat/inbox/conversations/%s".formatted(id))).andExpect(status().isForbidden());
        as(a.guard(), a, get("/api/v1/chat/me/conversations")).andExpect(status().isForbidden());
        as(a.admin(), a, get("/api/v1/chat/inbox/agents")).andExpect(status().isForbidden());
        // La administracion de OTRA comunidad no ve la conversacion.
        as(b.admin(), b, get("/api/v1/chat/inbox/conversations/%s".formatted(id))).andExpect(status().isNotFound());
        assertThat(findById(data(as(b.admin(), b, get("/api/v1/chat/inbox/conversations"))).get("content"), id)).isNull();

        // Otra comunidad: todas las operaciones por id responden 404 (no revelan que existe).
        as(b.guard(), b, get("/api/v1/chat/inbox/conversations/%s/messages".formatted(id))).andExpect(status().isNotFound());
        as(b.guard(), b, get("/api/v1/chat/inbox/conversations/%s/events".formatted(id))).andExpect(status().isNotFound());
        as(b.guard(), b, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(id))).andExpect(status().isNotFound());
        as(b.guard(), b, post("/api/v1/chat/inbox/conversations/%s/release".formatted(id))).andExpect(status().isNotFound());
        as(b.guard(), b, post("/api/v1/chat/inbox/conversations/%s/close".formatted(id))).andExpect(status().isNotFound());
        as(b.guard(), b, json(post("/api/v1/chat/inbox/conversations/%s/messages".formatted(id)), bodyJson("x", null)))
                .andExpect(status().isNotFound());
        as(b.guard(), b, json(post("/api/v1/chat/inbox/conversations/%s/read".formatted(id)), "{\"upToSeq\":1}"))
                .andExpect(status().isNotFound());
        as(b.guard(), b, json(post("/api/v1/chat/inbox/conversations/%s/transfer".formatted(id)),
                "{\"toUserId\":\"%s\"}".formatted(b.guard().getId()))).andExpect(status().isNotFound());
        as(b.owner(), b, get("/api/v1/chat/me/conversations/%s/messages".formatted(id))).andExpect(status().isNotFound());

        // Cabecera de otra comunidad con el token de A: el filtro de comunidad lo rechaza.
        int crossHeader = mvc.perform(get("/api/v1/chat/inbox/conversations")
                        .header("Authorization", fixture.bearer(a.guard())).header("X-Tenant-ID", b.tenantId().toString()))
                .andReturn().getResponse().getStatus();
        assertThat(crossHeader).isIn(401, 403);

        // Iniciar con una unidad o residente de otra comunidad, o con un residente que no es de esa unidad.
        as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations"),
                "{\"propertyId\":\"%s\",\"residentUserId\":\"%s\"}".formatted(b.home().getId(), b.owner().getId())))
                .andExpect(status().isNotFound());
        ResultActions wrongUnit = as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations"),
                "{\"propertyId\":\"%s\",\"residentUserId\":\"%s\"}".formatted(a.neighbourHome().getId(), a.owner().getId())));
        wrongUnit.andExpect(status().isConflict());
        assertThat(code(wrongUnit)).contains("CHAT_RESIDENT_UNAVAILABLE");
    }

    // ── asignacion ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Asignacion concurrente: dos porteros toman a la vez; exactamente uno la obtiene")
    void concurrentClaim() throws Exception {
        String id = residentWrites(a.owner(), "¿Llegó mi paquete?");
        String t1 = fixture.bearer(a.guard());
        String t2 = fixture.bearer(guard2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (String token : List.of(t1, t2)) {
                calls.add(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/chat/inbox/conversations/%s/claim".formatted(id))
                                    .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                            .andReturn().getResponse().getStatus();
                });
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> c : calls) futures.add(pool.submit(c));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) statuses.add(f.get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        JsonNode c = conversation(a.guard(), id);
        assertThat(c.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(c.get("assignedToUserId").asText()).isIn(a.guard().getId().toString(), guard2.getId().toString());
        assertThat(eventTypes(a.guard(), id).stream().filter("CLAIMED"::equals)).hasSize(1);
    }

    @Test
    @DisplayName("Responder toma la conversacion; solo el responsable responde, libera o transfiere; los mensajes conservan su autor")
    void assignmentTransferRelease() throws Exception {
        String id = residentWrites(a.owner(), "Necesito ayuda");

        // Responder una PENDING la toma.
        staffSends(a.guard(), a, id, "Hola, te atiende portería").andExpect(status().isCreated());
        JsonNode c = conversation(guard2, id);
        assertThat(c.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(c.get("assignedToName").asText()).isEqualTo(a.guard().getFullName());

        // Otro portero ve quien la atiende pero no puede responder, tomarla, liberarla ni transferirla.
        ResultActions other = staffSends(guard2, a, id, "Intento del otro portero");
        other.andExpect(status().isConflict());
        assertThat(code(other)).contains("CHAT_ASSIGNED_TO_OTHER");
        as(guard2, a, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(id))).andExpect(status().isConflict());
        as(guard2, a, post("/api/v1/chat/inbox/conversations/%s/release".formatted(id))).andExpect(status().isConflict());
        as(guard2, a, post("/api/v1/chat/inbox/conversations/%s/close".formatted(id))).andExpect(status().isConflict());

        // Transferir: solo a otro portero ACTIVO de la comunidad.
        as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations/%s/transfer".formatted(id)),
                "{\"toUserId\":\"%s\"}".formatted(a.owner().getId()))).andExpect(status().isConflict());
        as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations/%s/transfer".formatted(id)),
                "{\"toUserId\":\"%s\"}".formatted(b.guard().getId()))).andExpect(status().isConflict());
        as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations/%s/transfer".formatted(id)),
                "{\"toUserId\":\"%s\"}".formatted(guard2.getId()))).andExpect(status().isOk());
        assertThat(conversation(a.guard(), id).get("assignedToUserId").asText()).isEqualTo(guard2.getId().toString());
        staffSends(a.guard(), a, id, "Ya no soy responsable").andExpect(status().isConflict());
        staffSends(guard2, a, id, "Continúo yo").andExpect(status().isCreated());

        // Liberar: vuelve a PENDING sin responsable.
        JsonNode released = data(as(guard2, a, post("/api/v1/chat/inbox/conversations/%s/release".formatted(id)))
                .andExpect(status().isOk()));
        assertThat(released.get("status").asText()).isEqualTo("PENDING");
        assertThat(released.get("assignedToUserId").isNull()).isTrue();

        assertThat(eventTypes(a.guard(), id)).containsExactly("CREATED", "CLAIMED", "TRANSFERRED", "RELEASED");

        // Cada mensaje de porteria identifica a su portero aunque cambie el responsable.
        JsonNode msgs = data(as(a.guard(), a, get("/api/v1/chat/inbox/conversations/%s/messages".formatted(id)))).get("messages");
        assertThat(msgs.get(1).get("authorName").asText()).isEqualTo(a.guard().getFullName());
        assertThat(msgs.get(2).get("authorName").asText()).isEqualTo(guard2.getFullName());

        // El residente ve el NOMBRE de cada portero que le escribio, pero nunca su id de usuario.
        JsonNode residentMsgs = data(as(a.owner(), a, get("/api/v1/chat/me/conversations/%s/messages".formatted(id)))).get("messages");
        assertThat(residentMsgs.get(1).get("authorName").asText()).isEqualTo(a.guard().getFullName());
        assertThat(residentMsgs.get(2).get("authorName").asText()).isEqualTo(guard2.getFullName());
        assertThat(residentMsgs.get(1).get("authorUserId").isNull()).isTrue();
        String raw = as(a.owner(), a, get("/api/v1/chat/me/conversations/%s/messages".formatted(id)))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain(a.guard().getId().toString()).doesNotContain(guard2.getId().toString());
    }

    @Test
    @DisplayName("Cierre y reapertura: un mensaje del residente reabre como PENDING; uno del portero, como ACTIVE suyo")
    void closeAndReopen() throws Exception {
        String id = residentWrites(a.owner(), "Primera consulta");
        as(a.guard(), a, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(id))).andExpect(status().isOk());
        JsonNode closed = data(as(a.guard(), a, post("/api/v1/chat/inbox/conversations/%s/close".formatted(id)))
                .andExpect(status().isOk()));
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("assignedToUserId").isNull()).isTrue();
        // Cerrar dos veces es idempotente; tomar una cerrada no.
        as(a.guard(), a, post("/api/v1/chat/inbox/conversations/%s/close".formatted(id))).andExpect(status().isOk());
        as(a.guard(), a, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(id))).andExpect(status().isConflict());

        // El residente escribe mientras estaba cerrada: no se pierde; la reabre pendiente.
        residentWrites(a.owner(), "Otra consulta");
        assertThat(conversation(a.guard(), id).get("status").asText()).isEqualTo("PENDING");

        as(guard2, a, post("/api/v1/chat/inbox/conversations/%s/close".formatted(id))).andExpect(status().isOk());
        staffSends(guard2, a, id, "Te escribo desde portería").andExpect(status().isCreated());
        JsonNode c = conversation(a.guard(), id);
        assertThat(c.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(c.get("assignedToUserId").asText()).isEqualTo(guard2.getId().toString());

        assertThat(eventTypes(a.guard(), id)).containsExactly(
                "CREATED", "CLAIMED", "CLOSED", "REOPENED", "CLOSED", "REOPENED", "CLAIMED");
    }

    @Test
    @DisplayName("Porteria inicia la conversacion con un residente de una unidad: queda asignada a quien la inicia")
    void staffStartsConversation() throws Exception {
        JsonNode dir = data(as(a.guard(), a, get("/api/v1/chat/inbox/residents").param("q", a.neighbourHome().getNumber()))
                .andExpect(status().isOk()));
        assertThat(dir).anySatisfy(o -> assertThat(o.get("residentUserId").asText()).isEqualTo(a.neighbour().getId().toString()));
        String raw = as(a.guard(), a, get("/api/v1/chat/inbox/residents")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain(b.owner().getFullName());

        JsonNode started = data(as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations"),
                "{\"propertyId\":\"%s\",\"residentUserId\":\"%s\"}".formatted(a.neighbourHome().getId(), a.neighbour().getId())))
                .andExpect(status().isOk()));
        String id = started.get("id").asText();
        assertThat(started.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(started.get("assignedToUserId").asText()).isEqualTo(a.guard().getId().toString());

        // Otro portero que intenta iniciar la misma: recibe la existente, atendida por el primero.
        JsonNode again = data(as(guard2, a, json(post("/api/v1/chat/inbox/conversations"),
                "{\"propertyId\":\"%s\",\"residentUserId\":\"%s\"}".formatted(a.neighbourHome().getId(), a.neighbour().getId())))
                .andExpect(status().isOk()));
        assertThat(again.get("id").asText()).isEqualTo(id);
        assertThat(again.get("assignedToUserId").asText()).isEqualTo(a.guard().getId().toString());

        staffSends(a.guard(), a, id, "Buenas tardes, tiene un domicilio en portería").andExpect(status().isCreated());
        JsonNode mine = data(as(a.neighbour(), a, get("/api/v1/chat/me/conversations")));
        assertThat(findById(mine, id).get("unread").asInt()).isEqualTo(1);

        JsonNode agents = data(as(a.guard(), a, get("/api/v1/chat/inbox/agents")).andExpect(status().isOk()));
        assertThat(agents).extracting(n -> n.get("userId").asText())
                .contains(a.guard().getId().toString(), guard2.getId().toString())
                .doesNotContain(b.guard().getId().toString(), a.admin().getId().toString());
    }

    // ── mensajes ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Paginacion del historial (before) y recuperacion tras desconexion (after), en orden por seq")
    void paginationAndRecovery() throws Exception {
        String id = residentWrites(a.owner(), "m1");
        for (int i = 2; i <= 20; i++) residentWrites(a.owner(), "m" + i);
        for (int i = 21; i <= 35; i++) staffSends(a.guard(), a, id, "m" + i).andExpect(status().isCreated());

        JsonNode last = data(as(a.owner(), a, get("/api/v1/chat/me/conversations/%s/messages".formatted(id))));
        assertThat(last.get("messages")).hasSize(30);
        assertThat(last.get("hasMore").asBoolean()).isTrue();
        assertThat(last.get("messages").get(0).get("seq").asLong()).isEqualTo(6);
        assertThat(last.get("messages").get(29).get("seq").asLong()).isEqualTo(35);

        JsonNode older = data(as(a.owner(), a, get("/api/v1/chat/me/conversations/%s/messages".formatted(id)).param("before", "6")));
        assertThat(older.get("messages")).extracting(n -> n.get("seq").asLong()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(older.get("hasMore").asBoolean()).isFalse();

        JsonNode missed = data(as(a.guard(), a, get("/api/v1/chat/inbox/conversations/%s/messages".formatted(id))
                .param("after", "30")));
        assertThat(missed.get("messages")).extracting(n -> n.get("body").asText()).containsExactly("m31", "m32", "m33", "m34", "m35");

        JsonNode capped = data(as(a.guard(), a, get("/api/v1/chat/inbox/conversations/%s/messages".formatted(id))
                .param("size", "5000")));
        assertThat(capped.get("messages").size()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("Idempotencia: reintentar con el mismo clientMessageId no duplica el mensaje")
    void duplicateMessages() throws Exception {
        UUID clientId = UUID.randomUUID();
        JsonNode first = data(as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("Solo una vez", clientId)))
                .andExpect(status().isCreated()));
        JsonNode retry = data(as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("Solo una vez", clientId)))
                .andExpect(status().isOk()));
        assertThat(retry.get("duplicate").asBoolean()).isTrue();
        assertThat(retry.get("message").get("id").asText()).isEqualTo(first.get("message").get("id").asText());
        assertThat(retry.get("message").get("clientMessageId").asText()).isEqualTo(clientId.toString());
        String id = first.get("conversation").get("id").asText();

        UUID staffClientId = UUID.randomUUID();
        as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations/%s/messages".formatted(id)), bodyJson("Recibido", staffClientId)))
                .andExpect(status().isCreated());
        as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations/%s/messages".formatted(id)), bodyJson("Recibido", staffClientId)))
                .andExpect(status().isOk());

        JsonNode msgs = data(as(a.guard(), a, get("/api/v1/chat/inbox/conversations/%s/messages".formatted(id)))).get("messages");
        assertThat(msgs).hasSize(2);
        assertThat(conversation(a.guard(), id).get("lastMessageSeq").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("No leidos: suben con los mensajes del otro lado; en porteria solo los apaga la lectura del responsable")
    void unreadCounters() throws Exception {
        String id = residentWrites(a.owner(), "uno");
        residentWrites(a.owner(), "dos");
        assertThat(conversation(a.guard(), id).get("unread").asInt()).isEqualTo(2);

        // Un portero que solo mira una PENDING no apaga el aviso de la bandeja compartida.
        as(guard2, a, json(post("/api/v1/chat/inbox/conversations/%s/read".formatted(id)), "{\"upToSeq\":2}")).andExpect(status().isOk());
        assertThat(conversation(a.guard(), id).get("unread").asInt()).isEqualTo(2);

        as(a.guard(), a, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(id))).andExpect(status().isOk());
        JsonNode read = data(as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations/%s/read".formatted(id)), "{\"upToSeq\":2}")));
        assertThat(read.get("unread").asInt()).isZero();

        staffSends(a.guard(), a, id, "tres").andExpect(status().isCreated());
        staffSends(a.guard(), a, id, "cuatro").andExpect(status().isCreated());
        assertThat(findById(data(as(a.owner(), a, get("/api/v1/chat/me/conversations"))), id).get("unread").asInt()).isEqualTo(2);
        JsonNode partial = data(as(a.owner(), a, json(post("/api/v1/chat/me/conversations/%s/read".formatted(id)), "{\"upToSeq\":3}")));
        assertThat(partial.get("unread").asInt()).isEqualTo(1);
        JsonNode all = data(as(a.owner(), a, json(post("/api/v1/chat/me/conversations/%s/read".formatted(id)), "{\"upToSeq\":999}")));
        assertThat(all.get("unread").asInt()).isZero();
        assertThat(all.get("readSeq").asLong()).isEqualTo(4); // nunca por encima del ultimo mensaje
    }

    @Test
    @DisplayName("Validaciones: vacio, demasiado largo, texto con HTML tal cual (sin interpretarlo) y limite de frecuencia")
    void validationAndRateLimit() throws Exception {
        as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("   ", null))).andExpect(status().isBadRequest());
        as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("x".repeat(1001), null))).andExpect(status().isBadRequest());
        as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("\u0000\u0007", null))).andExpect(status().isBadRequest());

        String html = "<img src=x onerror=alert(1)> hola";
        JsonNode sent = data(as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson(html, null))).andExpect(status().isCreated()));
        assertThat(sent.get("message").get("body").asText()).isEqualTo(html);

        // 30 por minuto y cuenta: este ya fue el 1.º; 29 mas pasan y el siguiente es 429.
        for (int i = 0; i < 29; i++) {
            as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("spam " + i, null))).andExpect(status().isCreated());
        }
        ResultActions limited = as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("uno más", null)));
        limited.andExpect(status().isTooManyRequests());
        assertThat(code(limited)).contains("CHAT_RATE_LIMITED");
    }

    // ── accesos revocados ────────────────────────────────────────────────────

    @Test
    @DisplayName("Residente que pierde la unidad: deja de ver el hilo y porteria no puede escribirle")
    void residentLosesAccess() throws Exception {
        String id = residentWrites(a.owner(), "Antes de mudarme");
        var resident = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        resident.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(resident);

        // Sin unidades activas, la seguridad existente ya invalida la sesion del residente (401); si una peticion
        // llegara al chat, no veria el hilo.
        ResultActions list = as(a.owner(), a, get("/api/v1/chat/me/conversations"));
        int listStatus = list.andReturn().getResponse().getStatus();
        assertThat(listStatus).as("estado de la lista tras perder la unidad").isIn(200, 401, 403);
        if (listStatus == 200) assertThat(findById(data(list), id)).isNull();
        int read = as(a.owner(), a, get("/api/v1/chat/me/conversations/%s/messages".formatted(id)))
                .andReturn().getResponse().getStatus();
        assertThat(read).isIn(401, 403, 404);
        int send = as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("¿sigo aquí?", null)))
                .andReturn().getResponse().getStatus();
        assertThat(send).isIn(401, 403, 404, 409);

        ResultActions staff = staffSends(a.guard(), a, id, "¿Sigue ahí?");
        staff.andExpect(status().isConflict());
        assertThat(code(staff)).contains("CHAT_RESIDENT_UNAVAILABLE");
        // El historial sigue disponible para porteria (trazabilidad).
        assertThat(data(as(a.guard(), a, get("/api/v1/chat/inbox/conversations/%s/messages".formatted(id)))).get("messages")).hasSize(1);
    }

    @Test
    @DisplayName("Residente con dos unidades que pierde una: conserva la sesion pero ya no ve el hilo de esa unidad")
    void residentLosesOneOfTwoUnits() throws Exception {
        String id = residentWrites(a.owner(), "Consulta de mi primera unidad");
        var second = com.havita.modules.resident.domain.Resident.builder()
                .property(a.vacant()).user(a.owner()).name(a.owner().getFullName()).type("Arrendatario")
                .email("segunda-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test").phone("3100000000")
                .documentId("DOC-" + UUID.randomUUID().toString().substring(0, 6)).status(ResidentStatus.ACTIVE).build();
        second.setTenantId(a.tenantId());
        residentRepository.save(second);

        var first = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        first.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(first);

        JsonNode list = data(as(a.owner(), a, get("/api/v1/chat/me/conversations")).andExpect(status().isOk()));
        assertThat(findById(list, id)).isNull();
        as(a.owner(), a, get("/api/v1/chat/me/conversations/%s/messages".formatted(id))).andExpect(status().isNotFound());
        as(a.owner(), a, json(post("/api/v1/chat/me/conversations/%s/read".formatted(id)), "{\"upToSeq\":1}"))
                .andExpect(status().isNotFound());
        staffSends(a.guard(), a, id, "¿Sigue en la unidad?").andExpect(status().isConflict());

        // Con su otra unidad (cabecera X-Property-Id) abre un hilo distinto.
        JsonNode other = data(as(a.owner(), a, json(post("/api/v1/chat/me/messages"), bodyJson("Desde mi otra unidad", null))
                .header("X-Property-Id", a.vacant().getId().toString())).andExpect(status().isCreated()));
        assertThat(other.get("conversation").get("id").asText()).isNotEqualTo(id);
        assertThat(other.get("conversation").get("propertyId").asText()).isEqualTo(a.vacant().getId().toString());
    }

    @Test
    @DisplayName("Portero desactivado: no opera y sus conversaciones vuelven a PENDING (AUTO_RELEASED) para el resto")
    void staffLosesAccess() throws Exception {
        String id = residentWrites(a.owner(), "Atiéndanme");
        as(a.guard(), a, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(id))).andExpect(status().isOk());
        String token = fixture.bearer(a.guard());

        new TransactionTemplate(txManager).executeWithoutResult(s -> membershipRepository.findByUserId(a.guard().getId())
                .forEach(m -> {
                    m.setStatus(MembershipStatus.INACTIVE);
                    membershipRepository.save(m);
                }));

        int inbox = mvc.perform(get("/api/v1/chat/inbox/conversations")
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andReturn().getResponse().getStatus();
        assertThat(inbox).isIn(401, 403);
        int reply = mvc.perform(post("/api/v1/chat/inbox/conversations/%s/messages".formatted(id))
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(bodyJson("sigo aquí", null)))
                .andReturn().getResponse().getStatus();
        assertThat(reply).isIn(401, 403);

        JsonNode c = conversation(guard2, id);
        assertThat(c.get("status").asText()).isEqualTo("PENDING");
        assertThat(c.get("assignedToUserId").isNull()).isTrue();
        assertThat(eventTypes(guard2, id)).endsWith("AUTO_RELEASED");
        as(guard2, a, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(id))).andExpect(status().isOk());
    }

    // ── administracion (solo consulta) ───────────────────────────────────────

    @Test
    @DisplayName("Administracion: consulta la bandeja, mensajes e historial; no toma, responde, transfiere, cierra ni inicia")
    void adminReadOnly() throws Exception {
        String id = residentWrites(a.owner(), "Consulta para portería");
        staffSends(a.guard(), a, id, "Atendiendo").andExpect(status().isCreated());

        JsonNode inbox = data(as(a.admin(), a, get("/api/v1/chat/inbox/conversations")).andExpect(status().isOk()));
        JsonNode row = findById(inbox.get("content"), id);
        assertThat(row).isNotNull();
        assertThat(row.get("assignedToName").asText()).isEqualTo(a.guard().getFullName());
        as(a.admin(), a, get("/api/v1/chat/inbox/conversations/%s".formatted(id))).andExpect(status().isOk());
        assertThat(data(as(a.admin(), a, get("/api/v1/chat/inbox/conversations/%s/messages".formatted(id)))).get("messages")).hasSize(2);
        assertThat(data(as(a.admin(), a, get("/api/v1/chat/inbox/conversations/%s/events".formatted(id))))).isNotEmpty();

        as(a.admin(), a, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(id))).andExpect(status().isForbidden());
        as(a.admin(), a, post("/api/v1/chat/inbox/conversations/%s/release".formatted(id))).andExpect(status().isForbidden());
        as(a.admin(), a, post("/api/v1/chat/inbox/conversations/%s/close".formatted(id))).andExpect(status().isForbidden());
        as(a.admin(), a, json(post("/api/v1/chat/inbox/conversations/%s/messages".formatted(id)), bodyJson("x", null)))
                .andExpect(status().isForbidden());
        as(a.admin(), a, json(post("/api/v1/chat/inbox/conversations/%s/transfer".formatted(id)),
                "{\"toUserId\":\"%s\"}".formatted(guard2.getId()))).andExpect(status().isForbidden());
        as(a.admin(), a, json(post("/api/v1/chat/inbox/conversations/%s/read".formatted(id)), "{\"upToSeq\":2}"))
                .andExpect(status().isForbidden());
        as(a.admin(), a, json(post("/api/v1/chat/inbox/conversations"),
                "{\"propertyId\":\"%s\",\"residentUserId\":\"%s\"}".formatted(a.home().getId(), a.owner().getId())))
                .andExpect(status().isForbidden());
        // Nada cambio: sigue asignada al mismo portero y con 2 mensajes.
        JsonNode c = conversation(a.guard(), id);
        assertThat(c.get("assignedToUserId").asText()).isEqualTo(a.guard().getId().toString());
        assertThat(c.get("lastMessageSeq").asLong()).isEqualTo(2);
    }

    // ── responsable sin responder ────────────────────────────────────────────

    @Test
    @DisplayName("Responsable sin responder: a los 5 minutos de un mensaje del residente sin respuesta vuelve a PENDING")
    void inactivityRelease() throws Exception {
        String waiting = residentWrites(a.owner(), "¿Me ayudan?");
        as(a.guard(), a, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(waiting))).andExpect(status().isOk());

        // Con el plazo aun vigente no se libera.
        assertThat(chatService.releaseInactive(java.time.OffsetDateTime.now().minusMinutes(5))).isZero();
        assertThat(conversation(a.guard(), waiting).get("status").asText()).isEqualTo("ACTIVE");

        // Vencido el plazo (simulado con un corte en el futuro): se libera y queda auditado.
        applicationEvents.clear();
        assertThat(chatService.releaseInactive(java.time.OffsetDateTime.now().plusMinutes(1))).isGreaterThanOrEqualTo(1);
        JsonNode c = conversation(guard2, waiting);
        assertThat(c.get("status").asText()).isEqualTo("PENDING");
        assertThat(c.get("assignedToUserId").isNull()).isTrue();
        assertThat(eventTypes(guard2, waiting)).endsWith("AUTO_RELEASED");
        assertThat(pushes()).anySatisfy(p -> assertThat(p.userIds()).contains(a.guard().getId(), guard2.getId()));
        as(guard2, a, post("/api/v1/chat/inbox/conversations/%s/claim".formatted(waiting))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Responsable sin responder: una conversacion ya respondida (ultimo mensaje del portero) no se libera")
    void answeredConversationIsNotReleased() throws Exception {
        String answered = residentWrites(a.neighbour(), "Consulta del vecino");
        staffSends(a.guard(), a, answered, "Ya le respondo: listo").andExpect(status().isCreated());
        chatService.releaseInactive(java.time.OffsetDateTime.now().plusMinutes(1));
        JsonNode c = conversation(a.guard(), answered);
        assertThat(c.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(c.get("assignedToUserId").asText()).isEqualTo(a.guard().getId().toString());
    }

    // ── chat anterior retirado ───────────────────────────────────────────────

    @Test
    @DisplayName("El chat anterior por unidad (/api/v1/realtime/chat/...) ya no existe")
    void legacyChatRemoved() throws Exception {
        int read = as(a.guard(), a, get("/api/v1/realtime/chat/properties/" + a.home().getId())).andReturn().getResponse().getStatus();
        int write = as(a.owner(), a, json(post("/api/v1/realtime/chat/properties/" + a.home().getId()),
                "{\"content\":\"hola\"}")).andReturn().getResponse().getStatus();
        assertThat(read).isIn(404, 405);
        assertThat(write).isIn(404, 405);
    }

    // ── tiempo real y notificaciones ─────────────────────────────────────────

    @Test
    @DisplayName("SSE: el mensaje llega a la porteria de la comunidad y al residente; no a otros residentes, la administracion ni otra comunidad")
    void realtimeAudience() throws Exception {
        MvcResult guard = openStream(a.guard(), a);
        MvcResult owner = openStream(a.owner(), a);
        MvcResult neighbour = openStream(a.neighbour(), a);
        MvcResult admin = openStream(a.admin(), a);
        MvcResult otherGuard = openStream(b.guard(), b);

        String id = residentWrites(a.owner(), "Texto confidencial del residente");
        List<JsonNode> toGuard = sseEvents(guard, ChatNotifier.EVENT_MESSAGE);
        assertThat(toGuard).hasSize(1);
        assertThat(toGuard.get(0).get("payload").get("message").get("body").asText()).isEqualTo("Texto confidencial del residente");
        assertThat(toGuard.get(0).get("payload").get("conversation").get("id").asText()).isEqualTo(id);
        assertThat(sseEvents(owner, ChatNotifier.EVENT_MESSAGE)).hasSize(1);
        // La administracion de la comunidad consulta la bandeja: tambien la recibe en tiempo real.
        assertThat(sseEvents(admin, ChatNotifier.EVENT_MESSAGE)).hasSize(1);
        for (MvcResult s : List.of(neighbour, otherGuard)) {
            assertThat(s.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("Texto confidencial").doesNotContain("CHAT_");
        }

        staffSends(a.guard(), a, id, "Respuesta de portería").andExpect(status().isCreated());
        List<JsonNode> toOwner = sseEvents(owner, ChatNotifier.EVENT_MESSAGE);
        assertThat(toOwner).hasSize(2);
        JsonNode reply = toOwner.get(1).get("payload");
        // El residente ve el nombre del portero que le atiende, sin su id.
        assertThat(reply.get("message").get("authorName").asText()).isEqualTo(a.guard().getFullName());
        assertThat(reply.get("message").get("authorUserId").isNull()).isTrue();
        assertThat(reply.get("conversation").get("assignedToName").asText()).isEqualTo(a.guard().getFullName());
        assertThat(reply.get("conversation").get("assignedToUserId").isNull()).isTrue();
        assertThat(owner.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(a.guard().getId().toString());
        assertThat(neighbour.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("Respuesta de portería");

        // Asignaciones y cierres tambien en tiempo real para la porteria.
        as(a.guard(), a, post("/api/v1/chat/inbox/conversations/%s/close".formatted(id))).andExpect(status().isOk());
        assertThat(sseEvents(guard, ChatNotifier.EVENT_CONVERSATION))
                .anySatisfy(e -> assertThat(e.get("payload").get("conversation").get("status").asText()).isEqualTo("CLOSED"));
    }

    @Test
    @DisplayName("Push: a la porteria de la comunidad al primer no leido; al residente por CADA mensaje de porteria; sin el texto")
    void pushTargeting() throws Exception {
        String id = residentWrites(a.owner(), "Mensaje con datos privados");
        residentWrites(a.owner(), "Segundo mensaje");

        List<ChatNotifier.ChatPushRequested> staffPushes = pushes();
        assertThat(staffPushes).hasSize(1); // el segundo mensaje no repite el aviso (ya habia no leidos)
        assertThat(staffPushes.get(0).userIds())
                .contains(a.guard().getId(), guard2.getId())
                .doesNotContain(b.guard().getId(), a.admin().getId(), a.owner().getId(), a.neighbour().getId());
        assertThat(staffPushes.get(0).text()).doesNotContain("privados").doesNotContain(a.owner().getFullName());
        assertThat(staffPushes.get(0).tenantId()).isEqualTo(a.tenantId());

        applicationEvents.clear();
        staffSends(a.guard(), a, id, "Respuesta").andExpect(status().isCreated());
        staffSends(a.guard(), a, id, "Otra respuesta").andExpect(status().isCreated());
        List<ChatNotifier.ChatPushRequested> residentPushes = pushes();
        assertThat(residentPushes).hasSize(2); // un aviso por cada mensaje de porteria
        for (ChatNotifier.ChatPushRequested p : residentPushes) {
            assertThat(p.userIds()).containsExactly(a.owner().getId());
            assertThat(p.text()).doesNotContain("Respuesta").doesNotContain(a.guard().getFullName());
            assertThat(p.url()).isEqualTo("/resident/chat?c=" + id);
        }

        // Con la conversacion ACTIVE, el siguiente aviso de un mensaje del residente va solo al responsable.
        as(a.owner(), a, json(post("/api/v1/chat/me/conversations/%s/read".formatted(id)), "{\"upToSeq\":99}")).andExpect(status().isOk());
        as(a.guard(), a, json(post("/api/v1/chat/inbox/conversations/%s/read".formatted(id)), "{\"upToSeq\":99}")).andExpect(status().isOk());
        applicationEvents.clear();
        residentWrites(a.owner(), "Gracias");
        assertThat(pushes()).hasSize(1);
        assertThat(pushes().get(0).userIds()).containsExactly(a.guard().getId());
    }
}
