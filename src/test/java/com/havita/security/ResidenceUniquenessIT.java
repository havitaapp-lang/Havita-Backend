package com.havita.security;

import com.havita.common.api.ErrorResponse;
import com.havita.common.exception.GlobalExceptionHandler;
import com.havita.common.exception.UniqueConstraintConflicts;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 2 / P0-B — integridad de residencia y arrendamiento en el ESQUEMA PREPARADO PARA FLYWAY (classpath:db/flyway,
 * V100 + V101), con SQL directo sobre PostgreSQL real (contenedor propio de Testcontainers, independiente del modo de la
 * suite). Comprueba los indices unicos parciales reales:
 * <ul>
 *   <li>uq_active_arrendatario_per_property: un arrendatario ACTIVE por unidad;</li>
 *   <li>uq_active_non_owner_residence_per_user / _per_email: una sola residencia NO PROPIETARIA (Residente o
 *       Arrendatario) ACTIVE por persona en TODA Havita (regla de negocio confirmada); los propietarios no estan
 *       limitados;</li>
 *   <li>uq_pending_arrendatario_invitation: una invitacion de arrendatario PENDING por unidad.</li>
 * </ul>
 * Los rechazos se verifican por SQLSTATE 23505 y por el nombre de la restriccion; las carreras usan conexiones y
 * transacciones independientes y comprueban el estado final.
 */
@Testcontainers(disabledWithoutDocker = true)
class ResidenceUniquenessIT {

