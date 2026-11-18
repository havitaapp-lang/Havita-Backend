package com.havita.migration;

import com.havita.HavitaApplication;
import com.havita.modules.assembly.infrastructure.VoteLedgerSchema;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fase 2 / P5-B — ENSAYO de adopcion de Flyway sobre una COPIA SINTETICA aislada (PostgreSQL 17 en Testcontainers, la
 * misma version mayor que la base local). Nunca toca appunidades ni Railway; no hay datos personales reales.
 *
 * Simula una base existente como las actuales: esquema creado fuera de Flyway (V100 ejecutado por SQL, sin historial
 * de havita_schema_history), una tabla de historial HEREDADA flyway_schema_history de la cadena antigua db/migration, y
 * datos sinteticos representativos (residentes, delegaciones, asambleas, asistencias, preguntas, votos, alertas,
 * marketplace) mas volumen. Despues ensaya el procedimiento propuesto en tasks/P5_FLYWAY_ADOPTION_PLAN.md:
 * verificaciones previas → baseline 100 explicito (sin baselineOnMigrate) → migrate V101-V106 → validate → huella de
 * datos identica → esquema equivalente a una instalacion limpia → arranque de la aplicacion con ddl-auto=validate.
 * Tambien demuestra las condiciones de aborto (checksum alterado, deriva de esquema, cadena antigua con V32 duplicada).
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayAdoptionRehearsalIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG17 = new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("adoption_copy");

    // ── utilidades ────────────────────────────────────────────────────────────

    private static String url(String db) {
        return PG17.getJdbcUrl().replace("/adoption_copy", "/" + db);
    }

    private static Connection conn(String db) throws SQLException {
        return DriverManager.getConnection(url(db), PG17.getUsername(), PG17.getPassword());
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static String text(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getString(1);
        }
    }

    private static void createDatabase(String db) throws SQLException {
        try (Connection c = conn("adoption_copy"); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        }
    }

    private static void runScript(Connection c, String classpathResource) throws Exception {
        String sql = new String(FlywayAdoptionRehearsalIT.class.getResourceAsStream(classpathResource).readAllBytes(), StandardCharsets.UTF_8);
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** Flyway como en el plan: ubicacion db/flyway, tabla PROPIA havita_schema_history, sin baselineOnMigrate. */
    private static Flyway flyway(String db) {
        return Flyway.configure().dataSource(url(db), PG17.getUsername(), PG17.getPassword())
                .locations("classpath:db/flyway").table("havita_schema_history")
                .baselineVersion("100").baselineDescription("adopcion: esquema existente equivalente a V100")
                .baselineOnMigrate(false).cleanDisabled(true).load();
    }

    /** Catalogo estructural normalizado (tablas, columnas, restricciones, indices, triggers, funciones) sin historiales. */
    private static TreeSet<String> catalog(Connection c) throws SQLException {
        String q = """
                with t as (select c.oid, c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                           where n.nspname = 'public' and c.relkind = 'r'
                           and c.relname not in ('flyway_schema_history', 'havita_schema_history'))
                select 'column|' || t.relname || '.' || a.attname || '|' || format_type(a.atttypid, a.atttypmod) || '|'
                       || (not a.attnotnull)::text || '|' || coalesce(pg_get_expr(d.adbin, d.adrelid), '-')
                from t join pg_attribute a on a.attrelid = t.oid and a.attnum > 0 and not a.attisdropped
                left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                union all select 'constraint|' || t.relname || '.' || co.conname || '|' || pg_get_constraintdef(co.oid)
                from t join pg_constraint co on co.conrelid = t.oid
                union all select 'index|' || t.relname || '.' || ic.relname || '|' || pg_get_indexdef(i.indexrelid)
                from t join pg_index i on i.indrelid = t.oid join pg_class ic on ic.oid = i.indexrelid
                union all select 'trigger|' || t.relname || '.' || tg.tgname || '|' || pg_get_triggerdef(tg.oid)
                from t join pg_trigger tg on tg.tgrelid = t.oid and not tg.tgisinternal
                union all select 'function|' || p.proname || '|' || md5(pg_get_functiondef(p.oid))
                from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public'
                """;
        TreeSet<String> rows = new TreeSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
            while (rs.next()) rows.add(rs.getString(1));
        }
        return rows;
    }

    private static final List<String> DATA_TABLES = List.of("tenants", "users", "memberships", "properties", "residents",
            "voting_delegations", "assemblies", "assembly_attendees", "polls", "poll_options", "poll_votes",
            "security_panic_alerts", "marketplace_listings");

    /** Huella de los datos existentes (sin las columnas que la adopcion anade con valor por defecto o nulo). */
    private static Map<String, String> dataFingerprint(Connection c) throws SQLException {
        Map<String, String> out = new HashMap<>();
        for (String t : DATA_TABLES) {
            out.put(t, text(c, "select coalesce(md5(string_agg((to_jsonb(x) - 'participation_id' - 'hidden_by_admin')::text, ',' "
                    + "order by x.id)), 'vacia') from " + t + " x"));
        }
        return out;
    }

    // ── base "existente" sintetica ────────────────────────────────────────────

    private static void seedLegacyHistory(Connection c) throws SQLException {
        exec(c, "CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY, version varchar(50), description varchar(200) "
                + "NOT NULL, type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum int, installed_by varchar(100) NOT NULL, "
                + "installed_on timestamp NOT NULL DEFAULT now(), execution_time int NOT NULL, success boolean NOT NULL)");
        for (int v = 1; v <= 17; v++) {
            exec(c, "INSERT INTO flyway_schema_history VALUES (?, ?, 'heredada', 'SQL', ?, ?, 'legacy', now(), 1, true)", v,
                    String.valueOf(v), "V" + v + "__heredada.sql", 1000 + v);
        }
    }

    record Seed(UUID tenant, UUID livePoll, UUID liveYes, UUID unit) {}

    private static Seed seedData(Connection c, int bulkUnits) throws SQLException {
        UUID t = UUID.randomUUID(), owner = UUID.randomUUID(), lessee = UUID.randomUUID(), admin = UUID.randomUUID();
        exec(c, "insert into tenants (id, name, slug, subdomain, status, storage_limit_mb, storage_used_mb, created_at, updated_at) "
                + "values (?, 'Copia sintetica', 'copia-p5', 'copiap5', 'ACTIVE', 1024, 0, now(), now())", t);
        for (UUID u : List.of(owner, lessee, admin)) {
            exec(c, "insert into users (id, email, full_name, password_hash, status, has_completed_onboarding, created_at, updated_at) "
                    + "values (?, ?, 'Persona sintetica', '{noop}x', 'ACTIVE', true, now(), now())", u, "u-" + u + "@example.test");
        }
        exec(c, "insert into memberships (id, tenant_id, user_id, status, role, created_at, updated_at) values (?, ?, ?, 'ACTIVE', "
                + "'COMMUNITY_ADMIN', now(), now()), (?, ?, ?, 'ACTIVE', 'RESIDENT', now(), now()), (?, ?, ?, 'ACTIVE', 'RESIDENT', now(), now())",
                UUID.randomUUID(), t, admin, UUID.randomUUID(), t, owner, UUID.randomUUID(), t, lessee);
        UUID unit = UUID.randomUUID();
        exec(c, "insert into properties (id, tenant_id, number, name, type, status, coefficient, created_at, updated_at) values "
                + "(?, ?, 'U-1', 'Apto', 'Apartamento', 'OCCUPIED', 0.2, now(), now())", unit, t);
        UUID ownerRel = UUID.randomUUID();
        String res = "insert into residents (id, tenant_id, property_id, user_id, email, name, type, status, is_main_contact, "
                + "registered_at, created_at, updated_at) values (?, ?, ?, ?, ?, 'Relacion sintetica', ?, ?, false, now(), now(), now())";
        exec(c, res, ownerRel, t, unit, owner, "o@example.test", "Propietario", "ACTIVE");
        exec(c, res, UUID.randomUUID(), t, unit, lessee, "l@example.test", "Arrendatario", "ACTIVE");
        exec(c, res, UUID.randomUUID(), t, unit, null, "f@example.test", "Residente", "INACTIVE");
        exec(c, "insert into voting_delegations (id, tenant_id, property_id, owner_user_id, delegate_user_id, status, granted_at, "
                + "revoked_at, created_at, updated_at) values (?, ?, ?, ?, ?, 'REVOKED', now(), now(), now(), now())",
                UUID.randomUUID(), t, unit, owner, lessee);
        UUID ended = UUID.randomUUID(), live = UUID.randomUUID();
        String asm = "insert into assemblies (id, tenant_id, title, type, modality, assembly_date, start_time, status, total_properties, "
                + "registered_attendees, quorum_percentage, created_at, updated_at) values (?, ?, 'Asamblea', 'ORDINARIA', 'MIXTA', "
                + "current_date, '10:00', ?, 1, 1, 20, now(), now())";
        exec(c, asm, ended, t, "ENDED");
        exec(c, asm, live, t, "IN_PROGRESS");
        for (UUID a : List.of(ended, live)) {
            exec(c, "insert into assembly_attendees (id, tenant_id, assembly_id, property_id, resident_id, coefficient, is_power_of_attorney, "
                    + "checked_in_at, created_at, updated_at) values (?, ?, ?, ?, ?, 0.2, false, now(), now(), now())", UUID.randomUUID(), t, a, unit, ownerRel);
        }
        UUID closedPoll = UUID.randomUUID(), livePoll = UUID.randomUUID(), cy = UUID.randomUUID(), ly = UUID.randomUUID(), ln = UUID.randomUUID();
        exec(c, "insert into polls (id, tenant_id, assembly_id, question, type, status, show_results_to_residents, opened_at, closed_at, "
                + "created_at, updated_at) values (?, ?, ?, 'Historica', 'SINGLE_CHOICE', 'CLOSED', true, now(), now(), now(), now())", closedPoll, t, ended);
        exec(c, "insert into polls (id, tenant_id, assembly_id, question, type, status, show_results_to_residents, opened_at, "
                + "created_at, updated_at) values (?, ?, ?, 'Abierta en la adopcion', 'SINGLE_CHOICE', 'LIVE', true, now(), now(), now())", livePoll, t, live);
        exec(c, "insert into poll_options (id, poll_id, text, option_order) values (?, ?, 'Si', 1), (?, ?, 'Si', 1), (?, ?, 'No', 2)",
                cy, closedPoll, ly, livePoll, ln, livePoll);
        exec(c, "insert into poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, resident_id, coefficient, voted_at, "
                + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, 0.2, now(), now(), now())", UUID.randomUUID(), t, closedPoll, cy, unit, ownerRel);
        exec(c, "insert into security_panic_alerts (id, tenant_id, property_id, status, alert_type, created_at, updated_at) values "
                + "(?, ?, ?, 'RESOLVED', 'MEDICAL', now(), now())", UUID.randomUUID(), t, unit);
        exec(c, "insert into marketplace_listings (id, tenant_id, created_by_user_id, category, description, price_type, status, title, "
                + "type, terms_accepted, created_at, updated_at) values (?, ?, ?, 'OTROS', 'Sintetico', 'FIXED', 'PUBLISHED', 'Anuncio', "
                + "'PRODUCT', true, now(), now())", UUID.randomUUID(), t, owner);
        // Volumen: unidades y votos historicos de la pregunta cerrada (para medir duracion y bloqueos).
        exec(c, "insert into properties (id, tenant_id, number, name, type, status, coefficient, created_at, updated_at) "
                + "select gen_random_uuid(), ?, 'V-' || g, 'Apto', 'Apartamento', 'OCCUPIED', 0.001, now(), now() from generate_series(1, ?) g",
                t, bulkUnits);
        exec(c, "insert into poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, coefficient, voted_at, created_at, updated_at) "
                + "select gen_random_uuid(), ?, ?, ?, p.id, 0.001, now(), now(), now() from properties p where p.tenant_id = ? and p.number like 'V-%'",
                t, closedPoll, cy, t);
        return new Seed(t, livePoll, ly, unit);
    }

    // ── ensayo ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adopcion ensayada: previas OK → baseline 100 → V101-V106 → validate; datos intactos; esquema = instalacion limpia; app arranca con validate")
    void rehearsal() throws Exception {
        createDatabase("existing_copy");
        createDatabase("clean_install");
        Seed seed;
        Map<String, String> before;
        String legacyBefore;
        try (Connection c = conn("existing_copy")) {
            runScript(c, "/db/flyway/V100__baseline_schema.sql"); // esquema creado fuera de Flyway (como Hibernate/V100)
            seedLegacyHistory(c);
            seed = seedData(c, 5000);
            before = dataFingerprint(c);
            legacyBefore = text(c, "select md5(string_agg(row(f.*)::text, ',' order by installed_rank)) from flyway_schema_history f");
            // Verificaciones previas (las del plan): todas deben dar 0 / false para continuar.
            assertThat(text(c, "select count(*) from security_panic_alerts where alert_type not in ('MEDICAL','SECURITY','FIRE','OTHER')")).isEqualTo("0");
            assertThat(text(c, "select (to_regclass('public.havita_schema_history') is not null)::text")).isEqualTo("false");
            assertThat(text(c, "select (to_regclass('public.assembly_proxies') is not null)::text")).isEqualTo("false");
            assertThat(text(c, "select count(*) from poll_votes v join poll_options o on o.id = v.poll_option_id where o.poll_id <> v.poll_id")).isEqualTo("0");
        }

        Flyway fw = flyway("existing_copy");
        fw.baseline(); // explicito, version 100; NUNCA baselineOnMigrate
        long t0 = System.nanoTime();
        fw.migrate();
        long millis = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[P5-B] duracion de V101-V106 sobre la copia sintetica (5.000 votos historicos): " + millis + " ms");
        assertThat(millis).as("migracion corta sobre volumen representativo").isLessThan(60_000);

        MigrationInfo[] applied = fw.info().applied();
        assertThat(applied).extracting(m -> m.getVersion().getVersion()).containsExactly("100", "101", "102", "103", "104", "105", "106", "107", "108");
        assertThat(applied[0].getType().name()).isEqualTo("BASELINE");
        ValidateResult validation = fw.validateWithResult();
        assertThat(validation.validationSuccessful).as(String.valueOf(validation.invalidMigrations)).isTrue();
        assertThat(fw.migrate().migrationsExecuted).as("idempotente: una segunda ejecucion no hace nada").isZero();

        try (Connection c = conn("existing_copy")) {
            assertThat(dataFingerprint(c)).as("ningun dato existente cambia").isEqualTo(before);
            assertThat(text(c, "select md5(string_agg(row(f.*)::text, ',' order by installed_rank)) from flyway_schema_history f"))
                    .as("el historial heredado no se toca").isEqualTo(legacyBefore);
            assertThat(text(c, "select count(*) from poll_votes where participation_id is not null")).isEqualTo("0");
            assertThat(text(c, "select bool_and(not hidden_by_admin)::text from marketplace_listings")).isEqualTo("true");
            // Esquema equivalente a una instalacion limpia V100-V106.
            flyway("clean_install").migrate();
            try (Connection clean = conn("clean_install")) {
                TreeSet<String> adopted = catalog(c);
                TreeSet<String> fresh = catalog(clean);
                TreeSet<String> onlyAdopted = new TreeSet<>(adopted);
                onlyAdopted.removeAll(fresh);
                TreeSet<String> onlyFresh = new TreeSet<>(fresh);
                onlyFresh.removeAll(adopted);
                assertThat(onlyAdopted).as("solo en la copia adoptada").isEmpty();
                assertThat(onlyFresh).as("solo en la instalacion limpia").isEmpty();
            }
        }

        // Arranque de la aplicacion aislada sobre la copia adoptada: Flyway valida (nada pendiente) y Hibernate valida.
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", url("existing_copy"));
        props.put("spring.datasource.username", PG17.getUsername());
        props.put("spring.datasource.password", PG17.getPassword());
        props.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        props.put("spring.flyway.enabled", "true");
        props.put("spring.flyway.locations", "classpath:db/flyway");
        props.put("spring.flyway.table", "havita_schema_history");
        props.put("spring.flyway.baseline-on-migrate", "false");
        props.put("spring.jpa.hibernate.ddl-auto", "validate");
        props.put("server.port", "0");
        props.put("havita.assembly-proxies.enabled", "false");
        props.put("storage.provider", "local");
        props.put("storage.local.base-path", Files.createTempDirectory("havita-p5-adoption-").toString());
        // Como argumentos (precedencia alta, equivalente a variables de entorno del despliegue): las propiedades por
        // defecto de SpringApplicationBuilder pierden frente a application*.yml, que apuntan a db/migration (V32
        // duplicada) — la adopcion DEBE fijar ubicacion y tabla con precedencia alta (ver el plan).
        String[] args = props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HavitaApplication.class)
                .profiles("test").run(args)) {
            VoteLedgerSchema schema = ctx.getBean(VoteLedgerSchema.class);
            assertThat(schema.state()).isEqualTo(VoteLedgerSchema.State.COMPLETE);
            assertThat(schema.reissueSupported()).isTrue();
            assertThat(schema.communityLimitsSupported()).isTrue();
        }
        assertThat(seed.livePoll()).isNotNull();
    }

    @Test
    @DisplayName("condicion de aborto: un checksum alterado en havita_schema_history hace fallar validate (no se usa repair)")
    void tamperedChecksumAborts() throws Exception {
        createDatabase("tampered_copy");
        try (Connection c = conn("tampered_copy")) {
            runScript(c, "/db/flyway/V100__baseline_schema.sql");
        }
        Flyway fw = flyway("tampered_copy");
        fw.baseline();
        fw.migrate();
        try (Connection c = conn("tampered_copy")) {
            exec(c, "update havita_schema_history set checksum = checksum + 1 where version = '104'");
        }
        ValidateResult r = fw.validateWithResult();
        assertThat(r.validationSuccessful).isFalse();
        assertThat(r.invalidMigrations).anyMatch(m -> "104".equals(m.version));
        assertThatThrownBy(fw::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    }

    @Test
    @DisplayName("condicion de aborto: una deriva de esquema respecto de V100 se detecta en la comparacion previa")
    void schemaDriftDetected() throws Exception {
        createDatabase("reference_v100");
        createDatabase("drifted_copy");
        try (Connection ref = conn("reference_v100"); Connection drift = conn("drifted_copy")) {
            runScript(ref, "/db/flyway/V100__baseline_schema.sql");
            runScript(drift, "/db/flyway/V100__baseline_schema.sql");
            exec(drift, "ALTER TABLE poll_votes ADD COLUMN columna_creada_por_hibernate varchar(20)");
            exec(drift, "ALTER TABLE poll_votes DROP CONSTRAINT uq_property_poll_option");
            TreeSet<String> diff = new TreeSet<>(catalog(drift));
            diff.removeAll(catalog(ref));
            TreeSet<String> missing = new TreeSet<>(catalog(ref));
            missing.removeAll(catalog(drift));
            assertThat(diff).anyMatch(s -> s.contains("columna_creada_por_hibernate"));
            assertThat(missing).anyMatch(s -> s.contains("uq_property_poll_option"));
        }
    }

    @Test
    @DisplayName("condicion de aborto: la cadena antigua db/migration (V32 duplicada) no es adoptable con Flyway")
    void legacyChainRejected() {
        Flyway legacy = Flyway.configure().dataSource(url("adoption_copy"), PG17.getUsername(), PG17.getPassword())
                .locations("classpath:db/migration").table("legacy_probe_history").cleanDisabled(true).load();
        assertThatThrownBy(legacy::validate).isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                .hasMessageContaining("32");
        List<String> tables = new ArrayList<>();
        assertThat(tables).isEmpty();
    }
}
