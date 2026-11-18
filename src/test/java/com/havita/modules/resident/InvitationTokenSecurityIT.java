package com.havita.modules.resident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.resident.application.InvitationTokens;
import com.havita.modules.resident.application.LegacyInvitationTokenMigration;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-7: los tokens de invitacion son secretos de un solo uso. No se guardan en claro, no se devuelven en listados
 * ni en logs, y conocer un token ajeno no permite asumir la identidad ni el rol de su destinatario.
 */
@ExtendWith(OutputCaptureExtension.class)
class InvitationTokenSecurityIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Clave-Segura-2027";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private ResidentInvitationRepository invitationRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private LegacyInvitationTokenMigration migration;

    private Side a;
    private Side b;
    /** Co-residente (tipo Residente) de la unidad del propietario de A, con cuenta propia. */
    private User roommate;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        roommate = account("companero");
        membershipRepository.save(Membership.builder()
                .user(roommate).tenantId(a.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        Resident rel = Resident.builder().property(a.home()).user(roommate).name(roommate.getFullName()).type("Residente")
                .email(roommate.getEmail()).status(ResidentStatus.ACTIVE).build();
        rel.setTenantId(a.tenantId());
        residentRepository.save(rel);
    }

    private User account(String handle) {
        return userRepository.save(User.builder()
                .email(handle + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash(passwordEncoder.encode(PASSWORD)).fullName("Cuenta " + handle).phone("3001112233").build());
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static String tokenOf(String link) {
        return link.substring(link.lastIndexOf('/') + 1);
    }

    static String sha256Hex(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private String adminInvitation() throws Exception {
        return tokenOf(data(as(a.admin(), a, post("/api/v1/properties/{id}/resident-invitations", a.vacant().getId())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk())).get("link").asText());
    }

    private String householdInvitation(User inviter, String type) throws Exception {
        return tokenOf(data(as(inviter, a, post("/api/v1/resident/me/invitations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"%s\",\"email\":\"\",\"name\":\"\",\"phone\":\"\",\"propertyId\":\"%s\"}"
                        .formatted(type, a.home().getId())))
                .andExpect(status().isOk())).get("link").asText());
    }

    /** Traspaso de la unidad del propietario de A a un tercero; devuelve el token que recibe la administracion. */
    private String transferTo(String newOwnerEmail) throws Exception {
        JsonNode res = data(as(a.admin(), a, post("/api/v1/properties/{id}/transfer-owner", a.home().getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newOwnerName\":\"Nuevo Propietario\",\"newOwnerEmail\":\"%s\",\"newOwnerDocumentId\":\"900100200\",\"effectiveDate\":\"%s\"}"
                        .formatted(newOwnerEmail, OffsetDateTime.now())))
                .andExpect(status().isOk()));
        return tokenOf(res.get("inviteLink").asText());
    }

    private ResultActions register(String token, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/public/resident-invitations/{token}/register", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Persona Sintetica\",\"email\":\"%s\",\"documentType\":\"CC\",\"documentId\":\"1020304050\",\"phone\":\"3001112233\",\"password\":\"%s\",\"acceptTerms\":true,\"acceptPrivacy\":true}"
                        .formatted(email, password)));
    }

    private ResultActions validate(String token) throws Exception {
        return mvc.perform(get("/api/v1/public/resident-invitations/{token}", token));
    }

    private ResidentInvitation storedFor(String token) throws Exception {
        return invitationRepository.findByTokenHash(sha256Hex(token)).orElseThrow(
                () -> new AssertionError("no hay invitacion guardada con el SHA-256 del token"));
    }

    // ── Almacenamiento ────────────────────────────────────────────────────────

    @Test
    @DisplayName("los tokens nuevos (administracion, hogar y traspaso) se guardan como SHA-256, nunca en claro")
    void newTokensAreStoredHashed() throws Exception {
        List<String> tokens = List.of(adminInvitation(), householdInvitation(a.owner(), "Arrendatario"),
                transferTo("nuevo-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test"));
        for (String token : tokens) {
            assertThat(token).hasSize(43); // 32 bytes de SecureRandom en base64url: 256 bits de entropia
            assertThat(invitationRepository.findByTokenHash(token)).as("token en claro").isEmpty();
            assertThat(storedFor(token).getTokenHash()).matches("[0-9a-f]{64}");
        }
    }

    @Test
    @DisplayName("el hash permite validar y usar una invitacion legitima")
    void hashedTokenStillValidates() throws Exception {
        String token = adminInvitation();
        validate(token).andExpect(status().isOk());
        String email = "nuevo-titular-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        register(token, email, PASSWORD).andExpect(status().isOk());
        assertThat(storedFor(token).getStatus()).isEqualTo(InvitationStatus.USED);
    }

    // ── Exposicion ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mi Hogar no entrega a un co-residente el token de una invitacion ajena (hogar, administracion o traspaso)")
    void peopleDoesNotExposeOthersTokens() throws Exception {
        String household = householdInvitation(a.owner(), "Arrendatario");
        String people = body(as(roommate, a, get("/api/v1/resident/me/people")).andExpect(status().isOk()));
        assertThat(people).doesNotContain(household);

        String transfer = transferTo("nuevo-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test");
        people = body(as(roommate, a, get("/api/v1/resident/me/people")).andExpect(status().isOk()));
        assertThat(people).doesNotContain(transfer).doesNotContain(sha256Hex(transfer));
    }

    @Test
    @DisplayName("ningun listado devuelve el token ni su hash: ni Mi Hogar del emisor ni el de administracion")
    void listingsNeverContainSecrets() throws Exception {
        String household = householdInvitation(a.owner(), "Arrendatario");
        String admin = adminInvitation();

        String ownerPeople = body(as(a.owner(), a, get("/api/v1/resident/me/people")).andExpect(status().isOk()));
        assertThat(ownerPeople).doesNotContain(household).doesNotContain(sha256Hex(household)).doesNotContain("inviteLink");
        String adminList = body(as(a.admin(), a, get("/api/v1/properties/{id}/resident-invitations", a.vacant().getId()))
                .andExpect(status().isOk()));
        assertThat(adminList).doesNotContain(admin).doesNotContain(sha256Hex(admin));
    }

    @Test
    @DisplayName("otra comunidad no ve ni gestiona las invitaciones de A")
    void noCrossTenantExposure() throws Exception {
        String household = householdInvitation(a.owner(), "Residente");
        assertThat(body(as(b.owner(), b, get("/api/v1/resident/me/people")))).doesNotContain(household);
        int code = as(b.admin(), b, get("/api/v1/properties/{id}/resident-invitations", a.home().getId()))
                .andReturn().getResponse().getStatus();
        String list = body(as(b.admin(), b, get("/api/v1/properties/{id}/resident-invitations", a.home().getId())));
        assertThat(code == 200 ? list : "").doesNotContain(household).doesNotContain("\"id\"");
    }

    @Test
    @DisplayName("un error de validacion en el registro no escribe el token en los logs")
    void tokenIsNotLogged(CapturedOutput output) throws Exception {
        String token = adminInvitation();
        mvc.perform(post("/api/v1/public/resident-invitations/{token}/register", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
        validate(token + "x").andExpect(status().isConflict());
        assertThat(output.getAll()).doesNotContain(token);
    }

    // ── Escalamiento ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("un co-residente con el enlace de traspaso no puede vincularse como propietario")
    void coResidentCannotHijackTransfer() throws Exception {
        String newOwnerEmail = "nuevo-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String transfer = transferTo(newOwnerEmail);

        // Simula que el enlace llego a manos del co-residente (hoy lo obtiene de GET /resident/me/people).
        register(transfer, roommate.getEmail(), PASSWORD).andExpect(status().isConflict());

        Resident newOwner = storedFor(transfer).getResident();
        Resident reloaded = residentRepository.findById(newOwner.getId()).orElseThrow();
        assertThat(reloaded.getUser()).isNull();
        assertThat(residentRepository.findByUserIdAndTenantId(roommate.getId(), a.tenantId()))
                .noneMatch(r -> "Propietario".equalsIgnoreCase(r.getType()));
        assertThat(storedFor(transfer).getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    @DisplayName("el destinatario legitimo del traspaso si puede registrarse y queda como propietario")
    void legitimateTransferRecipientRegisters() throws Exception {
        String newOwnerEmail = "nuevo-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String transfer = transferTo(newOwnerEmail);
        register(transfer, newOwnerEmail.toUpperCase(), PASSWORD).andExpect(status().isOk());

        Resident owner = residentRepository.findById(storedFor(transfer).getResident().getId()).orElseThrow();
        assertThat(owner.getUser()).isNotNull();
        assertThat(owner.getUser().getId()).isEqualTo(userRepository.findByEmailIgnoreCase(newOwnerEmail).orElseThrow().getId());
        assertThat(owner.getType()).isEqualTo("Propietario");
        assertThat(storedFor(transfer).getStatus()).isEqualTo(InvitationStatus.USED);
    }

    // ── Estados ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tokens revocados, vencidos, usados o inexistentes se rechazan")
    void unusableTokensAreRejected() throws Exception {
        String revoked = adminInvitation();
        as(a.admin(), a, post("/api/v1/resident-invitations/{id}/revoke", storedFor(revoked).getId())).andExpect(status().isOk());
        validate(revoked).andExpect(status().isConflict());
        register(revoked, "x-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test", PASSWORD).andExpect(status().isConflict());

        String expired = householdInvitation(a.owner(), "Residente");
        ResidentInvitation inv = storedFor(expired);
        inv.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        invitationRepository.save(inv);
        validate(expired).andExpect(status().isConflict());

        String used = householdInvitation(a.owner(), "Arrendatario");
        register(used, "inquilino-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test", PASSWORD).andExpect(status().isOk());
        register(used, "otro-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test", PASSWORD).andExpect(status().isConflict());

        validate("no-existe-" + UUID.randomUUID()).andExpect(status().isConflict());
    }

    // ── Mi Hogar: regenerar y cancelar ────────────────────────────────────────

    private JsonNode pendingEntry(User viewer, String invitationId) throws Exception {
        for (JsonNode p : data(as(viewer, a, get("/api/v1/resident/me/people")).andExpect(status().isOk()))) {
            if (invitationId.equals(p.get("id").asText())) return p;
        }
        return null;
    }

    @Test
    @DisplayName("solo el emisor regenera su invitacion de hogar: el token anterior deja de valer y el nuevo valida")
    void onlyInviterRegeneratesHouseholdInvitation() throws Exception {
        String old = householdInvitation(a.owner(), "Arrendatario");
        String id = storedFor(old).getId().toString();

        assertThat(pendingEntry(a.owner(), id).get("canRegenerate").asBoolean()).isTrue();
        assertThat(pendingEntry(roommate, id).get("canRegenerate").asBoolean()).isFalse();
        as(roommate, a, post("/api/v1/resident/me/invitations/{id}/regenerate", id)).andExpect(status().isForbidden());
        validate(old).andExpect(status().isOk());

        JsonNode renewed = data(as(a.owner(), a, post("/api/v1/resident/me/invitations/{id}/regenerate", id)).andExpect(status().isOk()));
        String fresh = tokenOf(renewed.get("link").asText());
        assertThat(fresh).isNotEqualTo(old);
        assertThat(renewed.get("invitationType").asText()).isEqualTo("Arrendatario");
        validate(old).andExpect(status().isConflict());
        validate(fresh).andExpect(status().isOk());
        assertThat(invitationRepository.findById(UUID.fromString(id)).orElseThrow().getStatus()).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    @DisplayName("cancelar: un co-residente no cancela invitaciones ajenas; las OWNER solo las gestiona la administracion")
    void cancellationRules() throws Exception {
        String household = householdInvitation(a.owner(), "Residente");
        String householdId = storedFor(household).getId().toString();
        as(roommate, a, delete("/api/v1/resident/me/invitations/{id}", householdId)).andExpect(status().isForbidden());
        assertThat(storedFor(household).getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(pendingEntry(roommate, householdId).get("canRemove").asBoolean()).isFalse();
        assertThat(pendingEntry(roommate, householdId).get("email").asText()).isEmpty();

        // Invitacion OWNER que quedo pendiente cuando la unidad paso a estar ocupada por otra via.
        ResidentInvitation adminInv = ResidentInvitation.builder().property(a.home())
                .tokenHash(InvitationTokens.hash(InvitationTokens.generate()))
                .status(InvitationStatus.PENDING).invitationType("OWNER").maxUses(1).usedCount(0)
                .expiresAt(OffsetDateTime.now().plusHours(24)).build();
        adminInv.setTenantId(a.tenantId());
        adminInv = invitationRepository.save(adminInv);
        assertThat(pendingEntry(a.owner(), adminInv.getId().toString())).as("las OWNER no se listan en Mi Hogar").isNull();
        as(roommate, a, delete("/api/v1/resident/me/invitations/{id}", adminInv.getId())).andExpect(status().isForbidden());
        as(a.owner(), a, delete("/api/v1/resident/me/invitations/{id}", adminInv.getId())).andExpect(status().isForbidden());
        assertThat(invitationRepository.findById(adminInv.getId()).orElseThrow().getStatus()).isEqualTo(InvitationStatus.PENDING);

        // El propietario si cancela las de hogar de su unidad; otra comunidad ni la encuentra.
        as(b.owner(), b, delete("/api/v1/resident/me/invitations/{id}", householdId)).andExpect(status().isNotFound());
        as(a.owner(), a, delete("/api/v1/resident/me/invitations/{id}", householdId)).andExpect(status().isOk());
        assertThat(storedFor(household).getStatus()).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    @DisplayName("una invitacion de hogar dirigida a un correo solo la usa ese correo, sin revelarlo")
    void householdInvitationWithEmailIsBound() throws Exception {
        String invitee = "invitado-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String payload = "{\"type\":\"Residente\",\"email\":\"%s\",\"name\":\"Invitado Sintetico\",\"phone\":\"\",\"propertyId\":\"%s\"}"
                .formatted(invitee, a.home().getId());
        String token = tokenOf(data(as(a.owner(), a, post("/api/v1/resident/me/invitations")
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk())).get("link").asText());

        String mismatch = body(register(token, roommate.getEmail(), PASSWORD).andExpect(status().isConflict()));
        assertThat(mismatch).doesNotContain(invitee);
        assertThat(storedFor(token).getStatus()).isEqualTo(InvitationStatus.PENDING);
        register(token, invitee, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("dos aceptaciones simultaneas del mismo token: solo una prospera")
    void concurrentAcceptanceUsesTokenOnce() throws Exception {
        String token = householdInvitation(a.owner(), "Residente");
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<Integer> codes = new java.util.ArrayList<>();
        try {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int n = 0; n < 2; n++) {
                String email = "carrera-" + n + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
                results.add(pool.submit(() -> {
                    start.await();
                    return register(token, email, PASSWORD).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var f : results) codes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        ResidentInvitation used = storedFor(token);
        assertThat(used.getStatus()).isEqualTo(InvitationStatus.USED);
        assertThat(used.getUsedCount()).isEqualTo(1);
    }

    // ── Migracion de tokens heredados ─────────────────────────────────────────

    private ResidentInvitation legacy(String rawToken, InvitationStatus status) {
        ResidentInvitation inv = ResidentInvitation.builder().property(a.vacant()).tokenHash(rawToken)
                .status(status).invitationType("OWNER").maxUses(1).usedCount(status == InvitationStatus.USED ? 1 : 0)
                .expiresAt(OffsetDateTime.now().plusHours(24)).build();
        inv.setTenantId(a.tenantId());
        return invitationRepository.save(inv);
    }

    @Test
    @DisplayName("migracion: convierte en sitio los tokens heredados sin invalidarlos, conserva el historial y es idempotente")
    void legacyTokensAreMigratedSafely() throws Exception {
        String pendingRaw = InvitationTokens.generate();
        String usedRaw = InvitationTokens.generate();
        ResidentInvitation pending = legacy(pendingRaw, InvitationStatus.PENDING);
        ResidentInvitation used = legacy(usedRaw, InvitationStatus.USED);

        // Sin migrar, la busqueda es solo por hash: no hay via de compatibilidad en claro.
        validate(pendingRaw).andExpect(status().isConflict());

        var expiresAsStored = invitationRepository.findById(pending.getId()).orElseThrow().getExpiresAt().toInstant();
        var plan = migration.plan();
        assertThat(plan.ids()).contains(pending.getId(), used.getId());
        assertThat(invitationRepository.findById(pending.getId()).orElseThrow().getTokenHash()).isEqualTo(pendingRaw);

        assertThat(migration.apply()).isGreaterThanOrEqualTo(2);
        ResidentInvitation migrated = invitationRepository.findById(pending.getId()).orElseThrow();
        assertThat(migrated.getTokenHash()).isEqualTo(sha256Hex(pendingRaw));
        assertThat(migrated.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(migrated.getExpiresAt().toInstant()).isEqualTo(expiresAsStored);
        ResidentInvitation history = invitationRepository.findById(used.getId()).orElseThrow();
        assertThat(history.getTokenHash()).isEqualTo(sha256Hex(usedRaw));
        assertThat(history.getStatus()).isEqualTo(InvitationStatus.USED);

        validate(pendingRaw).andExpect(status().isOk());
        validate(usedRaw).andExpect(status().isConflict());

        assertThat(migration.plan().ids()).isEmpty();
        assertThat(migration.apply()).isZero();
        assertThat(invitationRepository.findById(pending.getId()).orElseThrow().getTokenHash()).isEqualTo(sha256Hex(pendingRaw));
    }
}
