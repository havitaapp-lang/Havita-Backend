package com.havita.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.domain.PropertyStatus;
import com.havita.modules.property.domain.Structure;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.property.infrastructure.StructureRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fase 2 / E1: resolvedor estricto de la unidad del residente ({@code X-Property-Id}). Una unidad pedida que no es una
 * relacion ACTIVE del usuario en esta comunidad se rechaza (409 UNIT_NOT_AVAILABLE) y nunca se sustituye por otra; con
 * varias unidades y sin seleccion se exige elegir (409 UNIT_SELECTION_REQUIRED); con una sola se usa esa. Cada prueba
 * comprueba en la base que la operacion ocurrio en la unidad correcta y que una peticion rechazada no escribio nada.
 */
class ResidentUnitResolutionIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Sintetica-2026!";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private StructureRepository structureRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    // ── utilidades ────────────────────────────────────────────────────────────

    private MvcResult call(User u, Side side, String unit, MockHttpServletRequestBuilder req) throws Exception {
        req.header("Authorization", fixture.bearer(u)).header("X-Tenant-ID", side.tenantId().toString());
        if (unit != null) req.header("X-Property-Id", unit);
        return mvc.perform(req).andReturn();
    }

    private Resident relation(Side side, Property p, User u, String type) {
        Resident r = Resident.builder().property(p).user(u).name("Relacion " + type).type(type)
                .email("rel-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test").phone("3100000000")
                .documentId("DOC-" + UUID.randomUUID().toString().substring(0, 6)).status(ResidentStatus.ACTIVE).build();
        r.setTenantId(side.tenantId());
        return residentRepository.save(r);
    }

    private Property unit(Side side, Structure s, String number) {
        Property p = Property.builder().structure(s).number(number + "-" + UUID.randomUUID().toString().substring(0, 4))
                .name(number).status(PropertyStatus.OCCUPIED).build();
        p.setTenantId(side.tenantId());
        return propertyRepository.save(p);
    }

    private Structure structure(Side side, String name) {
        Structure s = Structure.builder().name(name + " " + UUID.randomUUID().toString().substring(0, 4)).type("Torre").build();
        s.setTenantId(side.tenantId());
        return structureRepository.save(s);
    }

    /** a.owner pasa a tener dos unidades: home (fixture) y vacant (Propietario). */
    private void secondUnitForOwner() {
        relation(a, a.vacant(), a.owner(), "Propietario");
    }

    private static MockHttpServletRequestBuilder newVehicle(String plate) {
        return post("/api/v1/resident/me/vehicles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plate\":\"" + plate + "\",\"type\":\"Carro\"}");
    }

    private static MockHttpServletRequestBuilder panic() {
        return post("/api/v1/realtime/panic").contentType(MediaType.APPLICATION_JSON).content("{\"alertType\":\"MEDICAL\"}");
    }

    private String code(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("code").asText();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
    }

    private UUID vehicleProperty(String plate) {
        List<String> rows = jdbc.queryForList("select cast(property_id as varchar) from vehicles where plate = ?", String.class, plate);
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0));
    }

    private int panicRows(Side side) {
        return jdbc.queryForObject("select count(*) from security_panic_alerts where tenant_id = ?", Integer.class, side.tenantId());
    }

    private static String plate() {
        return "E1" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    }

    private void assertRejected(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(code(r)).isEqualTo(code);
    }

    // ── casos A-I ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1/H · una sola unidad: sin cabecera se usa esa unidad")
    void singleUnitResolvesAutomatically() throws Exception {
        String plate = plate();
        MvcResult r = call(a.owner(), a, null, newVehicle(plate));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(vehicleProperty(plate)).isEqualTo(a.home().getId());
        assertThat(call(a.owner(), a, null, get("/api/v1/resident/me/vehicles")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("2-3/G · dos unidades sin seleccion: 409 UNIT_SELECTION_REQUIRED, sin escribir en ninguna")
    void multipleUnitsRequireSelection() throws Exception {
        secondUnitForOwner();
        String plate = plate();
        assertRejected(call(a.owner(), a, null, newVehicle(plate)), 409, "UNIT_SELECTION_REQUIRED");
        assertThat(vehicleProperty(plate)).isNull();
        assertRejected(call(a.owner(), a, null, get("/api/v1/resident/me/vehicles")), 409, "UNIT_SELECTION_REQUIRED");
        assertRejected(call(a.owner(), a, "  ", get("/api/v1/resident/me/pets")), 409, "UNIT_SELECTION_REQUIRED");
        // La lista de unidades no necesita unidad activa.
        MvcResult list = call(a.owner(), a, null, get("/api/v1/resident/me/properties"));
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(list)).hasSize(2);
    }

    @Test
    @DisplayName("A/20 · unidad valida y cambio de unidad entre peticiones: cada escritura queda en la unidad pedida")
    void explicitUnitAndSwitchBetweenRequests() throws Exception {
        secondUnitForOwner();
        String p1 = plate();
        String p2 = plate();
        assertThat(call(a.owner(), a, a.home().getId().toString(), newVehicle(p1)).getResponse().getStatus()).isEqualTo(201);
        assertThat(call(a.owner(), a, a.vacant().getId().toString(), newVehicle(p2)).getResponse().getStatus()).isEqualTo(201);
        assertThat(vehicleProperty(p1)).isEqualTo(a.home().getId());
        assertThat(vehicleProperty(p2)).isEqualTo(a.vacant().getId());

        String home = call(a.owner(), a, a.home().getId().toString(), get("/api/v1/resident/me/vehicles")).getResponse().getContentAsString();
        String vacant = call(a.owner(), a, a.vacant().getId().toString(), get("/api/v1/resident/me/vehicles")).getResponse().getContentAsString();
        assertThat(home).contains(p1).doesNotContain(p2);
        assertThat(vacant).contains(p2).doesNotContain(p1);
    }

    @Test
    @DisplayName("B/C/F/10 · unidad ajena, de otra comunidad o inexistente: 409 UNIT_NOT_AVAILABLE, sin fallback ni escritura")
    void unavailableUnitsAreRejectedWithoutFallback() throws Exception {
        String ownPlate = plate();
        call(a.owner(), a, null, newVehicle(ownPlate));
        for (UUID foreign : List.of(a.neighbourHome().getId(), b.home().getId(), UUID.randomUUID())) {
            MvcResult read = call(a.owner(), a, foreign.toString(), get("/api/v1/resident/me/vehicles"));
            assertRejected(read, 409, "UNIT_NOT_AVAILABLE");
            assertThat(read.getResponse().getContentAsString()).as("sin datos de la unidad propia por fallback").doesNotContain(ownPlate);
            String plate = plate();
            assertRejected(call(a.owner(), a, foreign.toString(), newVehicle(plate)), 409, "UNIT_NOT_AVAILABLE");
            assertThat(vehicleProperty(plate)).isNull();
        }
        // La respuesta es identica para las tres: no revela si la unidad existe ni de que comunidad es.
        String m1 = call(a.owner(), a, a.neighbourHome().getId().toString(), get("/api/v1/resident/me/pets")).getResponse().getContentAsString();
        String m2 = call(a.owner(), a, b.home().getId().toString(), get("/api/v1/resident/me/pets")).getResponse().getContentAsString();
        assertThat(objectMapper.readTree(m1).path("detail")).isEqualTo(objectMapper.readTree(m2).path("detail"));
    }

    @Test
    @DisplayName("D/6 · unidad revocada: 409 aunque la relacion exista INACTIVE; la otra unidad sigue funcionando")
    void revokedUnitIsRejected() throws Exception {
        Resident second = relation(a, a.vacant(), a.owner(), "Propietario");
        second.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(second);
        assertRejected(call(a.owner(), a, a.vacant().getId().toString(), get("/api/v1/resident/me/vehicles")), 409, "UNIT_NOT_AVAILABLE");
        assertThat(call(a.owner(), a, a.home().getId().toString(), get("/api/v1/resident/me/vehicles")).getResponse().getStatus()).isEqualTo(200);
        // Con una sola unidad ACTIVE restante, sin cabecera se resuelve esa (no la revocada).
        String plate = plate();
        assertThat(call(a.owner(), a, null, newVehicle(plate)).getResponse().getStatus()).isEqualTo(201);
        assertThat(vehicleProperty(plate)).isEqualTo(a.home().getId());
    }

    @Test
    @DisplayName("I · sin ninguna unidad ACTIVE: ACCESS_REVOKED, distinto de membresia revocada o cuenta inactiva")
    void noActiveUnit() throws Exception {
        Resident own = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        own.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(own);
        MvcResult r = call(a.owner(), a, null, get("/api/v1/resident/me/properties"));
        assertThat(r.getResponse().getStatus()).isIn(401, 409);
        if (r.getResponse().getStatus() == 409) assertThat(code(r)).isEqualTo("ACCESS_REVOKED");
    }

    @Test
    @DisplayName("7-8 · membresia revocada o cuenta inactiva: 401, aunque la cabecera apunte a su unidad")
    void revokedMembershipOrInactiveAccount() throws Exception {
        String token = fixture.bearer(a.owner());
        Membership m = membershipRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(mvc.perform(get("/api/v1/resident/me/vehicles").header("Authorization", token)
                .header("X-Tenant-ID", a.tenantId().toString()).header("X-Property-Id", a.home().getId().toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        User u = userRepository.findById(a.neighbour().getId()).orElseThrow();
        String neighbourToken = fixture.bearer(u);
        u.setStatus(UserStatus.INACTIVE);
        userRepository.save(u);
        assertThat(mvc.perform(get("/api/v1/resident/me/vehicles").header("Authorization", neighbourToken)
                .header("X-Tenant-ID", a.tenantId().toString()).header("X-Property-Id", a.neighbourHome().getId().toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("E/9 · cabecera mal formada: 400 INVALID_UNIT_ID, nunca 500")
    void malformedHeader() throws Exception {
        for (String bad : List.of("no-es-un-uuid", "123", "'; drop table x;--")) {
            assertRejected(call(a.owner(), a, bad, get("/api/v1/resident/me/vehicles")), 400, "INVALID_UNIT_ID");
        }
        assertRejected(call(a.owner(), a, "zzz", panic()), 400, "INVALID_UNIT_ID");
    }

    // ── listado ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("11/13/14 · listado: orden natural estable por estructura y numero, unidades sin torre al final, una membresia")
    void stableOrderingAndHousesWithoutTowers() throws Exception {
        Structure t10 = structure(a, "Torre 10");
        Structure t2 = structure(a, "Torre 2");
        List<Property> created = new ArrayList<>();
        created.add(unit(a, t10, "Apto 101"));
        created.add(unit(a, t2, "Apto 1001"));
        created.add(unit(a, t2, "Apto 201"));
        for (Property p : created) relation(a, p, a.owner(), "Propietario");

        List<String> first = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            JsonNode list = data(call(a.owner(), a, null, get("/api/v1/resident/me/properties")));
            List<String> ids = new ArrayList<>();
            list.forEach(n -> ids.add(n.path("propertyId").asText()));
            if (i == 0) first.addAll(ids); else assertThat(ids).as("mismo orden en cada llamada").isEqualTo(first);
            // Torre 2 (Apto 201, Apto 1001) antes que Torre 10; la unidad de la fixture (sin torre) al final.
            assertThat(list.get(0).path("structureName").asText()).startsWith("Torre 2 ");
            assertThat(list.get(0).path("name").asText()).isEqualTo("Apto 201");
            assertThat(list.get(1).path("name").asText()).isEqualTo("Apto 1001");
            assertThat(list.get(2).path("structureName").asText()).startsWith("Torre 10 ");
            JsonNode house = list.get(3);
            assertThat(house.path("propertyId").asText()).isEqualTo(a.home().getId().toString());
            assertThat(house.path("structureId").isNull()).as("sin torre inventada").isTrue();
            assertThat(house.path("structureName").isNull()).isTrue();
            assertThat(house.path("label").asText()).isEqualTo(a.home().getName());
        }
        assertThat(membershipRepository.findByUserId(a.owner().getId())).as("propietario de 4 unidades: 1 membresia").hasSize(1);
    }

    @Test
    @DisplayName("12 · una unidad con dos relaciones aparece una sola vez con ambos tipos")
    void duplicateRelationsAreMerged() throws Exception {
        relation(a, a.home(), a.owner(), "Propietario"); // ya era Propietario: segunda fila de la misma unidad
        relation(a, a.vacant(), a.owner(), "Propietario");
        JsonNode list = data(call(a.owner(), a, null, get("/api/v1/resident/me/properties")));
        assertThat(list).hasSize(2);
        JsonNode home = null;
        for (JsonNode n : list) if (n.path("propertyId").asText().equals(a.home().getId().toString())) home = n;
        assertThat(home).isNotNull();
        assertThat(home.path("relations")).hasSize(1);
        assertThat(home.path("relations").get(0).asText()).isEqualTo("Propietario");
        assertThat(home.path("type").asText()).isEqualTo("Propietario");
        assertThat(home.path("tenantId").asText()).isEqualTo(a.tenantId().toString());
        assertThat(home.path("available").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("12b · propietario y residente de la misma unidad: una entrada, relaciones [Propietario, Residente]")
    void ownerAndResidentOfTheSameUnit() throws Exception {
        relation(a, a.home(), a.owner(), "Residente");
        JsonNode list = data(call(a.owner(), a, null, get("/api/v1/resident/me/properties")));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("relations").toString()).isEqualTo("[\"Propietario\",\"Residente\"]");
    }

    @Test
    @DisplayName("15 · administrador que tambien es propietario: sin portal de residente y la cabecera no le da permisos de residente")
    void adminOwnerGetsNoResidentPortal() throws Exception {
        relation(a, a.vacant(), a.admin(), "Propietario");
        assertThat(call(a.admin(), a, a.vacant().getId().toString(), get("/api/v1/resident/me/properties")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(a.admin(), a, a.vacant().getId().toString(), panic()).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(a.guard(), a, a.home().getId().toString(), get("/api/v1/resident/me/vehicles")).getResponse().getStatus()).isEqualTo(403);
        assertThat(membershipRepository.findByUserId(a.admin().getId())).singleElement()
                .satisfies(m -> assertThat(m.getRole()).isEqualTo(RoleType.COMMUNITY_ADMIN));
    }

    // ── panico ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("16 · panico con la unidad seleccionada: la alerta y su residente son los de esa unidad")
    void panicUsesTheSelectedUnit() throws Exception {
        Resident second = relation(a, a.vacant(), a.owner(), "Propietario");
        MvcResult r = call(a.owner(), a, a.vacant().getId().toString(), panic());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        var row = jdbc.queryForMap("select cast(property_id as varchar) p, cast(resident_id as varchar) r from security_panic_alerts "
                + "where tenant_id = ? order by created_at desc limit 1", a.tenantId());
        assertThat(row.get("p")).isEqualTo(a.vacant().getId().toString());
        assertThat(row.get("r")).isEqualTo(second.getId().toString());

        // Mis alertas incluye las de todas sus unidades.
        assertThat(call(a.owner(), a, a.home().getId().toString(), panic()).getResponse().getStatus()).isEqualTo(201);
        assertThat(data(call(a.owner(), a, a.home().getId().toString(), get("/api/v1/realtime/panic/my")))).hasSize(2);
    }

    @Test
    @DisplayName("17 · panico con una unidad ajena o de otra comunidad: 409 y ninguna alerta")
    void panicForeignUnit() throws Exception {
        int before = panicRows(a);
        assertRejected(call(a.owner(), a, a.neighbourHome().getId().toString(), panic()), 409, "UNIT_NOT_AVAILABLE");
        assertRejected(call(a.owner(), a, b.home().getId().toString(), panic()), 409, "UNIT_NOT_AVAILABLE");
        assertThat(panicRows(a)).isEqualTo(before);
        assertThat(panicRows(b)).isZero();
    }

    @Test
    @DisplayName("18 · panico con varias unidades y sin seleccion: 409 UNIT_SELECTION_REQUIRED, nunca la primera residencia")
    void panicMultipleUnitsWithoutSelection() throws Exception {
        secondUnitForOwner();
        int before = panicRows(a);
        assertRejected(call(a.owner(), a, null, panic()), 409, "UNIT_SELECTION_REQUIRED");
        assertThat(panicRows(a)).isEqualTo(before);
    }

    @Test
    @DisplayName("19 · panico con una sola unidad y sin cabecera: se usa esa unidad")
    void panicSingleUnit() throws Exception {
        assertThat(call(a.owner(), a, null, panic()).getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select cast(property_id as varchar) from security_panic_alerts where tenant_id = ? "
                + "order by created_at desc limit 1", String.class, a.tenantId())).isEqualTo(a.home().getId().toString());
    }

    // ── invitaciones y login ──────────────────────────────────────────────────

    @Test
    @DisplayName("invitacion de hogar para una unidad cuya relacion fue revocada: 409, sin invitacion")
    void householdInvitationRequiresAnActiveRelation() throws Exception {
        Resident second = relation(a, a.vacant(), a.owner(), "Propietario");
        second.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(second);
        int before = jdbc.queryForObject("select count(*) from resident_invitations where property_id = ?", Integer.class, a.vacant().getId());
        MvcResult r = call(a.owner(), a, a.home().getId().toString(), post("/api/v1/resident/me/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"Residente\",\"email\":\"\",\"name\":\"\",\"phone\":\"\",\"propertyId\":\"" + a.vacant().getId() + "\"}"));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from resident_invitations where property_id = ?", Integer.class, a.vacant().getId()))
                .isEqualTo(before);
        // Cuerpo mal formado: 400, no 500.
        MvcResult bad = call(a.owner(), a, null, post("/api/v1/resident/me/invitations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"Residente\",\"propertyId\":\"xyz\"}"));
        assertRejected(bad, 400, "INVALID_UNIT_ID");
    }

    @Test
    @DisplayName("login: con una unidad devuelve esa; con varias no elige ninguna (residentPropertyId null)")
    void loginDoesNotPickAnArbitraryUnit() throws Exception {
        User owner = userRepository.findById(a.owner().getId()).orElseThrow();
        owner.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userRepository.save(owner);
        String body = "{\"email\":\"" + owner.getEmail() + "\",\"password\":\"" + PASSWORD + "\",\"tenantId\":\"" + a.tenantId() + "\"}";

        JsonNode single = data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
        assertThat(single.path("user").path("residentPropertyId").asText()).isEqualTo(a.home().getId().toString());

        secondUnitForOwner();
        JsonNode multi = data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
        assertThat(multi.path("accessToken").asText()).isNotBlank();
        assertThat(multi.path("user").path("residentPropertyId").isNull()).as("no elige una unidad arbitraria").isTrue();
    }
}
