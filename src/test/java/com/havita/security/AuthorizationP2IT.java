package com.havita.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.havita.modules.staff.infrastructure.StaffMemberRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
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

/**
 * SEC-9 (P2): comunidades inexistentes (SEC-9-6), vinculacion de cuentas al personal (SEC-9-7), datos sensibles en los
 * logs (SEC-9-8), catalogo de roles para quien no administra, y regresiones de P1 no cubiertas (rollback, lock_timeout).
 * Datos sinteticos (dominio example.test) sobre PostgreSQL de Testcontainers.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthorizationP2IT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Clave-Segura-2027";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private StaffMemberRepository staffMemberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    // ── utilidades ────────────────────────────────────────────────────────────

    private User account(String handle) {
        return userRepository.save(User.builder()
                .email(handle + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash(passwordEncoder.encode(PASSWORD)).fullName("Cuenta " + handle).phone("3000000000").build());
    }

    private Membership grant(User u, UUID tenantId, RoleType role) {
        return membershipRepository.save(Membership.builder().user(u).tenantId(tenantId).role(role).status(MembershipStatus.ACTIVE).build());
    }

    private MvcResult call(String token, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        if (token != null) req.header("Authorization", token);
        if (tenant != null) req.header("X-Tenant-ID", tenant.toString());
        return mvc.perform(req).andReturn();
    }

    private int status(String token, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        return call(token, tenant, req).getResponse().getStatus();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder createStaff(String extraJson) {
        String doc = UUID.randomUUID().toString().substring(0, 9);
        return post("/api/v1/staff").contentType(MediaType.APPLICATION_JSON).content(
                "{\"firstName\":\"Personal\",\"lastName\":\"Sintetico\",\"documentNumber\":\"%s\",\"position\":\"Auxiliar\"%s}"
                        .formatted(doc, extraJson));
    }

    private MockHttpServletRequestBuilder linkStaff(UUID userId) {
        return createStaff(",\"userId\":\"" + userId + "\"");
    }

    private MockHttpServletRequestBuilder login(String email, String password, UUID tenantId) {
        String tenant = tenantId == null ? "" : ",\"tenantId\":\"" + tenantId + "\"";
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"%s}".formatted(email, password, tenant));
    }

    /** Estado que las operaciones rechazadas no deben tocar. */
    private String snapshot() {
        return membershipRepository.count() + "|" + userRepository.count() + "|"
                + staffMemberRepository.countByTenantId(a.tenantId()) + "|" + staffMemberRepository.countByTenantId(b.tenantId());
    }

    // ── 1. Comunidades inexistentes (SEC-9-6) ─────────────────────────────────

    @Test
    @DisplayName("SEC-9-6: un SUPER_ADMIN global con X-Tenant-ID inexistente no opera (401) ni escribe; con una comunidad real si")
    void globalRoleWithNonexistentTenantIsAnonymous() throws Exception {
        User sa = account("sa-global");
        grant(sa, null, RoleType.SUPER_ADMIN);
        String token = fixture.bearer(sa);
        UUID ghost = UUID.randomUUID();
        String before = snapshot();

        assertThat(status(token, ghost, get("/api/v1/residents"))).isEqualTo(401);
        assertThat(status(token, ghost, createStaff(""))).isEqualTo(401);
        assertThat(status(token, ghost, post("/api/v1/sync/stream-ticket"))).isEqualTo(401);
        assertThat(snapshot()).isEqualTo(before);

        // Controles: el acceso global legitimo no cambia.
        assertThat(status(token, b.tenantId(), get("/api/v1/residents"))).isEqualTo(200);
        assertThat(status(token, null, get("/api/v1/tenants"))).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-9-6: una membresia de una comunidad inexistente no concede nada, ni con nombre de rol global; no se modifica")
    void orphanMembershipGrantsNothing() throws Exception {
        UUID ghost = UUID.randomUUID();
        User admin = account("admin-huerfano");
        Membership orphan = grant(admin, ghost, RoleType.COMMUNITY_ADMIN);
        User scopedSa = account("sa-huerfano");
        Membership orphanSa = grant(scopedSa, ghost, RoleType.SUPER_ADMIN);
        String before = snapshot();

        assertThat(status(fixture.bearer(admin), ghost, get("/api/v1/residents"))).isEqualTo(401);
        assertThat(status(fixture.bearer(admin), null, get("/api/v1/residents"))).isEqualTo(401);
        assertThat(status(fixture.bearer(admin), ghost, createStaff(""))).isEqualTo(401);
        assertThat(status(fixture.bearer(scopedSa), null, get("/api/v1/tenants"))).isIn(401, 403);
        assertThat(status(fixture.bearer(scopedSa), b.tenantId(), get("/api/v1/residents"))).isIn(401, 403);

        // Las membresias historicas no se borran ni se modifican.
        assertThat(snapshot()).isEqualTo(before);
        assertThat(membershipRepository.findById(orphan.getId()).orElseThrow().getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membershipRepository.findById(orphanSa.getId()).orElseThrow().getRole()).isEqualTo(RoleType.SUPER_ADMIN);
    }

    @Test
    @DisplayName("SEC-9-6: una membresia huerfana no desplaza la legitima: sin X-Tenant-ID se resuelve la comunidad real")
    void orphanMembershipDoesNotShadowLegitimateOne() throws Exception {
        User admin = account("admin-doble");
        grant(admin, UUID.randomUUID(), RoleType.COMMUNITY_ADMIN);
        grant(admin, a.tenantId(), RoleType.COMMUNITY_ADMIN);
        String token = fixture.bearer(admin);

        MvcResult noHeader = call(token, null, get("/api/v1/residents"));
        assertThat(noHeader.getResponse().getStatus()).isEqualTo(200);
        assertThat(noHeader.getResponse().getContentAsString()).contains(a.ownerResident().getId().toString());
        assertThat(status(token, a.tenantId(), get("/api/v1/residents"))).isEqualTo(200);
        assertThat(status(token, b.tenantId(), get("/api/v1/residents"))).isIn(401, 403);
    }

    @Test
    @DisplayName("SEC-9-6: el login ignora membresias de comunidades inexistentes y no permite elegir una")
    void loginIgnoresOrphanMemberships() throws Exception {
        UUID ghost = UUID.randomUUID();
        User u = account("personal-doble");
        grant(u, ghost, RoleType.STAFF);
        grant(u, a.tenantId(), RoleType.STAFF);

        MvcResult ok = mvc.perform(login(u.getEmail(), PASSWORD, null)).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(body).doesNotContain(ghost.toString()).contains(a.tenantId().toString()).doesNotContain("Unknown");

        MvcResult chosen = mvc.perform(login(u.getEmail(), PASSWORD, ghost)).andReturn();
        assertThat(chosen.getResponse().getStatus()).isEqualTo(409);
        assertThat(chosen.getResponse().getContentAsString()).contains("TENANT_MEMBERSHIP_REQUIRED");
    }

    // ── 2. Vinculacion de cuentas existentes al personal (SEC-9-7) ────────────

    @Test
    @DisplayName("SEC-9-7: la administracion de A no vincula cuentas de B; 404 igual que un id inexistente y sin cambios")
    void cannotLinkAccountsOfAnotherCommunity() throws Exception {
        String admin = fixture.bearer(a.admin());
        String before = snapshot();

        MvcResult foreign = call(admin, a.tenantId(), linkStaff(b.guard().getId()));
        MvcResult missing = call(admin, a.tenantId(), linkStaff(UUID.randomUUID()));
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(foreign).path("code").asText())
                .as("misma respuesta: no permite saber si la cuenta existe en otra comunidad")
                .isNotBlank()
                .isEqualTo(json(missing).path("code").asText());
        assertThat(foreign.getResponse().getContentAsString()).doesNotContain(b.guard().getEmail());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-9-7: solo cuentas ACTIVE con membresia ACTIVE STAFF/SECURITY en la comunidad; sin crear roles ni duplicar")
    void linkingRequiresLegitimateRelationship() throws Exception {
        String admin = fixture.bearer(a.admin());
        String before = snapshot();

        // Residente de A (rol no permitido) -> 409.
        assertThat(status(admin, a.tenantId(), linkStaff(a.owner().getId()))).isEqualTo(409);
        // Combinar con creacion de acceso o rol -> 409, sin tocar la membresia de la cuenta.
        assertThat(status(admin, a.tenantId(), createStaff(",\"userId\":\"" + a.guard().getId() + "\",\"createSystemAccess\":true,\"password\":\"" + PASSWORD + "\""))).isEqualTo(409);
        assertThat(status(admin, a.tenantId(), createStaff(",\"userId\":\"" + a.guard().getId() + "\",\"systemRole\":\"COMMUNITY_ADMIN\""))).isEqualTo(409);
        assertThat(membershipRepository.findByUserIdAndTenantId(a.guard().getId(), a.tenantId()).orElseThrow().getRole()).isEqualTo(RoleType.SECURITY);
        // Membresia INACTIVE -> 409.
        Membership guardMembership = membershipRepository.findByUserIdAndTenantId(a.guard().getId(), a.tenantId()).orElseThrow();
        guardMembership.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(guardMembership);
        assertThat(status(admin, a.tenantId(), linkStaff(a.guard().getId()))).isEqualTo(409);
        guardMembership.setStatus(MembershipStatus.ACTIVE);
        membershipRepository.save(guardMembership);
        // Cuenta INACTIVE -> 409.
        User other = account("porteria-inactiva");
        grant(other, a.tenantId(), RoleType.SECURITY);
        other.setStatus(UserStatus.INACTIVE);
        userRepository.save(other);
        assertThat(status(admin, a.tenantId(), linkStaff(other.getId()))).isEqualTo(409);
        assertThat(staffMemberRepository.countByTenantId(a.tenantId())).isZero();

        // Caso legitimo: la porteria de A, una sola vez.
        long memberships = membershipRepository.count();
        assertThat(status(admin, a.tenantId(), linkStaff(a.guard().getId()))).isEqualTo(201);
        assertThat(staffMemberRepository.findByTenantIdAndUserId(a.tenantId(), a.guard().getId())).isPresent();
        assertThat(membershipRepository.count()).as("vincular no crea membresias").isEqualTo(memberships);
        assertThat(status(admin, a.tenantId(), linkStaff(a.guard().getId()))).isEqualTo(409);
        assertThat(before).isNotEqualTo(snapshot());
    }

    @Test
    @DisplayName("SEC-9-7: RESIDENT, SECURITY y STAFF no pueden vincular cuentas (403)")
    void nonAdministratorsCannotLink() throws Exception {
        User staff = account("staff-a");
        grant(staff, a.tenantId(), RoleType.STAFF);
        for (User u : new User[]{a.owner(), a.guard(), staff}) {
            assertThat(status(fixture.bearer(u), a.tenantId(), linkStaff(a.guard().getId()))).isEqualTo(403);
        }
        assertThat(staffMemberRepository.countByTenantId(a.tenantId())).isZero();
    }

    // ── 3. Catalogo de roles para quien no administra ─────────────────────────

    @Test
    @DisplayName("roles asignables: RESIDENT y SECURITY reciben una lista vacia; la administracion de comunidad, solo STAFF y SECURITY")
    void assignableRolesAreEmptyForNonAdministrators() throws Exception {
        for (User u : new User[]{a.owner(), a.guard()}) {
            JsonNode data = json(call(fixture.bearer(u), a.tenantId(), get("/api/v1/roles/assignable"))).path("data");
            assertThat(data.isArray() && data.isEmpty()).as("lista vacia para " + u.getEmail()).isTrue();
        }
        JsonNode admin = json(call(fixture.bearer(a.admin()), a.tenantId(), get("/api/v1/roles/assignable"))).path("data");
        assertThat(admin.findValuesAsText("code")).containsExactlyInAnyOrder("STAFF", "SECURITY");
    }

    // ── 4. Datos sensibles en los logs (SEC-9-8) ──────────────────────────────

    @Test
    @DisplayName("SEC-9-8: los inicios de sesion fallidos no escriben el correo ni la contrasena; si el motivo y el id")
    void failedLoginsDoNotLogEmails(CapturedOutput output) throws Exception {
        String unknown = "sonda-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String wrongPassword = "Clave-Erronea-" + UUID.randomUUID().toString().substring(0, 6);
        User u = account("victima");

        assertThat(mvc.perform(login(unknown, wrongPassword, null)).andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.perform(login(u.getEmail(), wrongPassword, null)).andReturn().getResponse().getStatus()).isEqualTo(409);

        String logs = output.getAll();
        assertThat(logs).doesNotContain(unknown).doesNotContain(u.getEmail()).doesNotContain(wrongPassword)
                .doesNotContain("DEBUG LOGIN");
        assertThat(logs).contains("motivo=UNKNOWN_ACCOUNT").contains("motivo=BAD_CREDENTIALS userId=" + u.getId());
    }

    @Test
    @DisplayName("SEC-9-8: las excepciones de dominio no copian al log datos personales del mensaje; el cliente si recibe el mensaje")
    void domainExceptionMessagesStayOutOfLogs(CapturedOutput output) throws Exception {
        String doc = "DOC" + UUID.randomUUID().toString().substring(0, 8);
        String admin = fixture.bearer(a.admin());
        String body = "{\"firstName\":\"Personal\",\"lastName\":\"Sintetico\",\"documentNumber\":\"%s\",\"position\":\"Auxiliar\"}".formatted(doc);
        assertThat(status(admin, a.tenantId(), post("/api/v1/staff").contentType(MediaType.APPLICATION_JSON).content(body))).isEqualTo(201);
        int before = output.getAll().length();
        MvcResult dup = call(admin, a.tenantId(), post("/api/v1/staff").contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(dup.getResponse().getContentAsString()).contains(doc);
        String newLogs = output.getAll().substring(before);
        assertThat(newLogs).contains("STAFF_DOCUMENT_EXISTS").doesNotContain(doc);
    }

    @Test
    @DisplayName("correlacion: un X-Correlation-ID valido se conserva; uno con saltos de linea o demasiado largo se sustituye")
    void correlationIdCannotForgeLogLines() throws Exception {
        String valid = "trace-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(mvc.perform(get("/api/v1/health").header("X-Correlation-ID", valid)).andReturn()
                .getResponse().getHeader("X-Correlation-ID")).isEqualTo(valid);
        for (String bad : new String[]{"x\r\nWARN falso", "x".repeat(200), "a b"}) {
            String echoed = mvc.perform(get("/api/v1/health").header("X-Correlation-ID", bad)).andReturn()
                    .getResponse().getHeader("X-Correlation-ID");
            assertThat(echoed).isNotEqualTo(bad);
            assertThat(UUID.fromString(echoed)).isNotNull();
        }
    }

    // ── 5. Regresiones de P1 no cubiertas ─────────────────────────────────────

    @Test
    @DisplayName("P1 rollback: una revocacion revertida no revoca sesiones ni quita el acceso")
    void rolledBackRevocationHasNoEffect() throws Exception {
        RefreshToken rt = refreshTokenRepository.save(RefreshToken.builder()
                .user(a.admin()).token("rt-" + UUID.randomUUID()).expiresAt(OffsetDateTime.now().plusDays(7)).build());
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            Membership m = membershipRepository.findByUserIdAndTenantId(a.admin().getId(), a.tenantId()).orElseThrow();
            m.setStatus(MembershipStatus.INACTIVE);
            membershipRepository.saveAndFlush(m); // el listener JPA publica el evento en el flush
            tx.setRollbackOnly();
        });
        assertThat(membershipRepository.findByUserIdAndTenantId(a.admin().getId(), a.tenantId()).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.ACTIVE);
        assertThat(refreshTokenRepository.findById(rt.getId()).orElseThrow().isRevoked()).as("sin AFTER_COMMIT no se revoca").isFalse();
        assertThat(status(fixture.bearer(a.admin()), a.tenantId(), get("/api/v1/residents"))).isEqualTo(200);
    }

    @Test
    @DisplayName("P1 lock_timeout: si la membresia sigue bloqueada mas de 5 s, la operacion critica responde 409 sin efectos")
    void criticalOperationTimesOutWith409() throws Exception {
        String jwt = fixture.bearer(a.admin());
        String before = propertyRepository.findById(a.vacant().getId()).orElseThrow().getName();
        MockHttpServletRequestBuilder critical = put("/api/v1/properties/{id}", a.vacant().getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"number\":\"%s\",\"name\":\"Tras espera\",\"status\":\"VACANT\"}".formatted(a.vacant().getNumber()));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
                // Bloqueo exclusivo de la fila (como un UPDATE en curso) sin cambiar nada; se revierte al final.
                jdbc.queryForList("select id from memberships where user_id = ? and tenant_id = ? for update",
                        a.admin().getId(), a.tenantId());
                holding.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                s.setRollbackOnly();
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            MvcResult r = pool.submit(() -> call(jwt, a.tenantId(), critical)).get(30, TimeUnit.SECONDS);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            assertThat(r.getResponse().getStatus()).isEqualTo(409);
            assertThat(r.getResponse().getContentAsString()).contains("CONCURRENT_ACCESS_CHANGE");
            assertThat(waitedMs).as("acotado por lock_timeout=5s").isBetween(4_000L, 15_000L);
            assertThat(propertyRepository.findById(a.vacant().getId()).orElseThrow().getName()).isEqualTo(before);
            // Liberado el bloqueo, la misma operacion legitima funciona.
            assertThat(status(jwt, a.tenantId(), critical)).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