    private static final int ROUNDS = 8;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("residence_it");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/flyway").table("havita_schema_history")
                .cleanDisabled(true).baselineOnMigrate(false).load().migrate();
    }

    // ── datos sinteticos ──────────────────────────────────────────────────────

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = conn()) {
            exec(c, sql, args);
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static UUID tenant() throws SQLException {
        UUID id = UUID.randomUUID();
        String s = "p0b-" + id.toString().substring(0, 8);
        exec("insert into tenants (id, name, slug, subdomain, status, storage_limit_mb, storage_used_mb, created_at, updated_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 1024, 0, now(), now())", id, "Comunidad " + s, s, s);
        return id;
    }

    private static UUID user() throws SQLException {
        UUID id = UUID.randomUUID();
        exec("insert into users (id, email, full_name, password_hash, status, has_completed_onboarding, created_at, updated_at) "
                + "values (?, ?, 'Persona sintetica', '{noop}x', 'ACTIVE', true, now(), now())", id, "u-" + id + "@example.test");
        return id;
    }

    private static UUID unit(UUID tenant) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("insert into properties (id, tenant_id, number, name, type, status, created_at, updated_at) "
                + "values (?, ?, ?, 'Apto', 'Apartamento', 'OCCUPIED', now(), now())", id, tenant, "U-" + id.toString().substring(0, 8));
        return id;
    }

    private static String residentSql() {
        return "insert into residents (id, tenant_id, property_id, user_id, email, name, type, status, is_main_contact, "
                + "registered_at, created_at, updated_at) values (?, ?, ?, ?, ?, 'Relacion sintetica', ?, ?, false, now(), now(), now())";
    }

    private static UUID resident(UUID tenant, UUID unit, UUID user, String email, String type, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(residentSql(), id, tenant, unit, user, email, type, status);
        return id;
    }

    private static String email() {
        return "r-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    /** La operacion debe fallar con 23505 en la restriccion indicada (y no dejar la fila). */
    private static void assertRejectedBy(String constraint, SqlAction action) {
        try {
            action.run();
            throw new AssertionError("se esperaba un rechazo por " + constraint);
        } catch (SQLException e) {
            assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo("23505");
            assertThat(constraintOf(e)).isEqualTo(constraint);
        }
    }

    /**
     * Nombre de la restriccion que rechazo la operacion, leido del mensaje de error del servidor que adjunta el driver
     * (PSQLException#getServerErrorMessage().getConstraint()). Por reflexion: el driver es runtimeOnly en el build.
     */
    private static String constraintOf(SQLException e) {
        try {
            Object msg = e.getClass().getMethod("getServerErrorMessage").invoke(e);
            return msg == null ? null : (String) msg.getClass().getMethod("getConstraint").invoke(msg);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    // ── casos de negocio ──────────────────────────────────────────────────────

    @Test
    @DisplayName("caso 1 · propietario de dos apartamentos en comunidades distintas: permitido")
    void ownerInTwoCommunities() throws SQLException {
        UUID u = user();
        String mail = email();
        resident(tenant(), unit(tenant()), u, mail, "Propietario", "ACTIVE");
        UUID t = tenant();
        resident(t, unit(t), u, mail, "Propietario", "ACTIVE");
        assertThat(count("select count(*) from residents where user_id = ? and status = 'ACTIVE'", u)).isEqualTo(2);
    }

    @Test
    @DisplayName("casos 2/3 · arrendatario o residente no propietario activo en dos comunidades: rechazado (por cuenta y por correo)")
    void nonOwnerInTwoCommunities() throws SQLException {
        UUID a = tenant(), b = tenant();
        UUID u = user();
        resident(a, unit(a), u, email(), "Arrendatario", "ACTIVE");
        assertRejectedBy("uq_active_non_owner_residence_per_user",
                () -> resident(b, unit(b), u, email(), "Arrendatario", "ACTIVE"));
        assertRejectedBy("uq_active_non_owner_residence_per_user",
                () -> resident(b, unit(b), u, email(), "Residente", "ACTIVE"));
        // Sin cuenta vinculada: el mismo correo (sin distinguir mayusculas) tambien identifica a la persona.
        String mail = email();
        resident(a, unit(a), null, mail, "Residente", "ACTIVE");
        assertRejectedBy("uq_active_non_owner_residence_per_email",
                () -> resident(b, unit(b), null, mail.toUpperCase(), "Arrendatario", "ACTIVE"));
        assertThat(count("select count(*) from residents where user_id = ? and status = 'ACTIVE'", u)).isEqualTo(1);
    }

    @Test
    @DisplayName("caso 4 · propietario en una comunidad y residente no propietario en otra: permitido")
    void ownerHereNonOwnerThere() throws SQLException {
        UUID a = tenant(), b = tenant();
        UUID u = user();
        String mail = email();
        resident(a, unit(a), u, mail, "Propietario", "ACTIVE");
        resident(b, unit(b), u, mail, "Arrendatario", "ACTIVE");
        assertThat(count("select count(*) from residents where user_id = ? and status = 'ACTIVE'", u)).isEqualTo(2);
    }

    @Test
    @DisplayName("casos 5/6 · dos arrendatarios activos en un apartamento: rechazado; el anterior dado de baja y uno nuevo: permitido")
    void oneActiveTenantPerUnit() throws SQLException {
        UUID t = tenant(), apt = unit(t);
        UUID first = resident(t, apt, user(), email(), "Arrendatario", "ACTIVE");
        assertRejectedBy("uq_active_arrendatario_per_property",
                () -> resident(t, apt, user(), email(), "Arrendatario", "ACTIVE"));
        // Transicion: baja del anterior y alta del nuevo.
        exec("update residents set status = 'INACTIVE' where id = ?", first);
        UUID second = resident(t, apt, user(), email(), "Arrendatario", "ACTIVE");
        // Y el anterior ya no puede reactivarse mientras el nuevo siga activo.
        assertRejectedBy("uq_active_arrendatario_per_property",
                () -> exec("update residents set status = 'ACTIVE' where id = ?", first));
        assertThat(count("select count(*) from residents where property_id = ? and type = 'Arrendatario' and status = 'ACTIVE'", apt)).isEqualTo(1);
        assertThat(second).isNotNull();
    }

    @Test
    @DisplayName("caso 7 · reactivar una residencia no propietaria con otra ya activa para la misma persona: rechazado")
    void reactivationWithAnotherActive() throws SQLException {
        UUID a = tenant(), b = tenant();
        UUID u = user();
        UUID old = resident(a, unit(a), u, email(), "Residente", "INACTIVE");
        resident(b, unit(b), u, email(), "Arrendatario", "ACTIVE");
        assertRejectedBy("uq_active_non_owner_residence_per_user",
                () -> exec("update residents set status = 'ACTIVE' where id = ?", old));
        assertThat(count("select count(*) from residents where id = ? and status = 'INACTIVE'", old)).isEqualTo(1);
    }

    @Test
    @DisplayName("caso 8 · varias propiedades y una unica residencia no propietaria activa: permitido")
    void severalPropertiesOneNonOwnerResidence() throws SQLException {
        UUID a = tenant(), b = tenant();
        UUID u = user();
        String mail = email();
        resident(a, unit(a), u, mail, "Propietario", "ACTIVE");
        resident(a, unit(a), u, mail, "Propietario", "ACTIVE");
        resident(b, unit(b), u, mail, "Propietario", "ACTIVE");
        resident(b, unit(b), u, mail, "Residente", "ACTIVE");
        assertThat(count("select count(*) from residents where user_id = ? and status = 'ACTIVE'", u)).isEqualTo(4);
        // Una segunda residencia no propietaria, aunque sea en la misma comunidad, se rechaza. La fila viola a la vez los
        // indices por cuenta y por correo; PostgreSQL informa del primero que comprueba (orden no garantizado).
        try {
            resident(a, unit(a), u, mail, "Residente", "ACTIVE");
            throw new AssertionError("se esperaba un rechazo por residencia no propietaria duplicada");
        } catch (SQLException e) {
            assertThat(e.getSQLState()).isEqualTo("23505");
            assertThat(constraintOf(e)).isIn("uq_active_non_owner_residence_per_user", "uq_active_non_owner_residence_per_email");
        }
    }

    @Test
    @DisplayName("invitaciones · una invitacion de arrendatario PENDING por unidad")
    void onePendingTenantInvitationPerUnit() throws SQLException {
        UUID t = tenant(), apt = unit(t);
        String sql = "insert into resident_invitations (id, tenant_id, property_id, token_hash, invitation_type, status, expires_at, "
                + "max_uses, used_count, created_at, updated_at) values (?, ?, ?, ?, 'Arrendatario', ?, now() + interval '1 day', 1, 0, now(), now())";
        exec(sql, UUID.randomUUID(), t, apt, hash(), "PENDING");
        assertRejectedBy("uq_pending_arrendatario_invitation", () -> exec(sql, UUID.randomUUID(), t, apt, hash(), "PENDING"));
        exec(sql, UUID.randomUUID(), t, apt, hash(), "USED"); // historicas: permitidas
    }

    private static String hash() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    @DisplayName("brechas documentadas (no corregidas en P0-B): tipo con otra capitalizacion y relacion sin cuenta ni correo quedan fuera de los indices")
    void documentedGaps() throws SQLException {
        // 1) Los indices comparan type con 'Arrendatario'/'Residente' exactos; la API no restringe los valores de type.
        UUID t = tenant(), apt = unit(t);
        resident(t, apt, user(), email(), "Arrendatario", "ACTIVE");
        resident(t, apt, user(), email(), "arrendatario", "ACTIVE");
        assertThat(count("select count(*) from residents where property_id = ? and lower(type) = 'arrendatario' and status = 'ACTIVE'", apt))
                .as("brecha: dos arrendatarios activos si uno se guarda en minusculas").isEqualTo(2);
        // 2) Una relacion de censo sin cuenta y sin correo no identifica a la persona: no hay unicidad posible.
        UUID a = tenant(), b = tenant();
        resident(a, unit(a), null, null, "Residente", "ACTIVE");
        resident(b, unit(b), null, null, "Residente", "ACTIVE");
    }

    // ── concurrencia (conexiones y transacciones independientes) ─────────────

    /** Ejecuta las operaciones a la vez, cada una en su conexion y transaccion; devuelve null (commit) o el SQLSTATE. */
    private static List<String> race(List<SqlAction> ops) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(ops.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (SqlAction op : ops) {
            fs.add(pool.submit((Callable<String>) () -> {
                try (Connection c = conn()) {
                    c.setAutoCommit(false);
                    start.await();
                    try {
                        CURRENT.set(c);
                        op.run();
                        Thread.sleep(40); // la transaccion sigue abierta: la otra espera en el indice unico
                        c.commit();
                        return null;
                    } catch (SQLException e) {
                        c.rollback();
                        return e.getSQLState() + ":" + constraintOf(e);
                    } finally {
                        CURRENT.remove();
                    }
                }
            }));
        }
        start.countDown();
        List<String> out = new ArrayList<>();
        for (Future<String> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

    private static void inTx(String sql, Object... args) throws SQLException {
        exec(CURRENT.get(), sql, args);
    }

    private static void assertOneWins(List<String> results, String constraint, String round) {
        assertThat(results.stream().filter(r -> r == null).count()).as(round + " " + results).isEqualTo(1);
        assertThat(results.stream().filter(r -> r != null)).as(round).allMatch(r -> r.equals("23505:" + constraint));
    }

    @Test
    @DisplayName("carrera 1 · dos arrendatarios activos para la misma unidad a la vez: uno solo")
    void raceTwoTenantsSameUnit() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID t = tenant(), apt = unit(t);
            UUID u1 = user(), u2 = user();
            List<String> r = race(List.of(
                    () -> inTx(residentSql(), UUID.randomUUID(), t, apt, u1, email(), "Arrendatario", "ACTIVE"),
                    () -> inTx(residentSql(), UUID.randomUUID(), t, apt, u2, email(), "Arrendatario", "ACTIVE")));
            assertOneWins(r, "uq_active_arrendatario_per_property", "ronda " + round);
            assertThat(count("select count(*) from residents where property_id = ? and status = 'ACTIVE'", apt)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("carrera 2 · dos residencias no propietarias activas para la misma persona en comunidades distintas a la vez: una sola")
    void raceTwoNonOwnerResidencesSamePerson() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID a = tenant(), b = tenant(), aptA = unit(a), aptB = unit(b);
            UUID u = user();
            String mail = email();
            List<String> r = race(List.of(
                    () -> inTx(residentSql(), UUID.randomUUID(), a, aptA, u, mail, "Arrendatario", "ACTIVE"),
                    () -> inTx(residentSql(), UUID.randomUUID(), b, aptB, u, mail, "Residente", "ACTIVE")));
            assertThat(r.stream().filter(x -> x == null).count()).as("ronda " + round + " " + r).isEqualTo(1);
            assertThat(r.stream().filter(x -> x != null)).allMatch(x -> x.startsWith("23505:uq_active_non_owner_residence_per_"));
            assertThat(count("select count(*) from residents where user_id = ? and status = 'ACTIVE'", u)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("carrera 3 · reactivar a la vez dos relaciones incompatibles de la misma persona: una sola queda activa")
    void raceConcurrentReactivations() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID a = tenant(), b = tenant();
            UUID u = user();
            String mail = email();
            UUID r1 = resident(a, unit(a), u, mail, "Residente", "INACTIVE");
            UUID r2 = resident(b, unit(b), u, mail, "Arrendatario", "INACTIVE");
            List<String> r = race(List.of(
                    () -> inTx("update residents set status = 'ACTIVE' where id = ?", r1),
                    () -> inTx("update residents set status = 'ACTIVE' where id = ?", r2)));
            assertThat(r.stream().filter(x -> x == null).count()).as("ronda " + round + " " + r).isEqualTo(1);
            assertThat(r.stream().filter(x -> x != null)).allMatch(x -> x.startsWith("23505:uq_active_non_owner_residence_per_"));
            assertThat(count("select count(*) from residents where user_id = ? and status = 'ACTIVE'", u)).isEqualTo(1);
        }
    }

    // ── P0-C ──────────────────────────────────────────────────────────────────

    private static SQLException failure(SqlAction action) {
        try {
            action.run();
        } catch (SQLException e) {
            return e;
        }
        throw new AssertionError("se esperaba un error SQL");
    }

    @Test
    @DisplayName("P0-C D3 · bypass directo: PostgreSQL acepta cualquier texto en residents.type (sin CHECK); solo NULL se rechaza")
    void directSqlBypassOfType() throws SQLException {
        // Brecha documentada (sin CHECK): estos valores solo los rechaza la aplicacion (ResidentTypes); por SQL directo entran.
        for (String invalid : List.of("OWNER", "TENANT", "RESIDENT", "owner", "tenant", "resident", "", "desconocido")) {
            UUID t = tenant();
            UUID id = resident(t, unit(t), user(), email(), invalid, "ACTIVE");
            assertThat(count("select count(*) from residents where id = ?", id)).as("'" + invalid + "'").isEqualTo(1);
        }
        // Un tipo desconocido queda fuera de los indices: dos 'TENANT' activos en la misma unidad no se bloquean.
        UUID t = tenant(), apt = unit(t);
        resident(t, apt, user(), email(), "TENANT", "ACTIVE");
        resident(t, apt, user(), email(), "TENANT", "ACTIVE");
        assertThat(count("select count(*) from residents where property_id = ? and status = 'ACTIVE'", apt)).isEqualTo(2);
        // NOT NULL si protege la columna.
        UUID t2 = tenant(), apt2 = unit(t2);
        SQLException nul = failure(() -> resident(t2, apt2, user(), email(), null, "ACTIVE"));
        assertThat(nul.getSQLState()).isEqualTo("23502");
    }

    @Test
    @DisplayName("P0-C D7 · solo las violaciones de los indices de residencia conocidos se clasifican como conflicto (excepciones reales del driver)")
    void classifierOnlyKnownUniqueViolations() throws SQLException {
        UUID t = tenant(), apt = unit(t);
        resident(t, apt, user(), email(), "Arrendatario", "ACTIVE");
        SQLException tenantDup = failure(() -> resident(t, apt, user(), email(), "Arrendatario", "ACTIVE"));

        UUID u = user();
        String mail = email();
        UUID a = tenant(), b = tenant();
        resident(a, unit(a), u, mail, "Residente", "ACTIVE");
        UUID aptB = unit(b);
        SQLException nonOwnerDup = failure(() -> resident(b, aptB, u, mail, "Residente", "ACTIVE"));

        String inv = "insert into resident_invitations (id, tenant_id, property_id, token_hash, invitation_type, status, expires_at, "
                + "max_uses, used_count, created_at, updated_at) values (?, ?, ?, ?, 'Arrendatario', 'PENDING', now() + interval '1 day', 1, 0, now(), now())";
        UUID apt2 = unit(t);
        exec(inv, UUID.randomUUID(), t, apt2, hash());
        SQLException inviteDup = failure(() -> exec(inv, UUID.randomUUID(), t, apt2, hash()));

        assertThat(UniqueConstraintConflicts.classify(new DataIntegrityViolationException("x", tenantDup)))
                .get().extracting(UniqueConstraintConflicts.Conflict::code).isEqualTo("SECOND_ACTIVE_TENANT");
        assertThat(UniqueConstraintConflicts.classify(new DataIntegrityViolationException("x", nonOwnerDup)))
                .get().extracting(UniqueConstraintConflicts.Conflict::code).isEqualTo("ALREADY_ACTIVE_IN_ANOTHER_PROPERTY");
        assertThat(UniqueConstraintConflicts.classify(new DataIntegrityViolationException("x", inviteDup)))
                .get().extracting(UniqueConstraintConflicts.Conflict::code).isEqualTo("PENDING_TENANT_INVITATION");

        // Otros errores de integridad reales: NO son conflictos de negocio.
        UUID dupSlugTenant = UUID.randomUUID();
        String slug = "slug-" + dupSlugTenant.toString().substring(0, 8);
        exec("insert into tenants (id, name, slug, subdomain, status, storage_limit_mb, storage_used_mb, created_at, updated_at) "
                + "values (?, 'X', ?, ?, 'ACTIVE', 1024, 0, now(), now())", dupSlugTenant, slug, slug);
        SQLException otherUnique = failure(() -> exec("insert into tenants (id, name, slug, subdomain, status, storage_limit_mb, "
                + "storage_used_mb, created_at, updated_at) values (?, 'Y', ?, ?, 'ACTIVE', 1024, 0, now(), now())",
                UUID.randomUUID(), slug, slug + "-otro"));
        SQLException foreignKey = failure(() -> resident(t, UUID.randomUUID(), null, email(), "Propietario", "ACTIVE"));
        UUID apt3 = unit(t), apt4 = unit(t);
        SQLException check = failure(() -> resident(t, apt3, null, email(), "Propietario", "BOGUS"));
        SQLException notNull = failure(() -> resident(t, apt4, null, email(), null, "ACTIVE"));
        assertThat(otherUnique.getSQLState()).isEqualTo("23505");
        assertThat(foreignKey.getSQLState()).isEqualTo("23503");
        assertThat(check.getSQLState()).isEqualTo("23514");
        assertThat(notNull.getSQLState()).isEqualTo("23502");
        for (SQLException e : List.of(otherUnique, foreignKey, check, notNull)) {
            assertThat(UniqueConstraintConflicts.classify(new DataIntegrityViolationException("x", e))).as(e.getSQLState()).isEmpty();
        }
        // Mismo SQLSTATE 23505 sin el campo de restriccion del servidor (otro driver o excepcion construida): no se clasifica.
        SQLException textOnly = new SQLException(
                "duplicate key value violates unique constraint uq_active_arrendatario_per_property", "23505");
        assertThat(UniqueConstraintConflicts.classify(new DataIntegrityViolationException("x", textOnly)))
                .as("el texto del mensaje no basta").isEmpty();

        // Respuesta HTTP del manejador global: 409 con codigo estable para lo conocido, 500 generico para lo demas.
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/residents");
        ResponseEntity<ErrorResponse> known = handler.handleDataIntegrityViolation(new DataIntegrityViolationException("x", tenantDup), req);
        assertThat(known.getStatusCode().value()).isEqualTo(409);
        assertThat(known.getBody().code()).isEqualTo("SECOND_ACTIVE_TENANT");
        assertNoInternals(known.getBody());
        for (SQLException e : List.of(otherUnique, foreignKey, check, notNull, textOnly)) {
            ResponseEntity<ErrorResponse> other = handler.handleDataIntegrityViolation(new DataIntegrityViolationException("x", e), req);
            assertThat(other.getStatusCode().value()).as(e.getSQLState()).isEqualTo(500);
            assertThat(other.getBody().code()).isEqualTo("INTERNAL_SERVER_ERROR");
            assertNoInternals(other.getBody());
        }
    }

    private static void assertNoInternals(ErrorResponse body) {
        String text = body.message() + " " + body.title() + " " + body.type();
        for (String internal : List.of("uq_", "residents", "resident_invitations", "tenants", "constraint", "duplicate key",
                "SQL", "insert", "@example.test")) {
            assertThat(text).as(internal).doesNotContainIgnoringCase(internal);
        }
    }

    @Test
    @DisplayName("P0-C carrera 4 · dos invitaciones de arrendatario PENDING para la misma unidad a la vez: una sola")
    void raceTwoPendingTenantInvitations() throws Exception {
        String inv = "insert into resident_invitations (id, tenant_id, property_id, token_hash, invitation_type, status, expires_at, "
                + "max_uses, used_count, created_at, updated_at) values (?, ?, ?, ?, 'Arrendatario', 'PENDING', now() + interval '1 day', 1, 0, now(), now())";
        for (int round = 0; round < ROUNDS; round++) {
            UUID t = tenant(), apt = unit(t);
            List<String> r = race(List.of(
                    () -> inTx(inv, UUID.randomUUID(), t, apt, hash()),
                    () -> inTx(inv, UUID.randomUUID(), t, apt, hash())));
            assertOneWins(r, "uq_pending_arrendatario_invitation", "ronda " + round);
            assertThat(count("select count(*) from resident_invitations where property_id = ? and status = 'PENDING'", apt)).isEqualTo(1);
        }
    }
}
