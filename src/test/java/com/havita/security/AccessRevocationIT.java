package com.havita.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SseService;
import com.havita.modules.sync.domain.SyncEvent;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * SEC-9 (P1): al perder un permiso se revocan las sesiones que ya no dan acceso a nada, se cierran de inmediato las
 * conexiones SSE que dependian de el (sin tocar las de otras comunidades), las operaciones criticas vuelven a
 * comprobar el permiso dentro de su transaccion (una revocacion concurrente no se adelanta) y los eventos de
 * auditoria llevan la identidad del servidor.
 */
class AccessRevocationIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Clave-Segura-2027";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private SseService sseService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
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

    private int status(String token, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        if (token != null) req.header("Authorization", token);
        if (tenant != null) req.header("X-Tenant-ID", tenant.toString());
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private RefreshToken session(User user) {
        return refreshTokenRepository.save(RefreshToken.builder()
                .user(user).token("rt-" + UUID.randomUUID()).expiresAt(OffsetDateTime.now().plusDays(7)).build());
    }

    private boolean revoked(RefreshToken rt) {
        return refreshTokenRepository.findById(rt.getId()).orElseThrow().isRevoked();
    }

    private MvcResult refresh(RefreshToken rt) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rt.getToken() + "\"}")).andReturn();
    }

    private Membership membership(User u, UUID tenant) {
        return membershipRepository.findByUserIdAndTenantId(u.getId(), tenant).orElseThrow();
    }

    private void deactivate(Membership m) {
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
    }

    /** Administrador de A que ademas es propietario (RESIDENT) en B, con su relacion activa. */
    private User adminOfAAndResidentOfB() {
        User u = userRepository.save(User.builder().email("doble-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash(passwordEncoder.encode(PASSWORD)).fullName("Cuenta Doble").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(a.tenantId()).role(RoleType.COMMUNITY_ADMIN).status(MembershipStatus.ACTIVE).build());
        membershipRepository.save(Membership.builder().user(u).tenantId(b.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        Resident home = Resident.builder().property(b.vacant()).user(u).name(u.getFullName()).type("Propietario")
                .email(u.getEmail()).status(ResidentStatus.ACTIVE).build();
        home.setTenantId(b.tenantId());
        residentRepository.save(home);
        return u;
    }

    private String ticket(User user, UUID tenant) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/sync/stream-ticket")
                .header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", tenant.toString())).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("ticket").asText();
    }

    private MvcResult openStream(String ticket) throws Exception {
        return mvc.perform(get("/api/v1/sync/stream").param("ticket", ticket)).andExpect(request().asyncStarted()).andReturn();
    }

    private MockHttpServletRequestBuilder renameVacant(Side side, String name) {
        return put("/api/v1/properties/{id}", side.vacant().getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"number\":\"%s\",\"name\":\"%s\",\"status\":\"VACANT\"}".formatted(side.vacant().getNumber(), name));
    }

    private String vacantName(Side side) {
        return propertyRepository.findById(side.vacant().getId()).orElseThrow().getName();
    }

    // ── Sesiones ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("revocar la unica membresia: se revocan sus sesiones, no renueva y su JWT deja de valer")
    void revokingOnlyMembershipRevokesSessions() throws Exception {
        String jwt = fixture.bearer(a.admin());
        RefreshToken rt = session(a.admin());
        deactivate(membership(a.admin(), a.tenantId()));

        assertThat(revoked(rt)).as("refresh token revocado").isTrue();
        assertThat(refresh(rt).getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(status(jwt, a.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
    }

    @Test
    @DisplayName("desactivar al usuario revoca sus sesiones")
    void deactivatingUserRevokesSessions() throws Exception {
        RefreshToken rt = session(a.admin());
        User u = userRepository.findById(a.admin().getId()).orElseThrow();
        u.setStatus(UserStatus.INACTIVE);
        userRepository.save(u);

        assertThat(revoked(rt)).isTrue();
        assertThat(refresh(rt).getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("dos comunidades: perder la administracion de A no revoca la sesion; renueva sin A y sigue accediendo a B")
    void losingOneCommunityKeepsTheOther() throws Exception {
        User both = adminOfAAndResidentOfB();
        RefreshToken rt = session(both);
        deactivate(membership(both, a.tenantId()));

        assertThat(revoked(rt)).as("conserva acceso legitimo en B").isFalse();
        MvcResult r = refresh(rt);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String access = objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/accessToken").asText();
        assertThat(status("Bearer " + access, a.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
        assertThat(status("Bearer " + access, b.tenantId(), get("/api/v1/resident/me/properties"))).isEqualTo(200);
    }

    @Test
    @DisplayName("tras volver a conceder el permiso, las operaciones legitimas funcionan")
    void legitimateOperationsAfterRegrant() throws Exception {
        Membership m = membership(a.admin(), a.tenantId());
        deactivate(m);
        assertThat(status(fixture.bearer(a.admin()), a.tenantId(), renameVacant(a, "Sin permiso"))).isIn(401, 403);
        m.setStatus(MembershipStatus.ACTIVE);
        membershipRepository.save(m);
        assertThat(status(fixture.bearer(a.admin()), a.tenantId(), renameVacant(a, "Con permiso"))).isEqualTo(200);
        assertThat(vacantName(a)).isEqualTo("Con permiso");
    }

    // ── SSE ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SSE: al revocar la membresia, la conexion abierta se cierra de inmediato y no recibe mas eventos")
    void revocationClosesOpenStreamImmediately() throws Exception {
        MvcResult stream = openStream(ticket(a.admin(), a.tenantId()));
        assertThat(sseService.subscriberCount(a.tenantId())).isEqualTo(1);

        deactivate(membership(a.admin(), a.tenantId()));
        assertThat(sseService.subscriberCount(a.tenantId())).as("cerrada sin esperar a otro evento ni a los 60 s").isZero();

        String marker = "TRAS_REVOCAR_" + UUID.randomUUID().toString().substring(0, 8);
        sseService.publishEvent(new SyncEvent(marker, a.tenantId(), Map.of("x", "y")));
        assertThat(stream.getResponse().getContentAsString()).doesNotContain(marker);
    }

    @Test
    @DisplayName("SSE: perder A cierra solo la conexion de A; la de B (acceso legitimo) y las de otros usuarios siguen abiertas")
    void revocationKeepsOtherStreams() throws Exception {
        User both = adminOfAAndResidentOfB();
        openStream(ticket(both, a.tenantId()));
        MvcResult inB = openStream(ticket(both, b.tenantId()));
        MvcResult guardA = openStream(ticket(a.guard(), a.tenantId()));
        assertThat(sseService.subscriberCount(a.tenantId())).isEqualTo(2);
        assertThat(sseService.subscriberCount(b.tenantId())).isEqualTo(1);

        deactivate(membership(both, a.tenantId()));
        assertThat(sseService.subscriberCount(a.tenantId())).as("queda la de porteria").isEqualTo(1);
        assertThat(sseService.subscriberCount(b.tenantId())).isEqualTo(1);

        String marker = "SIGUE_" + UUID.randomUUID().toString().substring(0, 8);
        sseService.publishEvent(new SyncEvent(marker, b.tenantId(), Map.of("x", "y")));
        sseService.publishEvent(new SyncEvent(marker, a.tenantId(), Map.of("x", "y")));
        assertThat(inB.getResponse().getContentAsString()).contains(marker);
        assertThat(guardA.getResponse().getContentAsString()).contains(marker);
    }

    @Test
    @DisplayName("SSE: con permisos revocados no se reconecta, ni con el JWT antiguo ni con un ticket obtenido antes")
    void cannotReconnectAfterRevocation() throws Exception {
        String jwt = fixture.bearer(a.admin());
        String earlyTicket = ticket(a.admin(), a.tenantId());
        deactivate(membership(a.admin(), a.tenantId()));

        assertThat(status(jwt, a.tenantId(), post("/api/v1/sync/stream-ticket"))).isIn(401, 403);
        int code = mvc.perform(get("/api/v1/sync/stream").param("ticket", earlyTicket)).andReturn().getResponse().getStatus();
        assertThat(code).isIn(401, 403);
        assertThat(sseService.subscriberCount(a.tenantId())).isZero();
    }

    // ── Operaciones criticas y concurrencia ───────────────────────────────────

    /**
     * Mantiene sin confirmar un cambio de permisos del administrador de A mientras lanza una operacion critica. Con la
     * comprobacion transaccional, la operacion espera al cambio y, al confirmarse, se rechaza sin modificar nada.
     */
    private void assertCriticalOperationWaitsForRevocation(String revocationSql, MockHttpServletRequestBuilder critical,
                                                           Runnable assertUnchanged) throws Exception {
        String jwt = fixture.bearer(a.admin());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> revoker = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
                jdbc.update(revocationSql, a.admin().getId(), a.tenantId());
                holding.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> op = pool.submit(() -> status(jwt, a.tenantId(), critical));
            Thread.sleep(1500);
            assertThat(op.isDone()).as("la operacion critica espera a la revocacion en curso").isFalse();
            release.countDown();
            revoker.get(20, TimeUnit.SECONDS);
            assertThat(op.get(20, TimeUnit.SECONDS)).isEqualTo(403);
            assertUnchanged.run();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("TOCTOU: un cambio de rol en curso no puede ser adelantado por una actualizacion administrativa de la unidad")
    void roleChangeDuringCriticalUpdate() throws Exception {
        String before = vacantName(a);
        assertCriticalOperationWaitsForRevocation(
                "update memberships set role = 'RESIDENT' where user_id = ? and tenant_id = ?",
                renameVacant(a, "Escrita en carrera"),
                () -> assertThat(vacantName(a)).isEqualTo(before));
    }

    @Test
    @DisplayName("TOCTOU: una desactivacion en curso no puede ser adelantada por una transferencia de propietario")
    void deactivationDuringTransfer() throws Exception {
        UUID ownerBefore = a.ownerResident().getId();
        assertCriticalOperationWaitsForRevocation(
                "update memberships set status = 'INACTIVE' where user_id = ? and tenant_id = ?",
                post("/api/v1/properties/{id}/transfer-owner", a.home().getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerName\":\"En carrera\",\"newOwnerEmail\":\"carrera-%s@example.test\",\"effectiveDate\":\"%s\"}"
                                .formatted(UUID.randomUUID().toString().substring(0, 6), OffsetDateTime.now())),
                () -> assertThat(residentRepository.findById(ownerBefore).orElseThrow().getStatus()).isEqualTo(ResidentStatus.ACTIVE));
    }

    @Test
    @DisplayName("TOCTOU: una desactivacion en curso no puede ser adelantada por la edicion de la comunidad")
    void deactivationDuringTenantUpdate() throws Exception {
        assertCriticalOperationWaitsForRevocation(
                "update memberships set status = 'INACTIVE' where user_id = ? and tenant_id = ?",
                put("/api/v1/tenants/{id}", a.tenantId()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renombrada en carrera\"}"),
                () -> assertThat(jdbc.queryForObject("select name from tenants where id = ?", String.class, a.tenantId()))
                        .isNotEqualTo("Renombrada en carrera"));
    }

    // ── Auditoria ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("auditoria: el panel envia solo los datos del evento; identidad, rol e IP los pone el servidor y no se guardan tokens")
    void auditEventWithServerIdentity() throws Exception {
        User sa = userRepository.save(User.builder().email("sa-p1-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash(passwordEncoder.encode(PASSWORD)).fullName("Super Admin P1").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(sa).tenantId(null).role(RoleType.SUPER_ADMIN).status(MembershipStatus.ACTIVE).build());
        String action = "PANEL_" + UUID.randomUUID().toString().substring(0, 8);
        String secret = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.firma-falsa-123";
        int code = status(fixture.bearer(sa), a.tenantId(), post("/api/v1/audit/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"%s\",\"resource\":\"Comunidades\",\"tenantId\":\"%s\",\"result\":\"SUCCESS\",\"details\":\"Bearer %s\"}"
                        .formatted(action, b.tenantId(), secret)));
        assertThat(code).as("sin campos de identidad en el cuerpo").isEqualTo(201);

        AuditEvent stored = auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).findFirst().orElseThrow();
        assertThat(stored.getActorEmail()).isEqualTo(sa.getEmail());
        assertThat(stored.getActorRole()).isEqualTo("SUPER_ADMIN");
        assertThat(stored.getTenantId()).isEqualTo(b.tenantId());
        assertThat(stored.getDetails()).doesNotContain(secret);

        // Un cliente antiguo que aun envie identidad no cambia nada: se ignora.
        String action2 = "PANEL_" + UUID.randomUUID().toString().substring(0, 8);
        status(fixture.bearer(sa), a.tenantId(), post("/api/v1/audit/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorName\":\"Otro\",\"actorEmail\":\"otro@example.test\",\"actorRole\":\"RESIDENT\",\"ipAddress\":\"10.9.9.9\",\"action\":\"%s\",\"resource\":\"x\",\"result\":\"SUCCESS\"}"
                        .formatted(action2)));
        AuditEvent legacy = auditEventRepository.findAll().stream().filter(e -> action2.equals(e.getAction())).findFirst().orElseThrow();
        assertThat(legacy.getActorEmail()).isEqualTo(sa.getEmail());
        assertThat(legacy.getIpAddress()).isNotEqualTo("10.9.9.9");
    }

    @Test
    @DisplayName("sin filtraciones: revocar a un administrador de A no revoca sesiones ni conexiones de otros usuarios ni de B")
    void revocationIsScopedToTheAffectedUser() throws Exception {
        RefreshToken otherA = session(a.guard());
        RefreshToken otherB = session(b.admin());
        openStream(ticket(b.admin(), b.tenantId()));
        deactivate(membership(a.admin(), a.tenantId()));

        assertThat(revoked(otherA)).isFalse();
        assertThat(revoked(otherB)).isFalse();
        assertThat(sseService.subscriberCount(b.tenantId())).isEqualTo(1);
        assertThat(status(fixture.bearer(b.admin()), b.tenantId(), get("/api/v1/residents"))).isEqualTo(200);
        assertThat(List.of(otherA, otherB)).allSatisfy(rt -> assertThat(revoked(rt)).isFalse());
    }
}
