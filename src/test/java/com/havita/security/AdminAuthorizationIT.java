package com.havita.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.audit.api.dto.RecordAuditEventRequest;
import com.havita.modules.audit.application.AuditService;
import com.havita.modules.audit.domain.AuditEvent;
import com.havita.modules.audit.infrastructure.AuditEventRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RefreshToken;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.RefreshTokenRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * SEC-9 (P0): la autorizacion administrativa se decide con el estado actual en PostgreSQL (usuario y membresia
 * ACTIVE, rol y alcance), no con el JWT; las membresias con comunidad nunca conceden alcance global; los recursos de
 * nivel comunidad no se cruzan entre comunidades; y un COMMUNITY_ADMIN solo asigna STAFF o SECURITY.
 */
class AdminAuthorizationIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Clave-Segura-2027";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private AuditService auditService;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    // ── utilidades ────────────────────────────────────────────────────────────

    private User account(String handle, RoleType role, UUID tenantId) {
        User u = userRepository.save(User.builder()
                .email(handle + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash(passwordEncoder.encode(PASSWORD)).fullName("Cuenta " + handle).phone("3000000000").build());
        if (role != null) {
            membershipRepository.save(Membership.builder().user(u).tenantId(tenantId).role(role).status(MembershipStatus.ACTIVE).build());
        }
        return u;
    }

    private int status(String token, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        return call(token, tenant, req).getResponse().getStatus();
    }

    private MvcResult call(String token, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        if (token != null) req.header("Authorization", token);
        if (tenant != null) req.header("X-Tenant-ID", tenant.toString());
        return mvc.perform(req).andReturn();
    }

    private String body(String token, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        return call(token, tenant, req).getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder renameTenant(UUID id, String name) {
        return put("/api/v1/tenants/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}");
    }

    private MockHttpServletRequestBuilder renameVacant(Side side) {
        return put("/api/v1/properties/{id}", side.vacant().getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"number\":\"%s\",\"name\":\"Renombrada SEC-9\",\"status\":\"VACANT\"}".formatted(side.vacant().getNumber()));
    }

    private MockHttpServletRequestBuilder createStaff(String role) {
        return post("/api/v1/staff").contentType(MediaType.APPLICATION_JSON).content(
                "{\"firstName\":\"Personal\",\"lastName\":\"Sintetico\",\"documentNumber\":\"%s\",\"email\":\"personal-%s@example.test\",\"position\":\"Auxiliar\",\"createSystemAccess\":true,\"password\":\"%s\",\"systemRole\":\"%s\"}"
                        .formatted(UUID.randomUUID().toString().substring(0, 9), UUID.randomUUID().toString().substring(0, 8), PASSWORD, role));
    }

    private Membership membershipOf(User u, UUID tenantId) {
        return membershipRepository.findByUserIdAndTenantId(u.getId(), tenantId).orElseThrow();
    }

    /** Lo que las operaciones rechazadas no deben tocar: comunidad B, unidades vacias, membresias y personal. */
    private String snapshot() {
        Tenant tb = tenantRepository.findById(b.tenantId()).orElseThrow();
        Tenant ta = tenantRepository.findById(a.tenantId()).orElseThrow();
        return tb.getName() + "|" + tb.getSubdomain() + "|" + ta.getName() + "|"
                + propertyRepository.findById(a.vacant().getId()).orElseThrow().getName() + "|"
                + propertyRepository.findById(b.vacant().getId()).orElseThrow().getName() + "|"
                + membershipRepository.count() + "|" + userRepository.count();
    }

    private void auditMarker(Side side, String action) {
        auditService.recordEvent(new RecordAuditEventRequest("Sistema", "-", "SYSTEM", action, "Sonda", side.tenantId(), null, "SUCCESS", "-", "-"));
    }

    // ── Paso 1: aislamiento entre comunidades ─────────────────────────────────

    @Test
    @DisplayName("SEC-9-1: un COMMUNITY_ADMIN de A no lee ni modifica la comunidad B (404, sin cambios); la suya si")
    void communityAdminCannotReachOtherTenant() throws Exception {
        String admin = fixture.bearer(a.admin());
        String slugB = tenantRepository.findById(b.tenantId()).orElseThrow().getSlug();
        String before = snapshot();

        assertThat(status(admin, a.tenantId(), get("/api/v1/tenants/{id}", b.tenantId()))).isEqualTo(404);
        assertThat(status(admin, a.tenantId(), get("/api/v1/tenants/by-slug/{s}", slugB))).isEqualTo(404);
        assertThat(status(admin, a.tenantId(), renameTenant(b.tenantId(), "Renombrada por A"))).isEqualTo(404);
        // Un id inexistente responde igual que uno ajeno: no permite enumerar.
        assertThat(status(admin, a.tenantId(), get("/api/v1/tenants/{id}", UUID.randomUUID()))).isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);

        assertThat(status(admin, a.tenantId(), get("/api/v1/tenants/{id}", a.tenantId()))).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-9-1: la auditoria de un COMMUNITY_ADMIN queda limitada a su comunidad aunque pida otra")
    void auditLogsAreScopedForCommunityAdmins() throws Exception {
        String markerA = "MARCA_A_" + UUID.randomUUID().toString().substring(0, 8);
        String markerB = "MARCA_B_" + UUID.randomUUID().toString().substring(0, 8);
        auditMarker(a, markerA);
        auditMarker(b, markerB);
        String admin = fixture.bearer(a.admin());

        String ofB = body(admin, a.tenantId(), get("/api/v1/audit/logs").param("tenantId", b.tenantId().toString()).param("size", "500"));
        String own = body(admin, a.tenantId(), get("/api/v1/audit/logs").param("size", "500"));
        assertThat(ofB).doesNotContain(markerB);
        assertThat(own).contains(markerA).doesNotContain(markerB);
    }

    // ── Paso 2: alcance global y roles asignables ─────────────────────────────

    @Test
    @DisplayName("SEC-9-2: un COMMUNITY_ADMIN solo crea personal STAFF o SECURITY; READ_ONLY, SUPER_ADMIN, PLATFORM_ADMIN y COMMUNITY_ADMIN se rechazan sin crear membresias")
    void communityAdminAssignsOnlyStaffOrSecurity() throws Exception {
        String admin = fixture.bearer(a.admin());
        long before = membershipRepository.count();
        for (String role : List.of("READ_ONLY", "SUPER_ADMIN", "PLATFORM_ADMIN", "COMMUNITY_ADMIN")) {
            assertThat(status(admin, a.tenantId(), createStaff(role))).as(role).isEqualTo(403);
        }
        assertThat(membershipRepository.count()).isEqualTo(before);
        assertThat(membershipRepository.findByTenantIdAndRole(a.tenantId(), RoleType.READ_ONLY)).isEmpty();

        assertThat(status(admin, a.tenantId(), createStaff("STAFF"))).isEqualTo(201);
        assertThat(status(admin, a.tenantId(), createStaff("SECURITY"))).isEqualTo(201);
    }

    @Test
    @DisplayName("SEC-9-2: una membresia READ_ONLY con comunidad no ve datos globales ni otras comunidades y no escribe")
    void communityReadOnlyHasNoGlobalAccess() throws Exception {
        User reader = account("lector", RoleType.READ_ONLY, a.tenantId());
        String token = fixture.bearer(reader);
        String markerB = "MARCA_B_" + UUID.randomUUID().toString().substring(0, 8);
        auditMarker(b, markerB);
        String before = snapshot();

        assertThat(status(token, a.tenantId(), get("/api/v1/tenants"))).isEqualTo(403);
        assertThat(status(token, a.tenantId(), get("/api/v1/organizations"))).isEqualTo(403);
        assertThat(status(token, a.tenantId(), get("/api/v1/tenants/{id}", b.tenantId()))).isEqualTo(404);
        assertThat(body(token, a.tenantId(), get("/api/v1/audit/logs").param("tenantId", b.tenantId().toString()))).doesNotContain(markerB);
        assertThat(status(token, b.tenantId(), get("/api/v1/tenants/{id}", b.tenantId()))).isIn(401, 403);
        assertThat(status(token, a.tenantId(), renameTenant(a.tenantId(), "Escrita por READ_ONLY"))).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
        // Su propia comunidad, en lectura, sigue disponible.
        assertThat(status(token, a.tenantId(), get("/api/v1/tenants/{id}", a.tenantId()))).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-9-5: un SUPER_ADMIN con comunidad A no opera globalmente ni en B; dentro de A actua como administracion")
    void communityScopedSuperAdminStaysInItsCommunity() throws Exception {
        User scoped = account("sa-a", RoleType.SUPER_ADMIN, a.tenantId());
        String token = fixture.bearer(scoped);
        String before = snapshot();

        assertThat(status(token, a.tenantId(), get("/api/v1/tenants"))).isEqualTo(403);
        assertThat(status(token, a.tenantId(), post("/api/v1/memberships").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"tenantId\":\"%s\",\"role\":\"COMMUNITY_ADMIN\"}".formatted(scoped.getId(), b.tenantId())))).isEqualTo(403);
        assertThat(status(token, b.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
        assertThat(status(token, b.tenantId(), renameVacant(b))).isIn(401, 403);
        assertThat(status(token, a.tenantId(), renameTenant(b.tenantId(), "Renombrada por SA limitado"))).isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);

        assertThat(status(token, a.tenantId(), get("/api/v1/residents"))).isEqualTo(200);
    }

    @Test
    @DisplayName("el SUPER_ADMIN global (sin comunidad) conserva su acceso legitimo a todas las comunidades")
    void globalSuperAdminKeepsAccess() throws Exception {
        User sa = account("sa-global", RoleType.SUPER_ADMIN, null);
        String token = fixture.bearer(sa);
        String list = body(token, a.tenantId(), get("/api/v1/tenants").param("size", "500"));
        assertThat(list).contains(a.tenantId().toString()).contains(b.tenantId().toString());
        assertThat(status(token, b.tenantId(), get("/api/v1/residents"))).isEqualTo(200);
        assertThat(status(token, b.tenantId(), get("/api/v1/tenants/{id}", b.tenantId()))).isEqualTo(200);
        assertThat(status(token, a.tenantId(), renameTenant(b.tenantId(), "Renombrada por SA global"))).isEqualTo(200);
        assertThat(tenantRepository.findById(b.tenantId()).orElseThrow().getName()).isEqualTo("Renombrada por SA global");
    }

    // ── Paso 3: autorizacion vigente en PostgreSQL ────────────────────────────

    @Test
    @DisplayName("SEC-9-3: administrador activo opera; con la membresia INACTIVE, borrada o degradada, su JWT previo ya no sirve (sin cambios)")
    void revokedAdminLosesAccessOnNextRequest() throws Exception {
        String token = fixture.bearer(a.admin());
        assertThat(status(token, a.tenantId(), get("/api/v1/residents"))).isEqualTo(200);

        Membership m = membershipOf(a.admin(), a.tenantId());
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        String before = snapshot();
        assertThat(status(token, a.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
        assertThat(status(token, a.tenantId(), renameVacant(a))).isIn(401, 403);
        assertThat(snapshot()).isEqualTo(before);

        m.setStatus(MembershipStatus.ACTIVE);
        m.setRole(RoleType.RESIDENT);
        membershipRepository.save(m);
        assertThat(status(token, a.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
        assertThat(status(token, a.tenantId(), renameVacant(a))).isIn(401, 403);

        membershipRepository.delete(membershipOf(a.admin(), a.tenantId()));
        String afterDelete = snapshot(); // la propia prueba borro una membresia
        assertThat(status(token, a.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
        assertThat(status(token, a.tenantId(), renameVacant(a))).isIn(401, 403);
        assertThat(snapshot()).isEqualTo(afterDelete);
    }

    @Test
    @DisplayName("SEC-9-3b: renovar la sesion con la membresia INACTIVE no devuelve permisos de administracion")
    void refreshWithInactiveMembershipGrantsNothing() throws Exception {
        RefreshToken rt = refreshTokenRepository.save(RefreshToken.builder()
                .user(a.admin()).token("rt-" + UUID.randomUUID()).expiresAt(OffsetDateTime.now().plusDays(7)).build());
        Membership m = membershipOf(a.admin(), a.tenantId());
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);

        MvcResult r = call(null, null, post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rt.getToken() + "\"}"));
        String access = objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/accessToken").asText(null);
        if (access != null && !access.isBlank()) {
            List<?> claimed = jwtService.parseToken(access).get("memberships", List.class);
            assertThat(claimed).as("el JWT nuevo no incluye la membresia INACTIVE").isEmpty();
            assertThat(status("Bearer " + access, a.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
        } else {
            assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    @DisplayName("usuario INACTIVE: no inicia sesion, no renueva y su JWT previo deja de valer")
    void inactiveUserIsLockedOut() throws Exception {
        User admin = userRepository.findById(a.admin().getId()).orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userRepository.save(admin);
        String token = fixture.bearer(admin);
        RefreshToken rt = refreshTokenRepository.save(RefreshToken.builder()
                .user(admin).token("rt-" + UUID.randomUUID()).expiresAt(OffsetDateTime.now().plusDays(7)).build());
        admin.setStatus(UserStatus.INACTIVE);
        userRepository.save(admin);

        assertThat(status(token, a.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
        int login = status(null, null, post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"tenantId\":\"%s\"}".formatted(admin.getEmail(), PASSWORD, a.tenantId())));
        assertThat(login).isGreaterThanOrEqualTo(400);
        int refresh = status(null, null, post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rt.getToken() + "\"}"));
        assertThat(refresh).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("varias comunidades: perder la administracion de A no afecta a su acceso legitimo como residente de B")
    void multiCommunityUserKeepsOtherAccess() throws Exception {
        User both = account("doble", RoleType.COMMUNITY_ADMIN, a.tenantId());
        membershipRepository.save(Membership.builder().user(both).tenantId(b.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        Resident home = Resident.builder().property(b.vacant()).user(both).name(both.getFullName()).type("Propietario")
                .email(both.getEmail()).status(ResidentStatus.ACTIVE).build();
        home.setTenantId(b.tenantId());
        residentRepository.save(home);
        String token = fixture.bearer(both);

        assertThat(status(token, a.tenantId(), get("/api/v1/residents"))).isEqualTo(200);
        assertThat(status(token, b.tenantId(), get("/api/v1/residents"))).isEqualTo(403);
        assertThat(status(token, b.tenantId(), get("/api/v1/resident/me/properties"))).isEqualTo(200);

        Membership adminA = membershipOf(both, a.tenantId());
        adminA.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(adminA);
        assertThat(status(token, a.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
        assertThat(status(token, b.tenantId(), get("/api/v1/resident/me/properties"))).isEqualTo(200);
    }

    @Test
    @DisplayName("RESIDENT y SECURITY no adquieren permisos administrativos (403, sin cambios)")
    void residentAndSecurityStayNonAdministrative() throws Exception {
        String before = snapshot();
        for (User u : List.of(a.owner(), a.guard())) {
            String token = fixture.bearer(u);
            assertThat(status(token, a.tenantId(), createStaff("STAFF"))).isEqualTo(403);
            assertThat(status(token, a.tenantId(), renameTenant(a.tenantId(), "Por no admin"))).isEqualTo(403);
            assertThat(status(token, a.tenantId(), get("/api/v1/tenants"))).isEqualTo(403);
            assertThat(status(token, a.tenantId(), renameVacant(a))).isEqualTo(403);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    // ── Contencion de la auditoria (P0) ───────────────────────────────────────

    @Test
    @DisplayName("POST /audit/events: residentes y administracion de comunidad no registran eventos; el SUPER_ADMIN global si, con identidad e IP del servidor")
    void auditEventsAreInternalAndServerAttributed() throws Exception {
        String forged = "{\"actorName\":\"Administrador Principal\",\"actorEmail\":\"falso@example.test\",\"actorRole\":\"SUPER_ADMIN\",\"action\":\"%s\",\"resource\":\"Residentes\",\"tenantId\":\"%s\",\"result\":\"SUCCESS\",\"details\":\"-\",\"ipAddress\":\"10.0.0.1\"}";
        for (User u : List.of(a.owner(), a.admin(), a.guard())) {
            String action = "FORJADO_" + UUID.randomUUID().toString().substring(0, 8);
            assertThat(status(fixture.bearer(u), a.tenantId(), post("/api/v1/audit/events").contentType(MediaType.APPLICATION_JSON)
                    .content(forged.formatted(action, b.tenantId())))).isEqualTo(403);
            assertThat(auditEventRepository.findAll()).noneMatch(e -> action.equals(e.getAction()));
        }

        User sa = account("sa-audit", RoleType.SUPER_ADMIN, null);
        String action = "PANEL_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(status(fixture.bearer(sa), a.tenantId(), post("/api/v1/audit/events").contentType(MediaType.APPLICATION_JSON)
                .content(forged.formatted(action, b.tenantId())))).isEqualTo(201);
        AuditEvent stored = auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).findFirst().orElseThrow();
        assertThat(stored.getActorEmail()).isEqualTo(sa.getEmail());
        assertThat(stored.getActorName()).isEqualTo(sa.getFullName());
        assertThat(stored.getActorRole()).isEqualTo("SUPER_ADMIN");
        assertThat(stored.getIpAddress()).isNotEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("el catalogo de roles asignables: STAFF y SECURITY para la administracion de comunidad; los globales solo para el SUPER_ADMIN global")
    void assignableRolesFollowScope() throws Exception {
        String community = body(fixture.bearer(a.admin()), a.tenantId(), get("/api/v1/roles/assignable"));
        List<String> codes = new java.util.ArrayList<>();
        objectMapper.readTree(community).get("data").forEach(r -> codes.add(r.get("code").asText()));
        assertThat(codes).containsExactlyInAnyOrder("SECURITY", "STAFF");

        String scoped = body(fixture.bearer(account("sa-cat", RoleType.SUPER_ADMIN, a.tenantId())), a.tenantId(), get("/api/v1/roles/assignable"));
        assertThat(scoped).doesNotContain("PLATFORM_ADMIN").doesNotContain("\"SUPER_ADMIN\"");

        String global = body(fixture.bearer(account("sa-cat-g", RoleType.SUPER_ADMIN, null)), a.tenantId(), get("/api/v1/roles/assignable"));
        assertThat(global).contains("\"SUPER_ADMIN\"").contains("PLATFORM_ADMIN");
    }
}
