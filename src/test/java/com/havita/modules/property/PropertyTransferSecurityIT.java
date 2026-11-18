package com.havita.modules.property;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.audit.domain.AuditEvent;
import com.havita.modules.audit.infrastructure.AuditEventRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.PropertyStatus;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.InvitationStatus;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentInvitation;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentInvitationRepository;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * SEC-8: transferir la titularidad de una unidad y consultar su historial son operaciones de la administracion de
 * esa comunidad. Ningun otro rol (residente, propietario sin privilegios, porteria) ni otra comunidad puede hacerlo,
 * y una transferencia rechazada no modifica nada.
 */
class PropertyTransferSecurityIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Clave-Segura-2027";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private ResidentInvitationRepository invitationRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return withToken(fixture.bearer(user), side, req);
    }

    private ResultActions withToken(String token, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", token).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private static String email() {
        return "nuevo-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private static String body(String recipient) {
        return "{\"newOwnerName\":\"Nuevo Propietario\",%s\"newOwnerDocumentId\":\"900100200\",\"effectiveDate\":\"%s\"}"
                .formatted(recipient, OffsetDateTime.now());
    }

    private static String byEmail(String email) {
        return body("\"newOwnerEmail\":\"" + email + "\",");
    }

    private static String byAccount(User user) {
        return body("\"newOwnerUserId\":\"" + user.getId() + "\",");
    }

    private MockHttpServletRequestBuilder transfer(UUID propertyId, String json) {
        return post("/api/v1/properties/{id}/transfer-owner", propertyId).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private int status(ResultActions r) {
        return r.andReturn().getResponse().getStatus();
    }

    private JsonNode data(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static String tokenOf(String link) {
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private List<Resident> owners(Side side) {
        return residentRepository.findByTenantIdAndPropertyId(side.tenantId(), side.home().getId()).stream()
                .filter(r -> r.getStatus() == ResidentStatus.ACTIVE && "Propietario".equalsIgnoreCase(r.getType())).toList();
    }

    /** Instantanea de lo que una transferencia toca: relaciones de la unidad, invitaciones y estado de la unidad. */
    private String snapshot(Side side) {
        StringBuilder sb = new StringBuilder();
        residentRepository.findByTenantIdAndPropertyId(side.tenantId(), side.home().getId()).stream()
                .sorted(java.util.Comparator.comparing(r -> r.getId().toString()))
                .forEach(r -> sb.append(r.getId()).append(r.getType()).append(r.getStatus()).append(r.getEndedAt())
                        .append(r.getEndReason()).append(r.getUser() != null ? r.getUser().getId() : null).append('|'));
        invitationRepository.findByTenantIdAndPropertyId(side.tenantId(), side.home().getId()).stream()
                .sorted(java.util.Comparator.comparing(i -> i.getId().toString()))
                .forEach(i -> sb.append(i.getId()).append(i.getStatus()).append('|'));
        sb.append(propertyRepository.findById(side.home().getId()).orElseThrow().getStatus());
        return sb.toString();
    }

    private void assertRejectedWithoutChanges(ResultActions call, int... allowed) throws Exception {
        String before = snapshot(a);
        int code = status(call);
        assertThat(code).isIn(java.util.Arrays.stream(allowed).boxed().toArray());
        assertThat(snapshot(a)).as("una transferencia rechazada no modifica nada").isEqualTo(before);
    }

    private List<AuditEvent> audit(Side side, String action) {
        return auditEventRepository.findByTenantId(side.tenantId(), org.springframework.data.domain.Pageable.unpaged()).getContent()
                .stream().filter(e -> action.equals(e.getAction())).toList();
    }

    // ── Autorizacion de la transferencia ──────────────────────────────────────

    @Test
    @DisplayName("un RESIDENT de otra unidad no puede transferirse la unidad (403, sin cambios)")
    void residentCannotTransfer() throws Exception {
        String before = snapshot(a);
        assertThat(status(as(a.neighbour(), a, transfer(a.home().getId(), byAccount(a.neighbour()))))).isEqualTo(403);
        assertThat(snapshot(a)).isEqualTo(before);
        assertThat(owners(a)).extracting(Resident::getId).containsExactly(a.ownerResident().getId());
    }

    @Test
    @DisplayName("el propietario sin privilegios administrativos no puede transferir su unidad")
    void ownerCannotTransfer() throws Exception {
        String before = snapshot(a);
        assertThat(status(as(a.owner(), a, transfer(a.home().getId(), byEmail(email()))))).isEqualTo(403);
        assertThat(snapshot(a)).isEqualTo(before);
    }

    @Test
    @DisplayName("porteria (SECURITY) no puede transferir unidades")
    void securityCannotTransfer() throws Exception {
        String before = snapshot(a);
        assertThat(status(as(a.guard(), a, transfer(a.home().getId(), byAccount(a.neighbour()))))).isEqualTo(403);
        assertThat(snapshot(a)).isEqualTo(before);
    }

    @Test
    @DisplayName("el administrador de la comunidad B no puede transferir unidades de A, ni con su tenant ni falsificando el de A")
    void otherCommunityAdminCannotTransfer() throws Exception {
        String before = snapshot(a);
        assertThat(status(as(b.admin(), b, transfer(a.home().getId(), byEmail(email()))))).isEqualTo(404);
        assertThat(status(as(b.admin(), a, transfer(a.home().getId(), byEmail(email()))))).isIn(401, 403);
        assertThat(snapshot(a)).isEqualTo(before);
    }

    @Test
    @DisplayName("un administrador degradado en la base no transfiere aunque su JWT aun diga COMMUNITY_ADMIN")
    void demotedAdminWithStaleTokenIsRejected() throws Exception {
        String staleToken = fixture.bearer(a.admin());
        Membership m = membershipRepository.findByUserIdAndTenantId(a.admin().getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);

        String before = snapshot(a);
        // SEC-9: el filtro ya no reconoce la membresia INACTIVE (401, anonimo); antes lo rechazaba el servicio (403).
        assertThat(status(withToken(staleToken, a, transfer(a.home().getId(), byEmail(email()))))).isIn(401, 403);
        assertThat(snapshot(a)).isEqualTo(before);
    }

    // ── Destinatario ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("exige exactamente un destinatario: sin ninguno, con ambos o con un correo invalido se rechaza sin cambios")
    void exactlyOneRecipient() throws Exception {
        String before = snapshot(a);
        assertThat(status(as(a.admin(), a, transfer(a.home().getId(), body(""))))).isEqualTo(400);
        assertThat(status(as(a.admin(), a, transfer(a.home().getId(), body("\"newOwnerEmail\":\"  \","))))).isEqualTo(400);
        assertThat(status(as(a.admin(), a, transfer(a.home().getId(),
                body("\"newOwnerUserId\":\"" + a.neighbour().getId() + "\",\"newOwnerEmail\":\"" + email() + "\","))))).isEqualTo(400);
        assertThat(status(as(a.admin(), a, transfer(a.home().getId(), byEmail("no-es-un-correo"))))).isEqualTo(400);
        assertThat(snapshot(a)).isEqualTo(before);
    }

    @Test
    @DisplayName("transferencia legitima por correo: cambia la titularidad, registra quien la hizo y emite una invitacion ligada")
    void legitimateTransferByEmail() throws Exception {
        String recipient = email();
        JsonNode res = data(as(a.admin(), a, transfer(a.home().getId(), byEmail(recipient))));

        Resident previous = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        assertThat(previous.getStatus()).isEqualTo(ResidentStatus.INACTIVE);
        assertThat(previous.getEndedBy()).isNotNull();
        assertThat(previous.getEndedBy().getId()).isEqualTo(a.admin().getId());
        List<Resident> owners = owners(a);
        assertThat(owners).hasSize(1);
        assertThat(owners.get(0).getEmail()).isEqualTo(recipient);
        assertThat(owners.get(0).getUser()).isNull();

        String token = tokenOf(res.get("inviteLink").asText());
        ResidentInvitation inv = invitationRepository.findByTokenHash(sha256(token)).orElseThrow();
        assertThat(inv.getResident().getId()).isEqualTo(owners.get(0).getId());
        assertThat(inv.getInviteeEmail()).isEqualTo(recipient);
        assertThat(propertyRepository.findById(a.home().getId()).orElseThrow().getStatus()).isEqualTo(PropertyStatus.OCCUPIED);

        List<AuditEvent> events = audit(a, "PROPERTY_OWNERSHIP_TRANSFERRED");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getDetails()).contains("propertyId=" + a.home().getId()).contains("method=INVITATION")
                .contains(a.ownerResident().getId().toString()).doesNotContain(token).doesNotContain(sha256(token));
    }

    @Test
    @DisplayName("transferencia a una cuenta existente: vinculacion directa auditada, sin enlace ni invitacion")
    void legitimateTransferToExistingAccount() throws Exception {
        int invitationsBefore = invitationRepository.findByTenantIdAndPropertyId(a.tenantId(), a.home().getId()).size();
        JsonNode res = data(as(a.admin(), a, transfer(a.home().getId(), byAccount(a.neighbour()))));

        assertThat(res.get("inviteLink").isNull()).isTrue();
        assertThat(res.get("linkedUserId").asText()).isEqualTo(a.neighbour().getId().toString());
        List<Resident> owners = owners(a);
        assertThat(owners).hasSize(1);
        assertThat(owners.get(0).getUser().getId()).isEqualTo(a.neighbour().getId());
        assertThat(invitationRepository.findByTenantIdAndPropertyId(a.tenantId(), a.home().getId())).hasSize(invitationsBefore);
        assertThat(audit(a, "PROPERTY_OWNERSHIP_TRANSFERRED")).singleElement()
                .satisfies(e -> assertThat(e.getDetails()).contains("method=ACCOUNT").contains("linkedUserId=" + a.neighbour().getId()));
    }

    @Test
    @DisplayName("una cuenta de otra comunidad no puede recibir la unidad (404, sin cambios)")
    void foreignAccountCannotReceive() throws Exception {
        String before = snapshot(a);
        assertThat(status(as(a.admin(), a, transfer(a.home().getId(), byAccount(b.owner()))))).isEqualTo(404);
        assertThat(snapshot(a)).isEqualTo(before);
    }

    // ── Concurrencia y transferencias sustituidas ─────────────────────────────

    @Test
    @DisplayName("dos transferencias simultaneas de la misma unidad dejan un unico propietario activo")
    void concurrentTransfersLeaveOneOwner() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<Integer> codes = new java.util.ArrayList<>();
        try {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int n = 0; n < 2; n++) {
                String json = byEmail(email());
                results.add(pool.submit(() -> {
                    start.await();
                    return status(as(a.admin(), a, transfer(a.home().getId(), json)));
                }));
            }
            start.countDown();
            for (var f : results) codes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(codes).containsOnly(200);
        assertThat(owners(a)).hasSize(1);
        assertThat(invitationRepository.findByTenantIdAndPropertyId(a.tenantId(), a.home().getId()))
                .filteredOn(i -> i.getStatus() == InvitationStatus.PENDING).hasSize(1);
    }

    @Test
    @DisplayName("una segunda transferencia revoca la invitacion pendiente de la primera; aceptarla no reactiva al sustituido")
    void supersededTransferCannotBeAccepted() throws Exception {
        String first = email();
        String firstToken = tokenOf(data(as(a.admin(), a, transfer(a.home().getId(), byEmail(first)))).get("inviteLink").asText());
        as(a.admin(), a, transfer(a.home().getId(), byEmail(email())));

        assertThat(invitationRepository.findByTokenHash(sha256(firstToken)).orElseThrow().getStatus()).isEqualTo(InvitationStatus.REVOKED);
        int code = status(mvc.perform(post("/api/v1/public/resident-invitations/{t}/register", firstToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Primer Destinatario\",\"email\":\"%s\",\"documentType\":\"CC\",\"documentId\":\"1020304050\",\"phone\":\"3001112233\",\"password\":\"%s\",\"acceptTerms\":true,\"acceptPrivacy\":true}"
                        .formatted(first, PASSWORD))));
        assertThat(code).isEqualTo(409);
        assertThat(owners(a)).hasSize(1);
    }

    @Test
    @DisplayName("aceptar la invitacion de traspaso solo vincula al destinatario y no toca otras relaciones")
    void acceptanceOnlyAffectsRecipient() throws Exception {
        String recipient = email();
        String token = tokenOf(data(as(a.admin(), a, transfer(a.home().getId(), byEmail(recipient)))).get("inviteLink").asText());
        String others = residentRepository.findByTenantIdAndPropertyId(a.tenantId(), a.home().getId()).stream()
                .filter(r -> !"Propietario".equalsIgnoreCase(r.getType()) || r.getStatus() != ResidentStatus.ACTIVE)
                .map(r -> r.getId() + ":" + r.getStatus()).sorted().toList().toString();

        mvc.perform(post("/api/v1/public/resident-invitations/{t}/register", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nuevo Propietario\",\"email\":\"%s\",\"documentType\":\"CC\",\"documentId\":\"900100200\",\"phone\":\"3001112233\",\"password\":\"%s\",\"acceptTerms\":true,\"acceptPrivacy\":true}"
                        .formatted(recipient, PASSWORD)));

        List<Resident> owners = owners(a);
        assertThat(owners).hasSize(1);
        assertThat(owners.get(0).getUser().getId()).isEqualTo(userRepository.findByEmailIgnoreCase(recipient).orElseThrow().getId());
        String othersAfter = residentRepository.findByTenantIdAndPropertyId(a.tenantId(), a.home().getId()).stream()
                .filter(r -> !"Propietario".equalsIgnoreCase(r.getType()) || r.getStatus() != ResidentStatus.ACTIVE)
                .map(r -> r.getId() + ":" + r.getStatus()).sorted().toList().toString();
        assertThat(othersAfter).isEqualTo(others);
    }

    // ── Regeneracion del enlace de traspaso ───────────────────────────────────

    @Test
    @DisplayName("regenerar el traspaso: solo la administracion, mismo destinatario y misma transferencia, revoca el anterior y queda auditado")
    void regenerateTransferInvitation() throws Exception {
        String recipient = email();
        String oldToken = tokenOf(data(as(a.admin(), a, transfer(a.home().getId(), byEmail(recipient)))).get("inviteLink").asText());
        ResidentInvitation original = invitationRepository.findByTokenHash(sha256(oldToken)).orElseThrow();
        int residentsBefore = residentRepository.findByTenantIdAndPropertyId(a.tenantId(), a.home().getId()).size();

        // El propietario saliente ya esta INACTIVE (la puerta A-8 lo trata como anonimo); un residente activo recibe 403.
        assertThat(status(as(a.owner(), a, post("/api/v1/resident-invitations/{id}/regenerate", original.getId())))).isIn(401, 403);
        assertThat(status(as(a.neighbour(), a, post("/api/v1/resident-invitations/{id}/regenerate", original.getId())))).isEqualTo(403);
        assertThat(status(as(a.guard(), a, post("/api/v1/resident-invitations/{id}/regenerate", original.getId())))).isEqualTo(403);
        assertThat(status(as(b.admin(), b, post("/api/v1/resident-invitations/{id}/regenerate", original.getId())))).isEqualTo(404);

        JsonNode renewed = data(as(a.admin(), a, post("/api/v1/resident-invitations/{id}/regenerate", original.getId())));
        String newToken = tokenOf(renewed.get("link").asText());
        ResidentInvitation fresh = invitationRepository.findByTokenHash(sha256(newToken)).orElseThrow();
        assertThat(fresh.getResident().getId()).isEqualTo(original.getResident().getId());
        assertThat(fresh.getInviteeEmail()).isEqualTo(recipient);
        assertThat(fresh.getInvitationType()).isEqualTo("Propietario");
        assertThat(invitationRepository.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(residentRepository.findByTenantIdAndPropertyId(a.tenantId(), a.home().getId())).hasSize(residentsBefore);
        mvc.perform(get("/api/v1/public/resident-invitations/{t}", oldToken))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(409));
        assertThat(audit(a, "TRANSFER_INVITATION_REGENERATED")).singleElement()
                .satisfies(e -> assertThat(e.getDetails()).doesNotContain(newToken).doesNotContain(oldToken));
    }

    @Test
    @DisplayName("no se regenera un traspaso ya completado por el destinatario")
    void completedTransferIsNotRegenerable() throws Exception {
        String recipient = email();
        String token = tokenOf(data(as(a.admin(), a, transfer(a.home().getId(), byEmail(recipient)))).get("inviteLink").asText());
        mvc.perform(post("/api/v1/public/resident-invitations/{t}/register", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nuevo Propietario\",\"email\":\"%s\",\"documentType\":\"CC\",\"documentId\":\"900100200\",\"phone\":\"3001112233\",\"password\":\"%s\",\"acceptTerms\":true,\"acceptPrivacy\":true}"
                        .formatted(recipient, PASSWORD)));
        ResidentInvitation used = invitationRepository.findByTokenHash(sha256(token)).orElseThrow();
        assertThat(used.getStatus()).isEqualTo(InvitationStatus.USED);
        assertThat(status(as(a.admin(), a, post("/api/v1/resident-invitations/{id}/regenerate", used.getId())))).isEqualTo(409);
    }

    // ── Historial ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("historial: solo la administracion de la comunidad; residentes (incluido el propio), porteria y otra comunidad no")
    void historyIsAdministrationOnly() throws Exception {
        String url = "/api/v1/properties/{id}/history";
        assertThat(status(as(a.neighbour(), a, get(url, a.home().getId())))).isEqualTo(403);
        assertThat(status(as(a.owner(), a, get(url, a.home().getId())))).isEqualTo(403);
        assertThat(status(as(a.guard(), a, get(url, a.home().getId())))).isEqualTo(403);
        assertThat(status(as(b.admin(), b, get(url, a.home().getId())))).isEqualTo(404);
        assertThat(status(as(b.admin(), a, get(url, a.home().getId())))).isIn(401, 403);
        // Un residente no puede usarlo para enumerar unidades: inexistente y ajena responden igual.
        assertThat(status(as(a.neighbour(), a, get(url, UUID.randomUUID())))).isEqualTo(403);

        JsonNode history = data(as(a.admin(), a, get(url, a.home().getId())));
        assertThat(history.toString()).contains(a.ownerResident().getName());
    }

    // ── Sin rutas alternativas ────────────────────────────────────────────────

    @Test
    @DisplayName("sin bypass: residentes y porteria no crean, reasignan ni modifican relaciones o estados de unidad por otras rutas")
    void noEquivalentBypass() throws Exception {
        String before = snapshot(a);
        String createOwner = "{\"name\":\"Bypass\",\"email\":\"%s\",\"phone\":\"3001112233\",\"propertyId\":\"%s\",\"type\":\"Propietario\"}"
                .formatted(email(), a.home().getId());
        String assign = "{\"propertyIds\":[\"%s\"],\"type\":\"Propietario\"}".formatted(a.home().getId());
        String updateOwner = "{\"name\":\"Bypass\",\"email\":\"%s\",\"phone\":\"3001112233\",\"propertyId\":\"%s\",\"type\":\"Propietario\",\"status\":\"INACTIVE\"}"
                .formatted(a.owner().getEmail(), a.home().getId());
        String updateProperty = "{\"number\":\"X\",\"name\":\"X\",\"status\":\"VACANT\"}";
        for (User caller : List.of(a.neighbour(), a.owner(), a.guard())) {
            assertThat(status(as(caller, a, post("/api/v1/residents").contentType(MediaType.APPLICATION_JSON).content(createOwner)))).isEqualTo(403);
            assertThat(status(as(caller, a, post("/api/v1/residents/users/{u}/properties", caller.getId())
                    .contentType(MediaType.APPLICATION_JSON).content(assign)))).isEqualTo(403);
            assertThat(status(as(caller, a, put("/api/v1/residents/{id}", a.ownerResident().getId())
                    .contentType(MediaType.APPLICATION_JSON).content(updateOwner)))).isEqualTo(403);
            assertThat(status(as(caller, a, put("/api/v1/properties/{id}", a.home().getId())
                    .contentType(MediaType.APPLICATION_JSON).content(updateProperty)))).isEqualTo(403);
            assertThat(status(as(caller, a, post("/api/v1/memberships").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"%s\",\"tenantId\":\"%s\",\"role\":\"COMMUNITY_ADMIN\"}".formatted(caller.getId(), a.tenantId()))))).isEqualTo(403);
        }
        assertThat(snapshot(a)).isEqualTo(before);
    }
}
