package com.havita.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base para pruebas de integracion contra PostgreSQL real (Testcontainers; requiere Docker).
 *
 * El esquema lo genera Hibernate y Flyway queda desactivado, igual que en la ejecucion actual de la
 * aplicacion (application.yml: ddl-auto=update, flyway.enabled=false; BACKLOG R-07). Las migraciones no
 * pueden aplicarse todavia: hay dos V32 (R-08) y V27 referencia resident_invitations, tabla que ninguna
 * migracion crea. Cuando se corrija R-07/R-08 esta base debe pasar a usar Flyway con ddl-auto=validate.
 *
 * Un unico contenedor por JVM, compartido por todas las clases que extienden esta base.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("havita_it");

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    /**
     * Fase 2 / P0: modo OPCIONAL para validar la baseline de Flyway preparada (db/flyway, inactiva en la aplicacion).
     * Solo se activa con la propiedad de sistema {@code havita.test.schema=flyway}; sin ella el comportamiento es el de
     * siempre (Hibernate create-drop, Flyway desactivado). Con ella, el esquema del contenedor lo crea Flyway con
     * classpath:db/flyway (tabla de historial havita_schema_history) y Hibernate solo lo valida.
     */
    static final boolean FLYWAY_SCHEMA = "flyway".equals(System.getProperty("havita.test.schema"))
            // Gradle no pasa los -D de la linea de comandos a la JVM de pruebas; la variable de entorno si llega:
            // HAVITA_TEST_SCHEMA=flyway ./gradlew test
            || "flyway".equals(System.getenv("HAVITA_TEST_SCHEMA"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        if (FLYWAY_SCHEMA) {
            registry.add("spring.flyway.enabled", () -> "true");
            registry.add("spring.flyway.locations", () -> "classpath:db/flyway");
            registry.add("spring.flyway.table", () -> "havita_schema_history");
            registry.add("spring.flyway.baseline-on-migrate", () -> "false");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        } else {
            registry.add("spring.flyway.enabled", () -> "false");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        }
    }
}
