package com.havita.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentInvitation;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentInvitationRepository;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Fase 2 / P0-C — conflictos de residencia a traves de la capa HTTP, sobre el ESQUEMA DE FLYWAY (db/flyway, V100 + V101),
 * que es el que tiene los indices unicos parciales reales. Por eso esta clase usa su propio contenedor con Flyway en
 * cualquier modo de la suite (el esquema de Hibernate de las demas pruebas no tiene esos indices).
 *
 * Comprueba: valores de type invalidos rechazados con 400 antes de escribir; violaciones de los indices conocidos
 * devueltas como 409 con el codigo de negocio (tambien en el flush/commit y en carreras reales); que la respuesta no
 * revela SQL, indices ni datos de otra comunidad.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ResidenceConflictHttpIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("conflict_http_it");

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            PG.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/flyway");
        registry.add("spring.flyway.table", () -> "havita_schema_history");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    private static final int ROUNDS = 5;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private ResidentInvitationRepository invitationRepository;
    @Autowired private TenantRepository tenantRepository;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    // ── utilidades ────────────────────────────────────────────────────────────

    private MockHttpServletResponse as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()))
                .andReturn().getResponse();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode body(MockHttpServletResponse r) throws Exception {
        return objectMapper.readTree(r.getContentAsString());
    }

    private MockHttpServletResponse adminCreate(Side side, Property unit, String typeJson, UUID userId) throws Exception {
        String user = userId != null ? ",\"userId\":\"" + userId + "\"" : "";
        String type = typeJson != null ? ",\"type\":" + typeJson : "";
        return as(side.admin(), side, json(post("/api/v1/residents"),
                "{\"propertyId\":\"%s\",\"name\":\"Persona Sintetica\"%s%s}".formatted(unit.getId(), type, user)));
    }

    private List<Resident> active(Property unit) {
        return residentRepository.findByPropertyIdAndStatus(unit.getId(), ResidentStatus.ACTIVE);
    }

    private long activeTenants(Property unit) {
        return active(unit).stream().filter(r -> "Arrendatario".equals(r.getType())).count();
    }

    private List<ResidentInvitation> invitations(Property unit) {
        return invitationRepository.findAll().stream().filter(i -> i.getProperty().getId().equals(unit.getId())).toList();
    }

    private User memberOf(Side... sides) {
        User u = userRepository.save(User.builder()
                .email("persona-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}not-used").fullName("Persona Multicomunidad").phone("3005556677").build());
        for (Side s : sides) {
            membershipRepository.save(Membership.builder()
                    .user(u).tenantId(s.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        }
        return u;
    }

    private Resident relation(Side side, Property unit, User user, String type) {
        Resident r = Resident.builder().property(unit).user(user).name(user.getFullName()).type(type)
                .email(user.getEmail()).status(ResidentStatus.ACTIVE).build();
        r.setTenantId(side.tenantId());
        return residentRepository.save(r);
    }

    /** La respuesta de error sigue el contrato de Havita y no expone SQL, indices, tablas ni datos de la otra comunidad. */
    private void assertSafeConflict(MockHttpServletResponse r, String code, String... forbidden) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(409);
        JsonNode e = body(r);
        assertThat(e.path("code").asText()).isEqualTo(code);
        assertThat(e.path("status").asInt()).isEqualTo(409);
        assertThat(e.path("traceId").asText()).isNotBlank();
        String raw = r.getContentAsString();
        for (String internal : List.of("uq_active", "uq_pending", "duplicate key", "constraint", "insert into",
                "SQLState", "23505", "org.postgresql", "org.hibernate", "Exception")) {
            assertThat(raw).as(internal).doesNotContainIgnoringCase(internal);
        }
        for (String f : forbidden) {
            assertThat(raw).as("no debe revelar: " + f).doesNotContain(f);
        }
    }

    // ── D2: tipos invalidos rechazados antes de escribir ──────────────────────

    @Test
    @DisplayName("D1/D2 · alta: tipos reales aceptados con grafia canonica; vacios, en ingles o desconocidos -> 400 sin escribir")
    void createValidatesType() throws Exception {
        for (String invalid : List.of("\"OWNER\"", "\"TENANT\"", "\"RESIDENT\"", "\"owner\"", "\"tenant\"", "\"resident\"",
                "\"\"", "\"   \"", "\"desconocido\"", "\"Inquilino\"")) {
            int before = active(a.vacant()).size();
            MockHttpServletResponse r = adminCreate(a, a.vacant(), invalid, null);
            assertThat(r.getStatus()).as(invalid + " " + r.getContentAsString()).isEqualTo(400);
            assertThat(body(r).path("code").asText()).isEqualTo("INVALID_RESIDENT_TYPE");
            assertThat(active(a.vacant())).as(invalid).hasSize(before);
        }
        // Sin tipo: el valor por defecto de siempre (Propietario).
        MockHttpServletResponse none = adminCreate(a, a.vacant(), null, null);
        assertThat(none.getStatus()).isEqualTo(201);
        assertThat(body(none).at("/data/type").asText()).isEqualTo("Propietario");
        // Capitalizacion distinta del mismo tipo: se guarda con la grafia canonica que usan los indices.
        MockHttpServletResponse lower = adminCreate(a, a.vacant(), "\"arrendatario\"", null);
        assertThat(lower.getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(body(lower).at("/data/id").asText());
        assertThat(residentRepository.findById(id).orElseThrow().getType()).isEqualTo("Arrendatario");
        // ...y por tanto el indice si la cubre: un segundo arrendatario (en mayusculas) es un conflicto, no un 500.
        assertSafeConflict(adminCreate(a, a.vacant(), "\"ARRENDATARIO\"", null), "SECOND_ACTIVE_TENANT");
        assertThat(activeTenants(a.vacant())).isEqualTo(1);
    }

    @Test
    @DisplayName("D2 · edicion y asignacion: tipo invalido -> 400 y la relacion no cambia")
    void updateAndAssignValidateType() throws Exception {
        Resident census = a.censusOnly();
        for (String invalid : List.of("\"TENANT\"", "\"\"", "\"desconocido\"")) {
            MockHttpServletResponse r = as(a.admin(), a, json(put("/api/v1/residents/{id}", census.getId()), "{\"type\":" + invalid + "}"));
            assertThat(r.getStatus()).as(invalid).isEqualTo(400);
            assertThat(body(r).path("code").asText()).isEqualTo("INVALID_RESIDENT_TYPE");
            assertThat(residentRepository.findById(census.getId()).orElseThrow().getType()).isEqualTo("Residente");
        }
        MockHttpServletResponse ok = as(a.admin(), a, json(put("/api/v1/residents/{id}", census.getId()), "{\"type\":\"RESIDENTE\"}"));
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(residentRepository.findById(census.getId()).orElseThrow().getType()).isEqualTo("Residente");

        User person = memberOf(a);
        MockHttpServletResponse assign = as(a.admin(), a, json(post("/api/v1/residents/users/{id}/properties", person.getId()),
                "{\"propertyIds\":[\"%s\"],\"type\":\"OWNER\"}".formatted(a.vacant().getId())));
        assertThat(assign.getStatus()).isEqualTo(400);
        assertThat(body(assign).path("code").asText()).isEqualTo("INVALID_RESIDENT_TYPE");
        assertThat(residentRepository.findByUserIdAndTenantId(person.getId(), a.tenantId())).isEmpty();
    }

    @Test
    @DisplayName("D2 · invitacion del hogar: solo Arrendatario o Residente; Propietario o un tipo desconocido -> 400 sin crear la invitacion")
    void householdInvitationValidatesType() throws Exception {
        for (String[] c : List.of(new String[]{"Propietario", "INVALID_INVITATION_TYPE"}, new String[]{"propietario", "INVALID_INVITATION_TYPE"},
                new String[]{"OWNER", "INVALID_RESIDENT_TYPE"}, new String[]{"TENANT", "INVALID_RESIDENT_TYPE"},
                new String[]{"desconocido", "INVALID_RESIDENT_TYPE"})) {
            MockHttpServletResponse r = as(a.owner(), a, json(post("/api/v1/resident/me/invitations"),
                    "{\"type\":\"%s\",\"email\":\"invitado-%s@example.test\",\"name\":\"Invitado\",\"phone\":\"3001234567\",\"propertyId\":\"%s\"}"
                            .formatted(c[0], UUID.randomUUID().toString().substring(0, 6), a.home().getId())));
            assertThat(r.getStatus()).as(c[0] + " " + r.getContentAsString()).isEqualTo(400);
            assertThat(body(r).path("code").asText()).as(c[0]).isEqualTo(c[1]);
        }
        assertThat(invitations(a.home())).isEmpty();

        // Minusculas: se guarda la grafia canonica, de modo que uq_pending_arrendatario_invitation la cubre.
        MockHttpServletResponse ok = as(a.owner(), a, json(post("/api/v1/resident/me/invitations"),
                "{\"type\":\"arrendatario\",\"email\":\"inquilino-%s@example.test\",\"name\":\"Inquilino\",\"phone\":\"3001234567\",\"propertyId\":\"%s\"}"
                        .formatted(UUID.randomUUID().toString().substring(0, 6), a.home().getId())));
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertThat(invitations(a.home())).singleElement().extracting(ResidentInvitation::getInvitationType).isEqualTo("Arrendatario");
    }

    // ── D6: violaciones de indice -> 409 por HTTP ─────────────────────────────

    @Test
    @DisplayName("D6 · cambiar el tipo a Arrendatario en una unidad que ya tiene uno: 409 SECOND_ACTIVE_TENANT (en el flush/commit) y sin cambios")
    void updateToSecondTenantIs409() throws Exception {
        assertThat(adminCreate(a, a.home(), "\"Arrendatario\"", null).getStatus()).isEqualTo(201);
        Resident census = a.censusOnly(); // Residente activo de la misma unidad
        MockHttpServletResponse r = as(a.admin(), a, json(put("/api/v1/residents/{id}", census.getId()), "{\"type\":\"Arrendatario\"}"));
        assertSafeConflict(r, "SECOND_ACTIVE_TENANT");
        assertThat(residentRepository.findById(census.getId()).orElseThrow().getType()).isEqualTo("Residente");
        assertThat(activeTenants(a.home())).isEqualTo(1);
    }

    @Test
    @DisplayName("C/D6 · residencia no propietaria activa en OTRA comunidad: 409 sin revelar comunidad, unidad ni datos de terceros")
    void crossCommunityNonOwnerResidenceIs409WithoutLeak() throws Exception {
        User person = memberOf(a, b);
        relation(b, b.neighbourHome(), person, "Residente");
        String bTenantName = tenantRepository.findById(b.tenantId()).orElseThrow().getName();
        String[] forbidden = {b.neighbourHome().getNumber(), b.neighbourHome().getName(), b.tenantId().toString(), bTenantName,
                person.getEmail(), b.neighbourHome().getId().toString()};

        // Alta por la administracion de A (sin comprobacion previa: la rechaza el indice _per_user).
        assertSafeConflict(adminCreate(a, a.vacant(), "\"Residente\"", person.getId()), "ALREADY_ACTIVE_IN_ANOTHER_PROPERTY", forbidden);
        // Asignacion de unidades por la administracion de A.
        assertSafeConflict(as(a.admin(), a, json(post("/api/v1/residents/users/{id}/properties", person.getId()),
                "{\"propertyIds\":[\"%s\"],\"type\":\"Arrendatario\"}".formatted(a.vacant().getId()))),
                "ALREADY_ACTIVE_IN_ANOTHER_PROPERTY", forbidden);
        assertThat(residentRepository.findByUserIdAndTenantId(person.getId(), a.tenantId())).isEmpty();

        // Invitacion del hogar en A al correo de esa persona (comprobacion previa del servicio): tampoco revela la unidad de B.
        MockHttpServletResponse inv = as(a.owner(), a, json(post("/api/v1/resident/me/invitations"),
                "{\"type\":\"Residente\",\"email\":\"%s\",\"name\":\"Persona\",\"phone\":\"3001234567\",\"propertyId\":\"%s\"}"
                        .formatted(person.getEmail(), a.home().getId())));
        assertThat(inv.getStatus()).isEqualTo(409);
        assertThat(body(inv).path("code").asText()).isEqualTo("ALREADY_ACTIVE_IN_ANOTHER_PROPERTY");
        for (String f : new String[]{b.neighbourHome().getNumber(), b.neighbourHome().getName(), bTenantName, b.tenantId().toString()}) {
            assertThat(inv.getContentAsString()).as(f).doesNotContain(f);
        }
        assertThat(invitations(a.home())).isEmpty();

        // R2: ser propietario en A si esta permitido.
        assertThat(adminCreate(a, a.vacant(), "\"Propietario\"", person.getId()).getStatus()).isEqualTo(201);
    }

    // ── D5/D6: carreras reales por HTTP ───────────────────────────────────────

    private List<MockHttpServletResponse> concurrently(List<Callable<MockHttpServletResponse>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> fs = new ArrayList<>();
        for (Callable<MockHttpServletResponse> c : calls) {
            fs.add(pool.submit(() -> {
                go.await();
                return c.call();
            }));
        }
        go.countDown();
        List<MockHttpServletResponse> out = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    @Test
    @DisplayName("D5/D6 · dos altas simultaneas de arrendatario para la misma unidad: una 201 y otra 409 (nunca 500); un solo arrendatario")
    void raceTwoTenantsOverHttp() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var c = fixture.create();
            Side s = c.a();
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> adminCreate(s, s.vacant(), "\"Arrendatario\"", null),
                    () -> adminCreate(s, s.vacant(), "\"Arrendatario\"", null)));
            assertThat(r).as("ronda " + round).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
            for (MockHttpServletResponse x : r) {
                if (x.getStatus() == 409) assertSafeConflict(x, "SECOND_ACTIVE_TENANT");
            }
            assertThat(activeTenants(s.vacant())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("D5/D6 · misma persona dada de alta a la vez como no propietaria en dos comunidades: una 201 y otra 409; una sola residencia")
    void raceNonOwnerResidenceAcrossCommunitiesOverHttp() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var c = fixture.create();
            User person = memberOf(c.a(), c.b());
            List<MockHttpServletResponse> r = concurrently(List.of(
                    () -> adminCreate(c.a(), c.a().vacant(), "\"Residente\"", person.getId()),
                    () -> adminCreate(c.b(), c.b().vacant(), "\"Arrendatario\"", person.getId())));
            assertThat(r).as("ronda " + round).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
            for (MockHttpServletResponse x : r) {
                if (x.getStatus() == 409) {
                    assertSafeConflict(x, "ALREADY_ACTIVE_IN_ANOTHER_PROPERTY", c.a().vacant().getNumber(), c.b().vacant().getNumber());
                }
            }
            long activeNonOwner = residentRepository.findActiveNonOwnerResidences(person.getId(), person.getEmail(), ResidentStatus.ACTIVE).size();
            assertThat(activeNonOwner).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("D5/D6 · dos invitaciones de arrendatario simultaneas para la misma unidad: una 200 y otra 409 PENDING_TENANT_INVITATION")
    void raceTwoPendingTenantInvitationsOverHttp() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var c = fixture.create();
            Side s = c.a();
            Callable<MockHttpServletResponse> invite = () -> as(s.owner(), s, json(post("/api/v1/resident/me/invitations"),
                    "{\"type\":\"Arrendatario\",\"email\":\"inq-%s@example.test\",\"name\":\"Inquilino\",\"phone\":\"3001234567\",\"propertyId\":\"%s\"}"
                            .formatted(UUID.randomUUID().toString().substring(0, 8), s.home().getId())));
            List<MockHttpServletResponse> r = concurrently(List.of(invite, invite));
            assertThat(r).as("ronda " + round).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
            for (MockHttpServletResponse x : r) {
                if (x.getStatus() == 409) assertSafeConflict(x, "PENDING_TENANT_INVITATION");
            }
            assertThat(invitations(s.home()).stream().filter(i -> i.getStatus().name().equals("PENDING")).count()).isEqualTo(1);
        }
    }
}
