package com.havita.modules.resident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.domain.VotingDelegation;
import com.havita.modules.assembly.domain.VotingDelegationStatus;
import com.havita.modules.assembly.infrastructure.VotingDelegationRepository;
import com.havita.modules.audit.domain.AuditEvent;
import com.havita.modules.audit.infrastructure.AuditEventRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RefreshToken;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.RefreshTokenRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.reservation.infrastructure.ReservationRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import com.havita.support.UnitDataFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-3 (una baja de la administracion solo se revierte con autorizacion administrativa) y D-4 (las bajas locales no
 * revocan las sesiones globales; el acceso se corta contra PostgreSQL solo donde se perdio la autorizacion).
 */
class ResidentReactivationIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Clave-Segura-2027";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UnitDataFixture unitData;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private VotingDelegationRepository delegationRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Side a;
    private Side b;
    private User relative;
    private Resident relativeHome;
    private RefreshToken relativeSession;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        unitData.seed(a);
        relative = userRepository.save(User.builder()
                .email("familiar-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash(passwordEncoder.encode(PASSWORD)).fullName("Familiar Con Cuenta").phone("3001112233").build());
        membershipRepository.save(Membership.builder()
                .user(relative).tenantId(a.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        relativeHome = relation(a, a.home(), relative, "Residente");
        relativeSession = refreshTokenRepository.save(RefreshToken.builder()
                .user(relative).token("rt-" + UUID.randomUUID()).expiresAt(OffsetDateTime.now().plusDays(7)).build());
    }

    private Resident relation(Side side, Property property, User user, String type) {
        Resident r = Resident.builder().property(property).user(user).name(user.getFullName()).type(type)
                .email(user.getEmail()).status(ResidentStatus.ACTIVE).build();
        r.setTenantId(side.tenantId());
        return residentRepository.save(r);
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private ResultActions withToken(String token, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", token).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private boolean sessionRevoked() {
        return refreshTokenRepository.findById(relativeSession.getId()).orElseThrow().isRevoked();
    }

    private Resident reload(Resident r) {
        return residentRepository.findById(r.getId()).orElseThrow();
    }

    private List<AuditEvent> audit(String action) {
        return auditEventRepository.findByTenantId(a.tenantId(), org.springframework.data.domain.Pageable.unpaged()).getContent()
                .stream().filter(e -> action.equals(e.getAction()) && e.getDetails().contains("residentId=" + relativeHome.getId())).toList();
    }

    /** Invitacion de hogar emitida por el propietario y aceptada por el familiar con su cuenta existente. */
    private ResultActions ownerInvitesRelativeAndRelativeAccepts() throws Exception {
        String body = as(a.owner(), a, post("/api/v1/resident/me/invitations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"Residente\",\"email\":\"%s\",\"name\":\"Familiar Con Cuenta\",\"phone\":\"3001112233\",\"propertyId\":\"%s\"}"
                        .formatted(relative.getEmail(), a.home().getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String link = objectMapper.readTree(body).at("/data/link").asText();
        return register(link.substring(link.lastIndexOf('/') + 1));
    }

    private ResultActions register(String token) throws Exception {
        return mvc.perform(post("/api/v1/public/resident-invitations/{token}/register", token)
                .header("X-Tenant-ID", a.tenantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Familiar Con Cuenta\",\"email\":\"%s\",\"documentType\":\"CC\",\"documentId\":\"1020304050\",\"phone\":\"3001112233\",\"password\":\"%s\",\"acceptTerms\":true,\"acceptPrivacy\":true}"
                        .formatted(relative.getEmail(), PASSWORD)));
    }

    // ── D-4: sesiones ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-4: la baja que hace el propietario no revoca los refresh tokens globales del afectado")
    void ownerRemovalKeepsGlobalSessions() throws Exception {
        as(a.owner(), a, delete("/api/v1/resident/me/people/{id}", relativeHome.getId())).andExpect(status().isOk());
        assertThat(reload(relativeHome).getStatus()).isEqualTo(ResidentStatus.INACTIVE);
        assertThat(sessionRevoked()).isFalse();
    }

    @Test
    @DisplayName("D-4: baja administrativa tampoco revoca los refresh tokens globales")
    void adminRemovalKeepsGlobalSessions() throws Exception {
        as(a.admin(), a, delete("/api/v1/residents/{id}", relativeHome.getId())).andExpect(status().isOk());
        assertThat(sessionRevoked()).isFalse();
    }

    @Test
    @DisplayName("D-4: con dos comunidades, el JWT anterior deja de valer solo en la comunidad de la baja")
    void jwtLosesOnlyTheAffectedCommunity() throws Exception {
        membershipRepository.save(Membership.builder()
                .user(relative).tenantId(b.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        // P0-B: en B es PROPIETARIO. Una persona solo puede tener una residencia no propietaria activa en toda Havita
        // (uq_active_non_owner_residence_per_user/_per_email) y ya es Residente en A. Ser propietario en otra comunidad
        // si esta permitido, y la prueba sigue comprobando que la baja en A no afecta a su acceso en B.
        relation(b, b.home(), relative, "Propietario");
        String token = fixture.bearer(relative);

        as(a.owner(), a, delete("/api/v1/resident/me/people/{id}", relativeHome.getId())).andExpect(status().isOk());

        assertThat(withToken(token, a, get("/api/v1/communications")).andReturn().getResponse().getStatus()).isEqualTo(401);
        withToken(token, b, get("/api/v1/communications")).andExpect(status().isOk());
        withToken(token, b, get("/api/v1/residents/by-property/{id}", b.home().getId())).andExpect(status().isOk());
        assertThat(sessionRevoked()).isFalse();
    }

    @Test
    @DisplayName("D-4: con varias unidades, pierde solo la unidad dada de baja y conserva la sesion y la otra unidad")
    void multipleUnitsKeepTheOtherOne() throws Exception {
        // P0-B: la segunda unidad es una relacion de PROPIETARIO (varias propiedades + una sola residencia no propietaria
        // activa: permitido). Dos residencias no propietarias activas a la vez no son validas en Havita.
        relation(a, a.vacant(), relative, "Propietario");
        String token = fixture.bearer(relative);

        as(a.owner(), a, delete("/api/v1/resident/me/people/{id}", relativeHome.getId())).andExpect(status().isOk());

        withToken(token, a, get("/api/v1/residents/by-property/{id}", a.home().getId())).andExpect(status().isForbidden());
        withToken(token, a, get("/api/v1/residents/by-property/{id}", a.vacant().getId())).andExpect(status().isOk());
        assertThat(sessionRevoked()).isFalse();
    }

    @Test
    @DisplayName("D-4: la conexion SSE del afectado recibe su aviso y se cierra; el ticket nuevo se rechaza")
    void sseConnectionIsClosed() throws Exception {
        String ticketBody = as(relative, a, post("/api/v1/sync/stream-ticket")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        MvcResult stream = mvc.perform(get("/api/v1/sync/stream").param("ticket", objectMapper.readTree(ticketBody).get("ticket").asText()))
                .andExpect(request().asyncStarted()).andReturn();

        as(a.owner(), a, delete("/api/v1/resident/me/people/{id}", relativeHome.getId())).andExpect(status().isOk());
        as(a.guard(), a, post("/api/v1/access/packages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"recipientName\":\"Paquete Tras La Baja\"}".formatted(a.home().getId())))
                .andExpect(status().isCreated());

        String received = stream.getResponse().getContentAsString();
        assertThat(received).contains("RESIDENT_ACCESS_REVOKED").contains(relative.getId().toString()).doesNotContain("Paquete Tras La Baja");
        assertThat(as(relative, a, post("/api/v1/sync/stream-ticket")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    // ── D-3: reactivacion tras ADMIN_REMOVAL ───────────────────────────────────

    @Test
    @DisplayName("D-3: una invitacion del propietario no revierte una baja de la administracion (409, sin cambios ni duplicados)")
    void ownerInvitationCannotUndoAdminRemoval() throws Exception {
        as(a.admin(), a, delete("/api/v1/residents/{id}", relativeHome.getId())).andExpect(status().isOk());

        ownerInvitesRelativeAndRelativeAccepts()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REACTIVATION_REQUIRES_ADMIN"));

        Resident after = reload(relativeHome);
        assertThat(after.getStatus()).isEqualTo(ResidentStatus.INACTIVE);
        assertThat(after.getEndReason()).isEqualTo("ADMIN_REMOVAL");
        assertThat(residentRepository.findByUserIdAndTenantId(relative.getId(), a.tenantId())).hasSize(1);
        assertThat(audit("RESIDENT_REACTIVATION_DENIED")).hasSize(1);
    }

    @Test
    @DisplayName("D-3: tras una baja del propietario, su invitacion si reactiva la misma fila (sin duplicados)")
    void ownerInvitationReactivatesOwnRemoval() throws Exception {
        as(a.owner(), a, delete("/api/v1/resident/me/people/{id}", relativeHome.getId())).andExpect(status().isOk());

        ownerInvitesRelativeAndRelativeAccepts().andExpect(status().is2xxSuccessful());

        assertThat(reload(relativeHome).getStatus()).isEqualTo(ResidentStatus.ACTIVE);
        assertThat(residentRepository.findByUserIdAndTenantId(relative.getId(), a.tenantId())).hasSize(1);
    }

    @Test
    @DisplayName("D-3: reactivacion explicita por la administracion: misma fila, trazabilidad completa en auditoria")
    void adminReactivationIsTraced() throws Exception {
        as(a.admin(), a, delete("/api/v1/residents/{id}", relativeHome.getId())).andExpect(status().isOk());
        as(a.admin(), a, put("/api/v1/residents/{id}", relativeHome.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());

        assertThat(reload(relativeHome).getStatus()).isEqualTo(ResidentStatus.ACTIVE);
        assertThat(residentRepository.findByUserIdAndTenantId(relative.getId(), a.tenantId())).hasSize(1);

        List<AuditEvent> ended = audit("RESIDENT_RELATION_ENDED");
        List<AuditEvent> reactivated = audit("RESIDENT_RELATION_REACTIVATED");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDetails()).contains("endReason=ADMIN_REMOVAL");
        assertThat(reactivated).hasSize(1);
        assertThat(reactivated.get(0).getDetails())
                .contains("channel=ADMIN").contains("previousEndReason=ADMIN_REMOVAL").contains("previousEndedBy=" + a.admin().getId());
        assertThat(reactivated.get(0).getDetails()).doesNotContain(relative.getEmail());
    }

    @Test
    @DisplayName("D-3: reactivar a un arrendatario no restaura la delegacion de voto que se invalido con su baja")
    void reactivationDoesNotRestoreDelegation() throws Exception {
        User tenantUser = userRepository.save(User.builder().email("arr-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}x").fullName("Arrendatario").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(tenantUser).tenantId(a.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        Resident tenantRel = relation(a, a.home(), tenantUser, "Arrendatario");
        VotingDelegation d = VotingDelegation.builder().property(a.home()).ownerUser(a.owner()).delegateUser(tenantUser)
                .status(VotingDelegationStatus.ACTIVE).build();
        d.setTenantId(a.tenantId());
        d = delegationRepository.save(d);

        as(a.admin(), a, delete("/api/v1/residents/{id}", tenantRel.getId())).andExpect(status().isOk());
        assertThat(delegationRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(VotingDelegationStatus.INVALIDATED);

        as(a.admin(), a, put("/api/v1/residents/{id}", tenantRel.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
        assertThat(delegationRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(VotingDelegationStatus.INVALIDATED);
        assertThat(delegationRepository.findByPropertyIdAndStatusAndTenantId(a.home().getId(), VotingDelegationStatus.ACTIVE, a.tenantId())).isEmpty();
    }

    @Test
    @DisplayName("Historial: tras baja y reactivacion las reservas del residente siguen asociadas")
    void historyIsKept() throws Exception {
        long before = reservationRepository.findAll().stream()
                .filter(r -> r.getResident() != null && r.getResident().getId().equals(a.ownerResident().getId())).count();
        as(a.admin(), a, delete("/api/v1/residents/{id}", a.ownerResident().getId())).andExpect(status().isOk());
        as(a.admin(), a, put("/api/v1/residents/{id}", a.ownerResident().getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
        long after = reservationRepository.findAll().stream()
                .filter(r -> r.getResident() != null && r.getResident().getId().equals(a.ownerResident().getId())).count();
        assertThat(before).isPositive();
        assertThat(after).isEqualTo(before);
    }
}
