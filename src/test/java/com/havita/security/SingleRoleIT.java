package com.havita.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cierre SEC-9: regla definitiva de un solo rol activo por persona y comunidad (R1-R4).
 * <ul>
 *   <li>R3: toda asignacion, cambio o retiro de rol queda en la bitacora existente, con rol anterior y nuevo, actor,
 *       comunidad e id de correlacion, sin datos sensibles; y el rol anterior deja de aplicarse en la peticion siguiente.</li>
 *   <li>R2: aceptar una invitacion de residencia no cambia el rol existente ni crea un segundo rol; una cuenta global
 *       se rechaza con un motivo claro.</li>
 *   <li>R4: la membresia provisional COMMUNITY_ADMIN sin comunidad no da acceso a nada hasta asignarla.</li>
 *   <li>R1: READ_ONLY asignado a una comunidad no obtiene alcance global ni datos de otra comunidad.</li>
 * </ul>
 */
class SingleRoleIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Sintetica-2026!";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private Side a;
    private Side b;
    private User superAdmin;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        superAdmin = user("sa", null, RoleType.SUPER_ADMIN);
    }

    // ── utilidades ────────────────────────────────────────────────────────────

    private User user(String label, UUID tenantId, RoleType role) {
        String handle = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder().email(handle + "@example.test").passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Cuenta " + handle).phone("3000000000").build());
        if (role != null) {
            membershipRepository.save(Membership.builder().user(u).tenantId(tenantId).role(role)
                    .status(MembershipStatus.ACTIVE).build());
        }
        return u;
    }

    private MvcResult call(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        req.header("Authorization", fixture.bearer(u));
        if (side != null) req.header("X-Tenant-ID", side.tenantId().toString());
        return mvc.perform(req).andReturn();
    }

    private MvcResult assign(String email, RoleType role, UUID tenantId, String traceId) throws Exception {
        String body = objectMapper.writeValueAsString(tenantId != null
                ? Map.of("email", email, "role", role.name(), "tenantId", tenantId.toString())
                : Map.of("email", email, "role", role.name()));
        MockHttpServletRequestBuilder req = post("/api/v1/memberships").contentType(MediaType.APPLICATION_JSON).content(body);
        if (traceId != null) req.header("X-Correlation-ID", traceId);
        return call(superAdmin, null, req);
    }

    private List<Map<String, Object>> audits(String action, UUID userId) {
        return jdbc.queryForList("select tenant_id, actor_role, actor_email, actor_name, details, result from platform_audit_events "
                + "where action = ? and details like ? order by timestamp", action, "%userId=" + userId + ";%");
    }

    private UUID membershipId(User u, UUID tenantId) {
        return membershipRepository.findByUserIdAndTenantId(u.getId(), tenantId).orElseThrow().getId();
    }

    private String invitationFor(UUID propertyId) throws Exception {
        MvcResult r = call(a.admin(), a, post("/api/v1/properties/{id}/resident-invitations", propertyId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String link = objectMapper.readTree(r.getResponse().getContentAsString()).path("data").path("link").asText();
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private MvcResult register(String token, String email) throws Exception {
        return mvc.perform(post("/api/v1/public/resident-invitations/{token}/register", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Persona Sintetica\",\"email\":\"%s\",\"documentType\":\"CC\",\"documentId\":\"1020304050\",\"phone\":\"3001112233\",\"password\":\"%s\",\"acceptTerms\":true,\"acceptPrivacy\":true}"
                        .formatted(email, PASSWORD))).andReturn();
    }

    private int status(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return call(u, side, req).getResponse().getStatus();
    }

    // ── R3: auditoria y efecto inmediato ──────────────────────────────────────

    @Test
    @DisplayName("R3 · cambio de rol: auditado con rol anterior/nuevo, actor, comunidad y correlacion; el rol anterior deja de aplicar ya")
    void roleChangeIsAuditedAndEffectiveImmediately() throws Exception {
        String adminToken = fixture.bearer(a.admin()); // emitido ANTES del cambio
        MockHttpServletRequestBuilder adminOnly = get("/api/v1/accounting/invoices/by-property/{propertyId}", a.home().getId());
        assertThat(mvc.perform(adminOnly.header("Authorization", adminToken).header("X-Tenant-ID", a.tenantId().toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        MvcResult change = assign(a.admin().getEmail(), RoleType.SECURITY, a.tenantId(), "trace-r3-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(change.getResponse().getStatus()).as(change.getResponse().getContentAsString()).isEqualTo(200);
        String traceId = change.getRequest().getHeader("X-Correlation-ID");

        // Mismo JWT, peticion siguiente: ya es SECURITY.
        int after = mvc.perform(get("/api/v1/accounting/invoices/by-property/{propertyId}", a.home().getId())
                .header("Authorization", adminToken).header("X-Tenant-ID", a.tenantId().toString())).andReturn().getResponse().getStatus();
        assertThat(after).isEqualTo(403);
        assertThat(status(a.admin(), a, get("/api/v1/properties/{id}", a.home().getId()))).as("SECURITY si ve la ficha").isEqualTo(200);

        List<Map<String, Object>> rows = audits("MEMBERSHIP_ROLE_CHANGED", a.admin().getId());
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        String details = (String) row.get("details");
        assertThat(row.get("tenant_id")).isEqualTo(a.tenantId());
        assertThat(row.get("actor_role")).isEqualTo("SUPER_ADMIN");
        assertThat(row.get("actor_email")).isEqualTo("-");
        assertThat(details)
                .contains("userId=" + a.admin().getId())
                .contains("membershipId=" + membershipId(a.admin(), a.tenantId()))
                .contains("tenantId=" + a.tenantId())
                .contains("previousRole=COMMUNITY_ADMIN")
                .contains("newRole=SECURITY")
                .contains("actorUserId=" + superAdmin.getId())
                .contains("traceId=" + traceId)
                .contains(";at=");
        assertThat(details).doesNotContain("@").doesNotContainIgnoringCase("bearer").doesNotContainIgnoringCase("password")
                .doesNotContain(adminToken.substring(7, 40));
    }

    @Test
    @DisplayName("R3 · la bitacora de la comunidad muestra sus cambios de rol y la de otra comunidad no")
    void roleChangeAuditIsTenantScoped() throws Exception {
        User auditor = user("auditor", a.tenantId(), RoleType.READ_ONLY);
        assertThat(assign(a.guard().getEmail(), RoleType.STAFF, a.tenantId(), null).getResponse().getStatus()).isEqualTo(200);

        MvcResult own = call(auditor, a, get("/api/v1/audit/logs").param("size", "200"));
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(own.getResponse().getContentAsString()).contains("MEMBERSHIP_ROLE_CHANGED").contains(a.guard().getId().toString());

        // La administracion de B, aunque pida explicitamente la comunidad A, solo ve la suya.
        MvcResult foreign = call(b.admin(), b, get("/api/v1/audit/logs").param("tenantId", a.tenantId().toString()).param("size", "200"));
        assertThat(foreign.getResponse().getStatus()).isEqualTo(200);
        assertThat(foreign.getResponse().getContentAsString()).doesNotContain(a.guard().getId().toString());
    }

    @Test
    @DisplayName("R3 · asignacion y retiro tambien se auditan, y el retiro quita el acceso de inmediato")
    void assignmentAndRemovalAreAudited() throws Exception {
        User worker = user("trabajador", null, null);
        assertThat(assign(worker.getEmail(), RoleType.STAFF, a.tenantId(), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(status(worker, a, get("/api/v1/properties/{id}", a.home().getId()))).isEqualTo(200);
        assertThat(audits("MEMBERSHIP_ROLE_ASSIGNED", worker.getId())).singleElement()
                .satisfies(r -> assertThat((String) r.get("details")).contains("previousRole=NONE").contains("newRole=STAFF"));

        UUID membership = membershipId(worker, a.tenantId());
        assertThat(call(superAdmin, null, delete("/api/v1/memberships/{id}", membership)).getResponse().getStatus()).isEqualTo(200);
        assertThat(status(worker, a, get("/api/v1/properties/{id}", a.home().getId()))).isIn(401, 403);
        assertThat(audits("MEMBERSHIP_ROLE_REMOVED", worker.getId())).singleElement()
                .satisfies(r -> assertThat((String) r.get("details")).contains("previousRole=STAFF").contains("newRole=NONE"));
    }

    @Test
    @DisplayName("R3 · un cambio revertido no deja auditoria y un cambio de estado no se registra como cambio de rol")
    void rolledBackOrStatusOnlyChangesAreNotRoleChanges() {
        UUID id = membershipId(a.guard(), a.tenantId());
        tx.executeWithoutResult(s -> {
            Membership m = membershipRepository.findById(id).orElseThrow();
            m.setRole(RoleType.COMMUNITY_ADMIN);
            membershipRepository.flush();
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select role from memberships where id = ?", String.class, id)).isEqualTo("SECURITY");
        assertThat(audits("MEMBERSHIP_ROLE_CHANGED", a.guard().getId())).isEmpty();

        tx.executeWithoutResult(s -> membershipRepository.findById(id).orElseThrow().setStatus(MembershipStatus.INACTIVE));
        assertThat(audits("MEMBERSHIP_ROLE_CHANGED", a.guard().getId())).isEmpty();
    }

    // ── R4: membresia provisional sin comunidad ───────────────────────────────

    @Test
    @DisplayName("R4 · COMMUNITY_ADMIN sin comunidad no accede a nada; al asignarla se reutiliza la misma fila y queda auditado")
    void placeholderAdminHasNoAccessUntilAssigned() throws Exception {
        User pending = user("admin-pendiente", null, RoleType.COMMUNITY_ADMIN);
        assertThat(status(pending, a, get("/api/v1/properties/{id}", a.home().getId()))).as("con X-Tenant-ID").isEqualTo(401);
        assertThat(status(pending, null, get("/api/v1/tenants"))).as("sin comunidad, endpoint global").isIn(401, 403);
        assertThat(status(pending, null, get("/api/v1/users/all"))).isIn(401, 403);

        assertThat(assign(pending.getEmail(), RoleType.COMMUNITY_ADMIN, a.tenantId(), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(membershipRepository.findByUserId(pending.getId())).singleElement()
                .satisfies(m -> assertThat(m.getTenantId()).isEqualTo(a.tenantId()));
        assertThat(status(pending, a, get("/api/v1/properties/{id}", a.home().getId()))).isEqualTo(200);
        assertThat(status(pending, b, get("/api/v1/properties/{id}", b.home().getId()))).as("solo su comunidad").isEqualTo(401);
        assertThat(audits("MEMBERSHIP_ROLE_CHANGED", pending.getId())).singleElement()
                .satisfies(r -> assertThat((String) r.get("details")).contains("previousTenantId=GLOBAL")
                        .contains("tenantId=" + a.tenantId()).contains("previousRole=COMMUNITY_ADMIN"));
    }

    // ── R1: READ_ONLY con comunidad ───────────────────────────────────────────

    @Test
    @DisplayName("R1 · READ_ONLY asignado a una comunidad: sin alcance global y sin datos de otra comunidad")
    void scopedReadOnlyIsNotGlobal() throws Exception {
        User auditor = user("solo-lectura", a.tenantId(), RoleType.READ_ONLY);
        assertThat(status(auditor, a, get("/api/v1/tenants"))).isEqualTo(403);
        assertThat(status(auditor, a, get("/api/v1/organizations"))).isEqualTo(403);
        assertThat(status(auditor, a, get("/api/v1/tenants/{id}", b.tenantId()))).isIn(403, 404);
        assertThat(status(auditor, b, get("/api/v1/audit/logs"))).as("X-Tenant-ID de otra comunidad").isEqualTo(401);

        assign(b.guard().getEmail(), RoleType.STAFF, b.tenantId(), null); // evento de B
        MvcResult logs = call(auditor, a, get("/api/v1/audit/logs").param("tenantId", b.tenantId().toString()).param("size", "200"));
        assertThat(logs.getResponse().getStatus()).isEqualTo(200);
        assertThat(logs.getResponse().getContentAsString()).doesNotContain(b.tenantId().toString()).doesNotContain(b.guard().getId().toString());
    }

    // ── R2: invitaciones de residencia ────────────────────────────────────────

    @Test
    @DisplayName("R2 · una cuenta con rol operativo acepta la residencia: conserva su rol, sin segunda membresia ni permisos de residente")
    void invitationKeepsTheExistingRole() throws Exception {
        User guard = user("porteria-propietaria", a.tenantId(), RoleType.SECURITY);
        MvcResult r = register(invitationFor(a.vacant().getId()), guard.getEmail());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);

        assertThat(membershipRepository.findByUserId(guard.getId())).singleElement()
                .satisfies(m -> assertThat(m.getRole()).isEqualTo(RoleType.SECURITY));
        assertThat(jdbc.queryForObject("select count(*) from residents where user_id = ? and property_id = ? and status = 'ACTIVE'",
                Integer.class, guard.getId(), a.vacant().getId())).isEqualTo(1);
        assertThat(status(guard, a, get("/api/v1/resident/me/vehicles"))).as("portal del residente").isEqualTo(403);
        assertThat(status(guard, a, get("/api/v1/accounting/properties/{propertyId}/balance", a.vacant().getId())))
                .as("la residencia no abre la contabilidad de su unidad").isEqualTo(403);
        assertThat(audits("MEMBERSHIP_ROLE_CHANGED", guard.getId())).isEmpty();
    }

    @Test
    @DisplayName("R2 · una cuenta global no se vincula como residente: 409 con motivo claro y nada modificado")
    void globalAccountCannotAcceptAResidence() throws Exception {
        String token = invitationFor(a.vacant().getId());
        MvcResult r = register(token, superAdmin.getEmail());
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("GLOBAL_ACCOUNT_CANNOT_BE_RESIDENT")
                .contains("no puede vincularse como residente");
        assertThat(membershipRepository.findByUserId(superAdmin.getId())).singleElement()
                .satisfies(m -> assertThat(m.getTenantId()).isNull());
        assertThat(jdbc.queryForObject("select count(*) from residents where user_id = ?", Integer.class, superAdmin.getId())).isZero();
        assertThat(jdbc.queryForObject("select status from resident_invitations where token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
                String.class, token)).as("la invitacion sigue disponible para su destinatario real").isEqualTo("PENDING");
    }
}
