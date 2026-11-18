package com.havita.modules.assembly.proxy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
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
 * Fase 2 / P4 — migracion V104 (participaciones, auditoria de votos, unicidad de voto vigente, no reapertura) en
 * PostgreSQL AISLADO (Testcontainers; nunca una base real), sin la aplicacion:
 * <ul>
 *   <li>base vacia: V100-V104 aplicadas, objetos creados, V100-V103 sin cambios;</li>
 *   <li>datos historicos sinteticos (incluidos INCONSISTENTES: dos opciones de una unidad en una SINGLE_CHOICE, votos
 *       sin residente, asamblea finalizada): V104 se aplica sin error y sin modificar ni ocultar ninguna fila; los votos
 *       nuevos quedan protegidos y los historicos no se pueden reescribir;</li>
 *   <li>concurrencia en PostgreSQL: dos INSERT directos simultaneos de la misma unidad y pregunta → uno solo entra.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class VoteLedgerMigrationIT {

    private static final int ROUNDS = 8;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> EMPTY = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("vote_ledger_empty");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> HIST = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("vote_ledger_hist");

    private static Flyway flyway(PostgreSQLContainer<?> c, String target) {
        var cfg = Flyway.configure().dataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword())
                .locations("classpath:db/flyway").table("havita_schema_history").cleanDisabled(true).baselineOnMigrate(false);
        if (target != null) cfg = cfg.target(target);
        return cfg.load();
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

    private static String text(PostgreSQLContainer<?> c, String sql, Object... args) throws SQLException {
        try (Connection cn = conn(c); PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getString(1);
        }
    }

    private static long count(PostgreSQLContainer<?> c, String sql, Object... args) throws SQLException {
        return Long.parseLong(text(c, sql, args));
    }

    private static String constraintOf(SQLException e) {
        try {
            Object msg = e.getClass().getMethod("getServerErrorMessage").invoke(e);
            return msg == null ? null : (String) msg.getClass().getMethod("getConstraint").invoke(msg);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    @FunctionalInterface
    interface SqlAction {
        void run() throws SQLException;
    }

    private static void assertRejected(String sqlState, String constraint, SqlAction a) {
        try {
            a.run();
        } catch (SQLException e) {
            assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState);
            assertThat(constraintOf(e)).as(e.getMessage()).isEqualTo(constraint);
            return;
        }
        throw new AssertionError("se esperaba un error SQL (" + constraint + ")");
    }

    // ── datos historicos sinteticos (antes de V104) ───────────────────────────

    record Hist(UUID tenant, UUID unit, UUID unit2, UUID ownerRel, UUID endedAssembly, UUID liveAssembly,
                UUID closedPoll, UUID livePoll, UUID liveYes, UUID liveNo, UUID closedYes, UUID closedNo) {}

    private static UUID id() {
        return UUID.randomUUID();
    }

    private static Hist seedHistory(PostgreSQLContainer<?> c) throws SQLException {
        UUID t = id(), owner = id(), unit = id(), unit2 = id(), rel = id(), ended = id(), live = id();
        String s = "p4-" + t.toString().substring(0, 8);
        exec(c, "insert into tenants (id, name, slug, subdomain, status, storage_limit_mb, storage_used_mb, created_at, updated_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 1024, 0, now(), now())", t, "Comunidad " + s, s, s);
        exec(c, "insert into users (id, email, full_name, password_hash, status, has_completed_onboarding, created_at, updated_at) "
                + "values (?, ?, 'Persona sintetica', '{noop}x', 'ACTIVE', true, now(), now())", owner, "u-" + owner + "@example.test");
        for (UUID u : List.of(unit, unit2)) {
            exec(c, "insert into properties (id, tenant_id, number, name, type, status, coefficient, created_at, updated_at) "
                    + "values (?, ?, ?, 'Apto', 'Apartamento', 'OCCUPIED', 0.2, now(), now())", u, t, "U-" + u.toString().substring(0, 8));
        }
        exec(c, "insert into residents (id, tenant_id, property_id, user_id, email, name, type, status, is_main_contact, registered_at, "
                + "created_at, updated_at) values (?, ?, ?, ?, ?, 'Propietaria', 'Propietario', 'ACTIVE', false, now(), now(), now())",
                rel, t, unit, owner, "r-" + rel.toString().substring(0, 8) + "@example.test");
        for (UUID[] a : new UUID[][]{{ended}, {live}}) {
            exec(c, "insert into assemblies (id, tenant_id, title, type, modality, assembly_date, start_time, status, total_properties, "
                    + "registered_attendees, quorum_percentage, created_at, updated_at) values (?, ?, 'Asamblea historica', 'ORDINARIA', "
                    + "'MIXTA', current_date, '10:00', ?, 2, 1, 50, now(), now())", a[0], t, a[0] == ended ? "ENDED" : "IN_PROGRESS");
        }
        UUID closedPoll = id(), livePoll = id(), cy = id(), cn = id(), ly = id(), ln = id();
        exec(c, "insert into polls (id, tenant_id, assembly_id, question, type, status, show_results_to_residents, opened_at, closed_at, "
                + "created_at, updated_at) values (?, ?, ?, 'Historica', 'SINGLE_CHOICE', 'CLOSED', true, now(), now(), now(), now())",
                closedPoll, t, ended);
        exec(c, "insert into polls (id, tenant_id, assembly_id, question, type, status, show_results_to_residents, opened_at, "
                + "created_at, updated_at) values (?, ?, ?, 'Abierta durante la adopcion', 'SINGLE_CHOICE', 'LIVE', true, now(), now(), now())",
                livePoll, t, live);
        for (Object[] o : new Object[][]{{cy, closedPoll, "Si", 1}, {cn, closedPoll, "No", 2}, {ly, livePoll, "Si", 1}, {ln, livePoll, "No", 2}}) {
            exec(c, "insert into poll_options (id, poll_id, text, option_order) values (?, ?, ?, ?)", o);
        }
        for (UUID a : List.of(ended, live)) {
            exec(c, "insert into assembly_attendees (id, tenant_id, assembly_id, property_id, resident_id, coefficient, "
                    + "is_power_of_attorney, checked_in_at, created_at, updated_at) values (?, ?, ?, ?, ?, 0.2, false, now(), now(), now())",
                    id(), t, a, unit, rel);
        }
        String vote = "insert into poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, resident_id, coefficient, voted_at, "
                + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, 0.2, now(), now(), now())";
        // INCONSISTENTE: la misma unidad con las DOS opciones de una SINGLE_CHOICE (posible antes de V104, ver V-1).
        exec(c, vote, id(), t, closedPoll, cy, unit, rel);
        exec(c, vote, id(), t, closedPoll, cn, unit, rel);
        // Voto historico sin persona identificada (anterior a D14).
        exec(c, vote, id(), t, closedPoll, cy, unit2, null);
        // Voto historico en una pregunta que sigue ABIERTA cuando se adopta V104.
        exec(c, vote, id(), t, livePoll, ly, unit, rel);
        return new Hist(t, unit, unit2, rel, ended, live, closedPoll, livePoll, ly, ln, cy, cn);
    }

    /** Huella de los datos historicos (columnas ORIGINALES, sin participation_id): debe ser identica antes y despues. */
    private static String fingerprint(PostgreSQLContainer<?> c, UUID tenant) throws SQLException {
        return text(c, "select md5(coalesce((select string_agg(concat_ws('|', id, tenant_id, poll_id, poll_option_id, property_id, "
                + "resident_id, coefficient, voted_at, created_at, updated_at), ',' order by id) from poll_votes where tenant_id = ?), '') "
                + "|| coalesce((select string_agg(concat_ws('|', id, status, closed_at, updated_at), ',' order by id) from polls where tenant_id = ?), '') "
                + "|| coalesce((select string_agg(concat_ws('|', id, status, registered_attendees, quorum_percentage, updated_at), ',' order by id) "
                + "from assemblies where tenant_id = ?), '') "
                + "|| coalesce((select string_agg(concat_ws('|', id, resident_id, coefficient, is_power_of_attorney, updated_at), ',' order by id) "
                + "from assembly_attendees where tenant_id = ?), ''))", tenant, tenant, tenant, tenant);
    }

    // ── pruebas ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("base vacia: V100-V104 aplicadas; V100-V103 intactas; tablas, indices, restricciones y triggers de V104 creados")
    void emptyDatabase() throws SQLException {
        flyway(EMPTY, null).migrate();
        MigrationInfo[] applied = flyway(EMPTY, null).info().applied();
        assertThat(applied).extracting(m -> m.getVersion().getVersion()).containsExactly("100", "101", "102", "103", "104", "105", "106", "107", "108");
        assertThat(applied[0].getChecksum()).isEqualTo(-156649982);
        assertThat(applied[1].getChecksum()).isEqualTo(-789330964);
        assertThat(flyway(EMPTY, null).validateWithResult().validationSuccessful).isTrue();
        for (String t : List.of("poll_participations", "assembly_vote_events")) {
            assertThat(text(EMPTY, "select to_regclass(?)::text", "public." + t)).isEqualTo(t);
        }
        for (String c : List.of("uq_polls_id_scope", "uq_assembly_proxies_id_scope", "uq_voting_delegations_id_scope",
                "poll_participations_pkey", "uq_poll_participations_id_scope", "fk_poll_participations_poll", "fk_poll_participations_attendee",
                "fk_poll_participations_proxy", "fk_poll_participations_delegation", "fk_poll_participations_resident",
                "poll_participations_title_check", "poll_participations_assisted_check", "poll_participations_void_check",
                "fk_poll_votes_participation", "assembly_vote_events_action_check", "assembly_vote_events_result_action_check",
                "fk_assembly_vote_events_poll", "fk_assembly_vote_events_participation")) {
            assertThat(count(EMPTY, "select count(*) from pg_constraint where conname = ?", c)).as(c).isEqualTo(1);
        }
        assertThat(count(EMPTY, "select count(*) from pg_indexes where indexname = 'uq_poll_participation_valid' "
                + "and indexdef like '%UNIQUE%' and indexdef like '%VALID%'")).isEqualTo(1);
        for (String t : List.of("trg_poll_participations_before_insert", "trg_poll_participations_guard", "trg_poll_participations_no_truncate",
                "trg_poll_participations_requires_vote", "trg_poll_votes_before_insert", "trg_poll_votes_immutable",
                "trg_assemblies_no_reopen", "trg_assembly_vote_events_append_only", "trg_assembly_vote_events_no_truncate")) {
            assertThat(count(EMPTY, "select count(*) from pg_trigger where tgname = ? and not tgisinternal", t)).as(t).isEqualTo(1);
        }
        assertThat(text(EMPTY, "select is_nullable from information_schema.columns where table_name = 'poll_votes' "
                + "and column_name = 'participation_id'")).isEqualTo("YES");
        // P5 (V105): uq_property_poll_option se sustituye por dos indices equivalentes (historicos sin cambios de
        // garantia; una opcion por participacion); cadena de nuevas emisiones. V106: limites por comunidad.
        assertThat(count(EMPTY, "select count(*) from pg_constraint where conname = 'uq_property_poll_option'")).isZero();
        for (String i : List.of("uq_poll_votes_legacy_option", "uq_poll_votes_participation_option", "uq_poll_participations_supersedes")) {
            assertThat(count(EMPTY, "select count(*) from pg_indexes where indexname = ? and indexdef like '%UNIQUE%'", i)).as(i).isEqualTo(1);
        }
        assertThat(text(EMPTY, "select indexdef from pg_indexes where indexname = 'uq_poll_votes_legacy_option'"))
                .contains("(poll_id, property_id, poll_option_id)").contains("participation_id IS NULL");
        for (String c : List.of("fk_poll_participations_supersedes", "community_proxy_settings_pkey", "community_proxy_settings_limit_check",
                "fk_community_proxy_settings_tenant")) {
            assertThat(count(EMPTY, "select count(*) from pg_constraint where conname = ?", c)).as(c).isEqualTo(1);
        }
        for (String t : List.of("trg_poll_participations_chain", "trg_poll_participations_chain_immutable",
                "trg_community_proxy_settings_events_append_only")) {
            assertThat(count(EMPTY, "select count(*) from pg_trigger where tgname = ? and not tgisinternal", t)).as(t).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("historico (incluido inconsistente): V104 se aplica sin tocar ni ocultar filas; los votos nuevos quedan protegidos")
    void historicalVotes() throws SQLException {
        flyway(HIST, "103").migrate();
        Hist h = seedHistory(HIST);
        String before = fingerprint(HIST, h.tenant());
        long votesBefore = count(HIST, "select count(*) from poll_votes where tenant_id = ?", h.tenant());

        flyway(HIST, null).migrate();
        assertThat(flyway(HIST, null).info().current().getVersion().getVersion()).isEqualTo("108");

        // Nada historico cambia: mismas filas y valores; sin participacion (no se inventan actores) ni normalizacion.
        assertThat(fingerprint(HIST, h.tenant())).isEqualTo(before);
        assertThat(count(HIST, "select count(*) from poll_votes where tenant_id = ?", h.tenant())).isEqualTo(votesBefore);
        assertThat(count(HIST, "select count(*) from poll_votes where tenant_id = ? and participation_id is not null", h.tenant())).isZero();
        assertThat(count(HIST, "select count(*) from poll_participations where tenant_id = ?", h.tenant())).isZero();
        // El duplicado historico NO se oculta: sigue visible y contando en el resultado vigente (ver documento P4 §16).
        assertThat(count(HIST, "select count(*) from poll_votes v left join poll_participations pp on pp.id = v.participation_id "
                + "where v.poll_id = ? and v.property_id = ? and (v.participation_id is null or pp.status = 'VALID')", h.closedPoll(), h.unit()))
                .isEqualTo(2);

        // Votos NUEVOS protegidos: la unidad con voto historico en la pregunta abierta no vota de nuevo, con ninguna opcion.
        assertRejected("23505", "poll_votes_one_participation_per_unit", () -> exec(HIST, "insert into poll_votes (id, tenant_id, "
                + "poll_id, poll_option_id, property_id, coefficient, voted_at, created_at, updated_at) values (?, ?, ?, ?, ?, 0.2, now(), "
                + "now(), now())", id(), h.tenant(), h.livePoll(), h.liveNo(), h.unit()));
        // Ni en la pregunta cerrada con el duplicado historico.
        assertRejected("23505", "poll_votes_one_participation_per_unit", () -> exec(HIST, "insert into poll_votes (id, tenant_id, "
                + "poll_id, poll_option_id, property_id, coefficient, voted_at, created_at, updated_at) values (?, ?, ?, ?, ?, 0.2, now(), "
                + "now(), now())", id(), h.tenant(), h.closedPoll(), h.closedYes(), h.unit()));
        // Opcion de otra pregunta: rechazada.
        assertRejected("23514", "poll_votes_scope", () -> exec(HIST, "insert into poll_votes (id, tenant_id, poll_id, poll_option_id, "
                + "property_id, coefficient, voted_at, created_at, updated_at) values (?, ?, ?, ?, ?, 0.2, now(), now(), now())",
                id(), h.tenant(), h.livePoll(), h.closedYes(), h.unit2()));
        // Los historicos no se reescriben ni se borran; la asamblea finalizada no se reabre.
        assertRejected("23514", "poll_votes_immutable", () -> exec(HIST, "update poll_votes set poll_option_id = ? where poll_id = ? "
                + "and property_id = ?", h.closedNo(), h.closedPoll(), h.unit2()));
        assertRejected("23514", "poll_votes_immutable", () -> exec(HIST, "delete from poll_votes where poll_id = ?", h.closedPoll()));
        assertRejected("23514", "assemblies_no_reopen", () -> exec(HIST, "update assemblies set status = 'IN_PROGRESS' where id = ?",
                h.endedAssembly()));
        assertThat(fingerprint(HIST, h.tenant())).isEqualTo(before);
        // Una unidad SIN voto previo si puede votar (aqui por SQL directo, sin participacion: V-1 la protege igual).
        exec(HIST, "insert into poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, coefficient, voted_at, created_at, "
                + "updated_at) values (?, ?, ?, ?, ?, 0.2, now(), now(), now())", id(), h.tenant(), h.livePoll(), h.liveNo(), h.unit2());
        assertThat(count(HIST, "select count(*) from poll_votes where poll_id = ? and property_id = ?", h.livePoll(), h.unit2())).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrencia en PostgreSQL: dos INSERT directos simultaneos de la misma unidad con opciones distintas → uno solo (8 rondas)")
    void concurrentDirectInserts() throws Exception {
        flyway(EMPTY, null).migrate();
        Hist h = seedAfterMigration(EMPTY);
        for (int round = 0; round < ROUNDS; round++) {
            UUID unit = id();
            exec(EMPTY, "insert into properties (id, tenant_id, number, name, type, status, created_at, updated_at) values (?, ?, ?, "
                    + "'Apto', 'Apartamento', 'OCCUPIED', now(), now())", unit, h.tenant(), "R-" + unit.toString().substring(0, 8));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> fs = new ArrayList<>();
            for (UUID option : List.of(h.liveYes(), h.liveNo())) {
                fs.add(pool.submit(() -> {
                    go.await();
                    try (Connection cn = conn(EMPTY)) {
                        cn.setAutoCommit(false);
                        exec(cn, "insert into poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, coefficient, voted_at, "
                                + "created_at, updated_at) values (?, ?, ?, ?, ?, 0, now(), now(), now())", id(), h.tenant(), h.livePoll(), option, unit);
                        Thread.sleep(50); // mantiene la transaccion abierta para forzar el cruce
                        cn.commit();
                        return "OK";
                    } catch (SQLException e) {
                        return constraintOf(e);
                    }
                }));
            }
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : fs) results.add(f.get(30, TimeUnit.SECONDS));
            pool.shutdown();
            assertThat(results).as("ronda " + round).containsExactlyInAnyOrder("OK", "poll_votes_one_participation_per_unit");
            assertThat(count(EMPTY, "select count(*) from poll_votes where poll_id = ? and property_id = ?", h.livePoll(), unit)).isEqualTo(1);
        }
    }

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> V104_ONLY = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("vote_ledger_v104");

    @Test
    @DisplayName("P5 · base con V104 pero sin V105/V106: sin nueva emision (fallo controlado) y sin limites por comunidad")
    void withoutV105AndV106() throws SQLException {
        flyway(V104_ONLY, "104").migrate();
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(V104_ONLY.getJdbcUrl(), V104_ONLY.getUsername(),
                V104_ONLY.getPassword());
        var schema = new com.havita.modules.assembly.infrastructure.VoteLedgerSchema(new org.springframework.jdbc.core.JdbcTemplate(ds));
        var ledger = new com.havita.modules.assembly.infrastructure.VoteLedgerJdbcRepository(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds), schema);
        assertThat(schema.active()).isTrue();
        assertThat(schema.reissueSupported()).isFalse();
        assertThat(schema.communityLimitsSupported()).isFalse();
        // Sin V105 nunca hay cabeza de cadena: el servicio responde VOIDED_VOTE_REVOTE_NOT_AUTHORIZED (409), no un 500.
        assertThat(ledger.findReissueHead(id(), id(), id())).isEmpty();
        // Las lecturas no consultan la columna inexistente.
        assertThat(ledger.history(id(), id())).isEmpty();
        assertThat(count(V104_ONLY, "select count(*) from information_schema.columns where table_name = 'poll_participations' "
                + "and column_name = 'supersedes_participation_id'")).isZero();
    }

    /** Comunidad con una pregunta abierta en un esquema ya migrado. */
    private static Hist seedAfterMigration(PostgreSQLContainer<?> c) throws SQLException {
        UUID t = id(), live = id(), livePoll = id(), ly = id(), ln = id();
        String s = "p4c-" + t.toString().substring(0, 8);
        exec(c, "insert into tenants (id, name, slug, subdomain, status, storage_limit_mb, storage_used_mb, created_at, updated_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 1024, 0, now(), now())", t, "Comunidad " + s, s, s);
        exec(c, "insert into assemblies (id, tenant_id, title, type, modality, assembly_date, start_time, status, total_properties, "
                + "registered_attendees, quorum_percentage, created_at, updated_at) values (?, ?, 'Asamblea', 'ORDINARIA', 'MIXTA', "
                + "current_date, '10:00', 'IN_PROGRESS', 0, 0, 0, now(), now())", live, t);
        exec(c, "insert into polls (id, tenant_id, assembly_id, question, type, status, show_results_to_residents, opened_at, "
                + "created_at, updated_at) values (?, ?, ?, 'Concurrente', 'SINGLE_CHOICE', 'LIVE', true, now(), now(), now())", livePoll, t, live);
        exec(c, "insert into poll_options (id, poll_id, text, option_order) values (?, ?, 'Si', 1)", ly, livePoll);
        exec(c, "insert into poll_options (id, poll_id, text, option_order) values (?, ?, 'No', 2)", ln, livePoll);
        return new Hist(t, null, null, null, null, live, null, livePoll, ly, ln, null, null);
    }
}
