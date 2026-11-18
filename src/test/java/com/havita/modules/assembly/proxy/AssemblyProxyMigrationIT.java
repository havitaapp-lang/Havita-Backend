package com.havita.modules.assembly.proxy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 2 / P1 — migracion V102 (assembly_proxies) en PostgreSQL real aislado (Testcontainers), con SQL directo:
 * <ul>
 *   <li>base vacia: V100 + V101 + V102, checksums de V100/V101 sin cambios y objetos esperados creados;</li>
 *   <li>copia sintetica del esquema existente: base en V101 con datos sinteticos, despues V102: datos intactos;</li>
 *   <li>restricciones: FK multi-tenant, CHECK, coherencia de estados, transiciones e inmutabilidad (trigger),
 *       historial de eventos de solo insercion y unicidad del poder vigente, tambien en carrera.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class AssemblyProxyMigrationIT {

    private static final int ROUNDS = 8;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("proxy_migration_it");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> LEGACY = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("proxy_legacy_it");

    private static Flyway flyway(PostgreSQLContainer<?> c, String target) {
        var cfg = Flyway.configure().dataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword())
                .locations("classpath:db/flyway").table("havita_schema_history").cleanDisabled(true).baselineOnMigrate(false);
        if (target != null) cfg = cfg.target(target);
        return cfg.load();
    }

    @BeforeAll
    static void migrate() {
        flyway(PG, null).migrate();
    }

    // ── utilidades JDBC ───────────────────────────────────────────────────────

    private static Connection conn(PostgreSQLContainer<?> c) throws SQLException {
        return DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
    }

    private static void exec(PostgreSQLContainer<?> c, String sql, Object... args) throws SQLException {
        try (Connection cn = conn(c)) {
            exec(cn, sql, args);
        }
    }

    private static void exec(Connection cn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static long count(PostgreSQLContainer<?> c, String sql, Object... args) throws SQLException {
        try (Connection cn = conn(c); PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    @FunctionalInterface
    interface SqlAction {
        void run() throws SQLException;
    }

    private static SQLException failure(SqlAction a) {
        try {
            a.run();
        } catch (SQLException e) {
            return e;
        }
        throw new AssertionError("se esperaba un error SQL");
    }

    private static String constraintOf(SQLException e) {
        try {
            Object msg = e.getClass().getMethod("getServerErrorMessage").invoke(e);
            return msg == null ? null : (String) msg.getClass().getMethod("getConstraint").invoke(msg);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static void assertRejected(String sqlState, String constraint, SqlAction a) {
        SQLException e = failure(a);
        assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState);
        if (constraint != null) assertThat(constraintOf(e)).as(e.getMessage()).isEqualTo(constraint);
    }

    // ── datos sinteticos ──────────────────────────────────────────────────────

    record World(UUID tenant, UUID owner, UUID ownerRel, UUID unit, UUID assembly, UUID admin) {}

    private static UUID tenant(PostgreSQLContainer<?> c) throws SQLException {
        UUID id = UUID.randomUUID();
        String s = "p1-" + id.toString().substring(0, 8);
        exec(c, "insert into tenants (id, name, slug, subdomain, status, storage_limit_mb, storage_used_mb, created_at, updated_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 1024, 0, now(), now())", id, "Comunidad " + s, s, s);
        return id;
    }

    private static UUID user(PostgreSQLContainer<?> c) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "insert into users (id, email, full_name, password_hash, status, has_completed_onboarding, created_at, updated_at) "
                + "values (?, ?, 'Persona sintetica', '{noop}x', 'ACTIVE', true, now(), now())", id, "u-" + id + "@example.test");
        return id;
    }

    private static UUID unit(PostgreSQLContainer<?> c, UUID tenant) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "insert into properties (id, tenant_id, number, name, type, status, created_at, updated_at) "
                + "values (?, ?, ?, 'Apto', 'Apartamento', 'OCCUPIED', now(), now())", id, tenant, "U-" + id.toString().substring(0, 8));
        return id;
    }

    private static UUID relation(PostgreSQLContainer<?> c, UUID tenant, UUID unit, UUID user, String type) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "insert into residents (id, tenant_id, property_id, user_id, email, name, type, status, is_main_contact, "
                + "registered_at, created_at, updated_at) values (?, ?, ?, ?, ?, 'Relacion sintetica', ?, 'ACTIVE', false, now(), now(), now())",
                id, tenant, unit, user, "r-" + id.toString().substring(0, 8) + "@example.test", type);
        return id;
    }

    private static UUID assembly(PostgreSQLContainer<?> c, UUID tenant) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "insert into assemblies (id, tenant_id, title, type, modality, assembly_date, start_time, status, total_properties, "
                + "registered_attendees, quorum_percentage, created_at, updated_at) values (?, ?, 'Asamblea sintetica', 'ORDINARIA', "
                + "'MIXTA', current_date, '10:00', 'SCHEDULED', 0, 0, 0, now(), now())", id, tenant);
        return id;
    }

    private static World world(PostgreSQLContainer<?> c) throws SQLException {
        UUID t = tenant(c), owner = user(c), u = unit(c, t);
        return new World(t, owner, relation(c, t, u, owner, "Propietario"), u, assembly(c, t), user(c));
    }

    private static final String INSERT = "insert into assembly_proxies (id, tenant_id, assembly_id, property_id, grantor_resident_id, "
            + "grantor_user_id, proxy_user_id, proxy_full_name, proxy_document_type, proxy_document_number, status, document_key, "
            + "document_content_type, document_size_bytes, document_sha256, document_registered_at, document_registered_by_user_id, "
            + "created_by_user_id, created_channel, created_at, updated_at) values (?, ?, ?, ?, ?, ?, NULL, 'Apoderado Sintetico', 'CC', ?, "
            + "'PENDING_REVIEW', ?, 'application/pdf', 10, ?, now(), ?, ?, 'OWNER', now(), now())";

    private static String sha() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static UUID proxy(Connection cn, World w, UUID tenant, UUID assembly, UUID unit, UUID grantorRel) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(cn, INSERT, id, tenant, assembly, unit, grantorRel, w.owner(), "DOC" + id.toString().substring(0, 6),
                "tenants/" + tenant + "/assembly-proxies/2026/11/" + id + "-document.pdf", sha(), w.owner(), w.owner());
        return id;
    }

    private static UUID proxy(World w) throws SQLException {
        try (Connection cn = conn(PG)) {
            return proxy(cn, w, w.tenant(), w.assembly(), w.unit(), w.ownerRel());
        }
    }

    // ── migracion ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("base vacia: V100 + V101 + V102 aplicadas; V100/V101 con los checksums de P0; objetos de V102 creados")
    void emptyDatabase() throws SQLException {
        MigrationInfo[] applied = flyway(PG, null).info().applied();
        // P4: V104 (participaciones y auditoria de votos) se aplica despues de V103; las anteriores no cambian.
        // P5: V105 y V106 despues de V104; las anteriores no cambian.
        assertThat(applied).extracting(m -> m.getVersion().getVersion()).containsExactly("100", "101", "102", "103", "104", "105", "106", "107", "108");
        assertThat(applied[0].getChecksum()).isEqualTo(-156649982);
        assertThat(applied[1].getChecksum()).isEqualTo(-789330964);
        // P2: V102 sin cambios desde P1 (md5 del archivo registrado al cerrar P1).
        try (var in = getClass().getResourceAsStream("/db/flyway/V102__assembly_proxies.sql")) {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(in.readAllBytes());
            assertThat(java.util.HexFormat.of().formatHex(digest)).isEqualTo("fca6cb4d5fe5c1f781e797f4f1eaa3b7");
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        // P4: V103 sin cambios desde P2 (md5 del archivo registrado al cerrar P2).
        try (var in = getClass().getResourceAsStream("/db/flyway/V103__assembly_proxy_attendance.sql")) {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(in.readAllBytes());
            assertThat(java.util.HexFormat.of().formatHex(digest)).isEqualTo("537627f9fd7bb36a98a81c8aac8e5119");
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        // P5: V104 sin cambios desde P4 (md5 del archivo registrado al cerrar P4).
        try (var in = getClass().getResourceAsStream("/db/flyway/V104__poll_participations_and_vote_events.sql")) {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(in.readAllBytes());
            assertThat(java.util.HexFormat.of().formatHex(digest)).isEqualTo("c6c5f39b5a6da45b0dbfd037006fcc1b");
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        for (String c : List.of("assembly_proxies_pkey", "fk_assembly_proxies_assembly", "fk_assembly_proxies_property",
                "fk_assembly_proxies_grantor_resident", "fk_assembly_proxies_tenant", "assembly_proxies_status_check",
                "assembly_proxies_state_data_check", "assembly_proxies_not_self_check", "uq_assembly_proxies_document_key",
                "uq_assemblies_id_tenant", "uq_properties_id_tenant", "uq_residents_id_tenant_property", "fk_assembly_proxy_events_proxy")) {
            assertThat(count(PG, "select count(*) from pg_constraint where conname = ?", c)).as(c).isEqualTo(1);
        }
        assertThat(count(PG, "select count(*) from pg_indexes where indexname = 'uq_assembly_proxy_live_per_unit' "
                + "and indexdef like '%UNIQUE%' and indexdef like '%PENDING_REVIEW%'")).isEqualTo(1);
        for (String t : List.of("trg_assembly_proxies_guard", "trg_assembly_proxy_events_append_only", "trg_assembly_proxy_events_no_truncate")) {
            assertThat(count(PG, "select count(*) from pg_trigger where tgname = ? and not tgisinternal", t)).as(t).isEqualTo(1);
        }
        assertThat(flyway(PG, null).validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("copia sintetica del esquema existente (V101 con datos) + V102: migra y los datos previos no cambian")
    void syntheticExistingSchema() throws SQLException {
        flyway(LEGACY, "101").migrate();
        UUID t = tenant(LEGACY), u = unit(LEGACY, t), owner = user(LEGACY);
        UUID rel = relation(LEGACY, t, u, owner, "Propietario");
        UUID a = assembly(LEGACY, t);
        String before = fingerprint();
        flyway(LEGACY, null).migrate();
        assertThat(fingerprint()).as("datos previos intactos").isEqualTo(before);
        assertThat(count(LEGACY, "select count(*) from assembly_proxies")).isZero();
        // el modelo funciona sobre los datos existentes
        World w = new World(t, owner, rel, u, a, owner);
        try (Connection cn = conn(LEGACY)) {
            proxy(cn, w, t, a, u, rel);
        }
        assertThat(count(LEGACY, "select count(*) from assembly_proxies where tenant_id = ?", t)).isEqualTo(1);
    }

    private static String fingerprint() throws SQLException {
        try (Connection cn = conn(LEGACY); var st = cn.createStatement(); var rs = st.executeQuery(
                "select md5(string_agg(x, '|' order by x)) from (select 'tenants'||to_jsonb(t)::text x from tenants t "
                        + "union all select 'properties'||to_jsonb(p)::text from properties p union all select 'residents'||to_jsonb(r)::text from residents r "
                        + "union all select 'users'||to_jsonb(u)::text from users u union all select 'assemblies'||to_jsonb(a)::text from assemblies a) s")) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ── restricciones ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("multi-tenant: asamblea, unidad u otorgante de otra comunidad (o de otra unidad) rechazados por FK")
    void crossTenantRejected() throws SQLException {
        World a = world(PG), b = world(PG);
        try (Connection cn = conn(PG)) {
            assertRejected("23503", "fk_assembly_proxies_assembly", () -> proxy(cn, a, a.tenant(), b.assembly(), a.unit(), a.ownerRel()));
            assertRejected("23503", "fk_assembly_proxies_property", () -> proxy(cn, a, a.tenant(), a.assembly(), b.unit(), b.ownerRel()));
            UUID otherUnit = unit(PG, a.tenant());
            UUID otherRel = relation(PG, a.tenant(), otherUnit, user(PG), "Propietario");
            assertRejected("23503", "fk_assembly_proxies_grantor_resident", () -> proxy(cn, a, a.tenant(), a.assembly(), a.unit(), otherRel));
        }
        assertThat(count(PG, "select count(*) from assembly_proxies where tenant_id in (?, ?)", a.tenant(), b.tenant())).isZero();
    }

    @Test
    @DisplayName("un solo poder vigente por asamblea y unidad; los finales liberan la representacion")
    void oneLivePerUnit() throws SQLException {
        World w = world(PG);
        UUID first = proxy(w);
        assertRejected("23505", "uq_assembly_proxy_live_per_unit", () -> proxy(w));
        exec(PG, "update assembly_proxies set status = 'REJECTED', reviewed_by_user_id = ?, reviewed_at = now(), "
                + "review_decision = 'REJECTED', rejection_reason = 'Documento ilegible' where id = ?", w.admin(), first);
        proxy(w); // permitido tras el rechazo
    }

    @Test
    @DisplayName("estados: valor invalido, aprobar sin revisor, rechazar sin motivo, transicion prohibida e identidad fija tras aprobar")
    void stateIntegrity() throws SQLException {
        World w = world(PG);
        UUID p = proxy(w);
        assertRejected("23514", "assembly_proxies_transition", () -> exec(PG, "update assembly_proxies set status = 'FOO' where id = ?", p));
        try (Connection cn = conn(PG)) {
            World wx = world(PG);
            assertRejected("23514", "assembly_proxies_status_check", () -> exec(cn, INSERT.replace("'PENDING_REVIEW'", "'FOO'"),
                    UUID.randomUUID(), wx.tenant(), wx.assembly(), wx.unit(), wx.ownerRel(), wx.owner(), "DX",
                    "k-" + UUID.randomUUID(), sha(), wx.owner(), wx.owner()));
        }
        assertRejected("23514", "assembly_proxies_state_data_check", () -> exec(PG,
                "update assembly_proxies set status = 'APPROVED' where id = ?", p));
        assertRejected("23514", "assembly_proxies_state_data_check", () -> exec(PG, "update assembly_proxies set status = 'REJECTED', "
                + "reviewed_by_user_id = ?, reviewed_at = now(), review_decision = 'REJECTED' where id = ?", w.admin(), p));
        exec(PG, "update assembly_proxies set status = 'APPROVED', reviewed_by_user_id = ?, reviewed_at = now(), review_decision = 'APPROVED' "
                + "where id = ?", w.admin(), p);
        assertRejected("23514", "assembly_proxies_identity_locked", () -> exec(PG,
                "update assembly_proxies set proxy_document_number = 'OTRO' where id = ?", p));
        assertRejected("23514", "assembly_proxies_identity_locked", () -> exec(PG,
                "update assembly_proxies set document_sha256 = ? where id = ?", sha(), p));
        assertRejected("23514", "assembly_proxies_immutable", () -> exec(PG,
                "update assembly_proxies set grantor_user_id = ? where id = ?", w.admin(), p));
        assertRejected("23514", "assembly_proxies_transition", () -> exec(PG,
                "update assembly_proxies set status = 'PENDING_REVIEW' where id = ?", p));
        assertRejected("23514", "assembly_proxies_history_locked", () -> exec(PG,
                "update assembly_proxies set reviewed_by_user_id = ? where id = ?", w.owner(), p));
        // APPROVED -> USED permitido solo con su asistencia por apoderado (V103); USED -> REVOKED prohibido.
        useWithAttendance(w, p);
        assertRejected("23514", "assembly_proxies_transition", () -> exec(PG,
                "update assembly_proxies set status = 'REVOKED', revoked_at = now(), revoked_by_user_id = ? where id = ?", w.owner(), p));
        assertRejected("23514", "assembly_proxies_no_delete", () -> exec(PG, "delete from assembly_proxies where id = ?", p));
        // REJECTED -> APPROVED prohibido.
        World w2 = world(PG);
        UUID r = proxy(w2);
        exec(PG, "update assembly_proxies set status = 'REJECTED', reviewed_by_user_id = ?, reviewed_at = now(), "
                + "review_decision = 'REJECTED', rejection_reason = 'x' where id = ?", w2.admin(), r);
        assertRejected("23514", null, () -> exec(PG, "update assembly_proxies set status = 'APPROVED', review_decision = 'APPROVED' where id = ?", r));
        // CHECK de datos: tipo de documento, apoderado = otorgante, documento vacio.
        World w3 = world(PG);
        try (Connection cn = conn(PG)) {
            assertRejected("23514", "assembly_proxies_not_self_check", () -> exec(cn, INSERT.replace("NULL, 'Apoderado", "?, 'Apoderado"),
                    UUID.randomUUID(), w3.tenant(), w3.assembly(), w3.unit(), w3.ownerRel(), w3.owner(), w3.owner(), "D1",
                    "k-" + UUID.randomUUID(), sha(), w3.owner(), w3.owner()));
            assertRejected("23514", "assembly_proxies_document_type_check", () -> exec(cn, INSERT.replace("'CC'", "'LICENCIA'"),
                    UUID.randomUUID(), w3.tenant(), w3.assembly(), w3.unit(), w3.ownerRel(), w3.owner(), "D2",
                    "k-" + UUID.randomUUID(), sha(), w3.owner(), w3.owner()));
            assertRejected("23514", "assembly_proxies_sha256_check", () -> exec(cn, INSERT, UUID.randomUUID(), w3.tenant(), w3.assembly(),
                    w3.unit(), w3.ownerRel(), w3.owner(), "D3", "k-" + UUID.randomUUID(), "no-es-un-hash", w3.owner(), w3.owner()));
        }
    }

    @Test
    @DisplayName("historial de eventos: solo insercion (UPDATE, DELETE y TRUNCATE rechazados)")
    void eventsAppendOnly() throws SQLException {
        World w = world(PG);
        UUID p = proxy(w);
        UUID e = UUID.randomUUID();
        exec(PG, "insert into assembly_proxy_events (id, tenant_id, proxy_id, assembly_id, property_id, actor_user_id, actor_channel, "
                + "action, result, from_status, to_status) values (?, ?, ?, ?, ?, ?, 'OWNER', 'PROXY_REQUESTED', 'SUCCESS', NULL, 'PENDING_REVIEW')",
                e, w.tenant(), p, w.assembly(), w.unit(), w.owner());
        assertRejected("23514", "assembly_proxy_events_append_only", () -> exec(PG, "update assembly_proxy_events set result = 'DENIED' where id = ?", e));
        assertRejected("23514", "assembly_proxy_events_append_only", () -> exec(PG, "delete from assembly_proxy_events where id = ?", e));
        assertRejected("23514", "assembly_proxy_events_append_only", () -> exec(PG, "truncate assembly_proxy_events"));
        assertRejected("23514", "assembly_proxy_events_action_check", () -> exec(PG, "insert into assembly_proxy_events (id, tenant_id, "
                + "assembly_id, actor_channel, action, result) values (?, ?, ?, 'OWNER', 'BORRAR_TODO', 'SUCCESS')", UUID.randomUUID(), w.tenant(), w.assembly()));
        assertThat(count(PG, "select count(*) from assembly_proxy_events where id = ?", e)).isEqualTo(1);
    }

    @Test
    @DisplayName("carrera · dos poderes a la vez para la misma unidad y asamblea (conexiones independientes): uno solo")
    void raceTwoLiveProxies() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world(PG);
            UUID coOwner = user(PG);
            UUID coRel = relation(PG, w.tenant(), w.unit(), coOwner, "Propietario");
            World co = new World(w.tenant(), coOwner, coRel, w.unit(), w.assembly(), w.admin());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> fs = new ArrayList<>();
            for (World who : List.of(w, co)) {
                fs.add(pool.submit(() -> {
                    try (Connection cn = conn(PG)) {
                        cn.setAutoCommit(false);
                        go.await();
                        try {
                            proxy(cn, who, who.tenant(), who.assembly(), who.unit(), who.ownerRel());
                            Thread.sleep(40);
                            cn.commit();
                            return "OK";
                        } catch (SQLException e) {
                            cn.rollback();
                            return e.getSQLState() + ":" + constraintOf(e);
                        }
                    }
                }));
            }
            go.countDown();
            List<String> r = new ArrayList<>();
            for (Future<String> f : fs) r.add(f.get(60, TimeUnit.SECONDS));
            pool.shutdown();
            assertThat(r).as("ronda " + round).containsExactlyInAnyOrder("OK", "23505:uq_assembly_proxy_live_per_unit");
            assertThat(count(PG, "select count(*) from assembly_proxies where assembly_id = ? and property_id = ?", w.assembly(), w.unit())).isEqualTo(1);
        }
    }

    // ── Fase 2 / P2: V103 (uso del poder ligado a su asistencia) ─────────────

    private static UUID attendee(Connection cn, UUID tenant, UUID assembly, UUID unit, boolean poa) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(cn, "insert into assembly_attendees (id, tenant_id, assembly_id, property_id, resident_id, coefficient, "
                + "is_power_of_attorney, representative_name, checked_in_at, created_at, updated_at) values (?, ?, ?, ?, NULL, 0.5, ?, "
                + "'Apoderado Sintetico', now(), now(), now())", id, tenant, assembly, unit, poa);
        return id;
    }

    private static void approve(World w, UUID proxy) throws SQLException {
        exec(PG, "update assembly_proxies set status = 'APPROVED', reviewed_by_user_id = ?, reviewed_at = now(), "
                + "review_decision = 'APPROVED' where id = ?", w.admin(), proxy);
    }

    /** Uso real del poder en UNA transaccion: asistencia por apoderado + APPROVED -> USED con esa asistencia. */
    private static UUID useWithAttendance(World w, UUID proxy) throws SQLException {
        try (Connection cn = conn(PG)) {
            cn.setAutoCommit(false);
            UUID att = attendee(cn, w.tenant(), w.assembly(), w.unit(), true);
            exec(cn, "update assembly_proxies set status = 'USED', used_at = now(), attendee_id = ?, used_channel = 'PROXY' where id = ?", att, proxy);
            cn.commit();
            return att;
        }
    }

    private static SQLException failOnCommit(SqlAction inTx) throws SQLException {
        try (Connection cn = conn(PG)) {
            cn.setAutoCommit(false);
            CURRENT.set(cn);
            try {
                inTx.run();
                cn.commit();
            } catch (SQLException e) {
                cn.rollback();
                return e;
            } finally {
                CURRENT.remove();
            }
        }
        throw new AssertionError("se esperaba un rechazo");
    }

    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

    @Test
    @DisplayName("P2/V103 · USED exige su asistencia por apoderado de la misma unidad, asamblea y comunidad; y viceversa")
    void usedRequiresMatchingAttendance() throws SQLException {
        World w = world(PG);
        UUID p = proxy(w);
        approve(w, p);
        // USED sin asistencia: lo rechaza el trigger (antes que el CHECK used_attendee).
        SQLException noAttendance = failure(() -> exec(PG, "update assembly_proxies set status = 'USED', used_at = now() where id = ?", p));
        assertThat(noAttendance.getSQLState()).isEqualTo("23514");
        assertThat(constraintOf(noAttendance)).isIn("assembly_proxies_attendee_kind", "assembly_proxies_used_attendee_check");
        // Asistencia de OTRA unidad de la misma comunidad (FK compuesta).
        UUID otherUnit = unit(PG, w.tenant());
        try (Connection cn = conn(PG)) {
            cn.setAutoCommit(false); // la asistencia por apoderado huerfana no llega a confirmarse
            UUID other = attendee(cn, w.tenant(), w.assembly(), otherUnit, true);
            assertRejected("23503", "fk_assembly_proxies_attendee", () -> exec(cn,
                    "update assembly_proxies set status = 'USED', used_at = now(), attendee_id = ?, used_channel = 'PROXY' where id = ?", other, p));
            cn.rollback();
        }
        // Asistencia que NO es por apoderado.
        World w2 = world(PG);
        UUID p2 = proxy(w2);
        approve(w2, p2);
        try (Connection cn = conn(PG)) {
            UUID plain = attendee(cn, w2.tenant(), w2.assembly(), w2.unit(), false);
            assertRejected("23514", "assembly_proxies_attendee_kind", () -> exec(cn,
                    "update assembly_proxies set status = 'USED', used_at = now(), attendee_id = ?, used_channel = 'PROXY' where id = ?", plain, p2));
        }
        // Registrada por la administracion sin verificacion de identidad.
        World w3 = world(PG);
        UUID p3 = proxy(w3);
        approve(w3, p3);
        SQLException noVerification = failOnCommit(() -> {
            UUID att = attendee(CURRENT.get(), w3.tenant(), w3.assembly(), w3.unit(), true);
            exec(CURRENT.get(), "update assembly_proxies set status = 'USED', used_at = now(), attendee_id = ?, used_channel = 'ADMIN' where id = ?", att, p3);
        });
        assertThat(noVerification.getSQLState()).isEqualTo("23514");
        assertThat(constraintOf(noVerification)).isEqualTo("assembly_proxies_used_attendee_check");
        // Asistencia por apoderado sin poder USED: rechazada AL CONFIRMAR (trigger diferido).
        World w4 = world(PG);
        SQLException orphan = failOnCommit(() -> attendee(CURRENT.get(), w4.tenant(), w4.assembly(), w4.unit(), true));
        assertThat(orphan.getSQLState()).isEqualTo("23514");
        assertThat(constraintOf(orphan)).isEqualTo("assembly_attendees_proxy_required");
        assertThat(count(PG, "select count(*) from assembly_attendees where assembly_id = ?", w4.assembly())).isZero();
        // Uso correcto (el intento anterior se revirtio: p3 sigue APPROVED); despues la asistencia y el canal no cambian.
        assertThat(count(PG, "select count(*) from assembly_proxies where id = ? and status = 'APPROVED'", p3)).isEqualTo(1);
        UUID att = useWithAttendance(w3, p3);
        assertRejected("23514", "assembly_proxies_history_locked", () -> exec(PG,
                "update assembly_proxies set used_channel = 'ADMIN' where attendee_id = ?", att));
    }

    @Test
    @DisplayName("P2/V103 · asistencias historicas por apoderado (anteriores a V103) siguen siendo validas y modificables")
    void legacyPowerOfAttorneyRowsUntouched() throws SQLException {
        try (var c = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("proxy_legacy_poa")) {
            c.start();
            flyway(c, "102").migrate();
            UUID t = tenant(c), u = unit(c, t), a = assembly(c, t);
            UUID legacy;
            try (Connection cn = conn(c)) {
                legacy = attendee(cn, t, a, u, true); // fila POA historica (sin poder verificable)
            }
            flyway(c, null).migrate();
            exec(c, "update assembly_attendees set coefficient = 0.75, updated_at = now() where id = ?", legacy);
            assertThat(count(c, "select count(*) from assembly_attendees where id = ? and is_power_of_attorney", legacy)).isEqualTo(1);
        }
    }
}
