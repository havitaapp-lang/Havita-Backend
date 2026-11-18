package com.havita.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fase 3 — V108 retira la tabla del chat anterior (concierge_chat_messages) SOLO si esta vacia. Con datos (posible en
 * una base con historia desconocida, como produccion) falla sin cambiar nada. Contenedor PostgreSQL propio y aislado.
 */
@Testcontainers(disabledWithoutDocker = true)
class LegacyChatDropMigrationIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("v108");

    private static Flyway flyway(String target) {
        var cfg = Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/flyway").table("havita_schema_history").cleanDisabled(false);
        if (target != null) cfg.target(target);
        return cfg.load();
    }

    private static long scalar(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    @DisplayName("V108: con mensajes antiguos falla sin borrar nada; vacia, elimina la tabla")
    void dropsOnlyWhenEmpty() throws Exception {
        flyway(null).clean();
        flyway("107").migrate();
        assertThat(scalar("select count(*) from pg_class where relname = 'concierge_chat_messages'")).isEqualTo(1);

        // Un mensaje heredado (datos sinteticos minimos: comunidad, usuario y unidad).
        exec("insert into tenants (id, name, slug, status, created_at, updated_at, subdomain, storage_limit_mb, storage_used_mb) values "
                + "('00000000-0000-0000-0000-000000000001', 'Comunidad V108', 'c-v108', 'ACTIVE', now(), now(), 'cv108', 100, 0)");
        exec("insert into users (id, email, password_hash, full_name, status, created_at, updated_at, has_completed_onboarding) values "
                + "('00000000-0000-0000-0000-000000000002', 'v108@example.test', 'x', 'Usuario V108', 'ACTIVE', now(), now(), false)");
        exec("insert into properties (id, tenant_id, number, name, type, status, created_at, updated_at) values "
                + "('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', '101', 'Apto 101', 'Apartamento', 'OCCUPIED', now(), now())");
        exec("insert into concierge_chat_messages (id, tenant_id, property_id, sender_id, sender_name, sender_role, content, "
                + "message_type, is_read, created_at, updated_at) values ('00000000-0000-0000-0000-000000000004', "
                + "'00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000003', "
                + "'00000000-0000-0000-0000-000000000002', 'Usuario V108', 'RESIDENT', 'hola', 'TEXT', false, now(), now())");

        assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("concierge_chat_messages tiene 1 fila");
        assertThat(scalar("select count(*) from concierge_chat_messages")).as("el mensaje sigue ahi").isEqualTo(1);
        assertThat(scalar("select count(*) from havita_schema_history where version = '108' and success")).isZero();

        exec("delete from concierge_chat_messages");
        flyway(null).migrate();
        assertThat(scalar("select count(*) from pg_class where relname = 'concierge_chat_messages'")).isZero();
        assertThat(scalar("select count(*) from havita_schema_history where version = '108' and success")).isEqualTo(1);
    }
}
