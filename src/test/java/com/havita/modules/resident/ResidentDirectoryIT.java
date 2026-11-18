package com.havita.modules.resident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.User;
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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-008: GET /residents (Directorio). Filtros combinables por estado, tipo y busqueda (incluida la unidad y la
 * torre), paginacion y totales calculados en PostgreSQL, orden determinista, sin N+1 y siempre dentro del tenant.
 */
class ResidentDirectoryIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private StructureRepository structureRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Side a;
    private Side b;
    private String tag;
    private Property tower2Unit;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        tag = "dir" + UUID.randomUUID().toString().substring(0, 6);

        Structure t1 = structure(a, "Torre 1 " + tag);
        Structure t2 = structure(a, "Torre 2 " + tag);
        Property t1Unit = property(a, t1, "T1-P1-101-" + tag);
        tower2Unit = property(a, t2, "T2-P3-302-" + tag);

        // 12 en la torre 1: 6 Propietario ACTIVE, 3 Arrendatario ACTIVE, 3 Propietario INACTIVE.
        // P0-B: cada arrendataria ACTIVE en su propia unidad de la torre 1 (una unidad admite un solo arrendatario activo,
        // indice real uq_active_arrendatario_per_property). Los totales, la torre y el orden por unidad no cambian.
        for (int i = 0; i < 6; i++) resident(a, t1Unit, null, tag + " Propietaria " + i, "Propietario", ResidentStatus.ACTIVE, "DOC-P" + i);
        for (int i = 0; i < 3; i++) resident(a, property(a, t1, "T1-P1-10" + (2 + i) + "-" + tag), null,
                tag + " Arrendataria " + i, "Arrendatario", ResidentStatus.ACTIVE, "DOC-A" + i);
        for (int i = 0; i < 3; i++) resident(a, t1Unit, null, tag + " Antigua " + i, "Propietario", ResidentStatus.INACTIVE, "DOC-X" + i);
        // Torre 2: un familiar y dos personas con el mismo nombre (desempate por id)
        resident(a, tower2Unit, null, tag + " Familiar", "Residente", ResidentStatus.ACTIVE, "DOC-F");
        resident(a, tower2Unit, null, tag + " Repetido", "Propietario", ResidentStatus.ACTIVE, "DOC-R1");
        resident(a, tower2Unit, null, tag + " Repetido", "Propietario", ResidentStatus.ACTIVE, "DOC-R2");
        // Otra comunidad con el mismo texto de busqueda
        Property bUnit = property(b, null, "B-101-" + tag);
        resident(b, bUnit, null, tag + " Ajena", "Propietario", ResidentStatus.ACTIVE, "DOC-B");
    }

    private Structure structure(Side side, String name) {
        Structure s = Structure.builder().name(name).build();
        s.setTenantId(side.tenantId());
        return structureRepository.save(s);
    }

    private Property property(Side side, Structure structure, String number) {
        Property p = Property.builder().structure(structure).number(number).name("Apto " + number).status(PropertyStatus.OCCUPIED).build();
        p.setTenantId(side.tenantId());
        return propertyRepository.save(p);
    }

    private Resident resident(Side side, Property property, User user, String name, String type, ResidentStatus status, String doc) {
        Resident r = Resident.builder().property(property).user(user).name(name).type(type).status(status)
                .email(name.toLowerCase().replace(' ', '.') + "." + UUID.randomUUID().toString().substring(0, 4) + "@example.test")
                .phone("3100000000").documentId(doc + "-" + tag).build();
        r.setTenantId(side.tenantId());
        return residentRepository.save(r);
    }

    private JsonNode page(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        String body = mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode directory(String... params) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/v1/residents");
        for (int i = 0; i < params.length; i += 2) req = req.param(params[i], params[i + 1]);
        return page(a.admin(), a, req);
    }

    private static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    @Test
    @DisplayName("Estado: ACTIVE e INACTIVE filtran; sin estado devuelve todos (el frontend pide ACTIVE por defecto)")
    void statusFilter() throws Exception {
        assertThat(directory("search", tag, "status", "ACTIVE").get("totalElements").asLong()).isEqualTo(12);
        JsonNode inactive = directory("search", tag, "status", "INACTIVE");
        assertThat(inactive.get("totalElements").asLong()).isEqualTo(3);
        assertThat(names(inactive)).allMatch(n -> n.contains("Antigua"));
        assertThat(directory("search", tag).get("totalElements").asLong()).isEqualTo(15);
    }

    @Test
    @DisplayName("Tipo (valores del dominio, sin distinguir mayusculas) y combinacion con estado y busqueda")
    void typeFilterCombined() throws Exception {
        assertThat(directory("search", tag, "type", "Arrendatario").get("totalElements").asLong()).isEqualTo(3);
        assertThat(directory("search", tag, "type", "residente").get("totalElements").asLong()).isEqualTo(1);
        assertThat(directory("search", tag, "type", "Propietario", "status", "ACTIVE").get("totalElements").asLong()).isEqualTo(8);
        assertThat(directory("search", tag, "type", "Propietario", "status", "INACTIVE").get("totalElements").asLong()).isEqualTo(3);
        assertThat(directory("search", tag + " Antigua", "type", "Propietario", "status", "ACTIVE").get("totalElements").asLong()).isZero();
        assertThat(directory("search", tag, "type", "Inexistente").get("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("Busqueda por unidad, por torre y por documento; los comodines escritos son literales")
    void searchByUnitTowerAndDocument() throws Exception {
        assertThat(directory("search", "T2-P3-302-" + tag).get("totalElements").asLong()).isEqualTo(3);
        assertThat(directory("search", "Torre 2 " + tag).get("totalElements").asLong()).isEqualTo(3);
        assertThat(directory("search", "doc-a1-" + tag).get("totalElements").asLong()).isEqualTo(1);
        assertThat(directory("search", "%" + tag).get("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("Paginacion real: totales segun los filtros, sin duplicados ni huecos y orden determinista por nombre e id")
    void paginationIsConsistent() throws Exception {
        List<String> ids = new ArrayList<>();
        List<String> seenNames = new ArrayList<>();
        int pages = 0;
        for (int p = 0; ; p++) {
            JsonNode page = directory("search", tag, "status", "ACTIVE", "page", String.valueOf(p), "size", "5");
            assertThat(page.get("totalElements").asLong()).isEqualTo(12);
            assertThat(page.get("totalPages").asInt()).isEqualTo(3);
            page.get("content").forEach(n -> { ids.add(n.get("id").asText()); seenNames.add(n.get("name").asText()); });
            pages++;
            if (page.get("isLast").asBoolean()) break;
        }
        assertThat(pages).isEqualTo(3);
        assertThat(ids).hasSize(12);
        assertThat(new HashSet<>(ids)).hasSize(12);
        assertThat(seenNames).isSortedAccordingTo(String::compareTo);

        // Dos consultas iguales devuelven el mismo orden (incluidos los dos "Repetido", desempatados por id).
        assertThat(names(directory("search", tag, "status", "ACTIVE", "size", "50")))
                .containsExactlyElementsOf(names(directory("search", tag, "status", "ACTIVE", "size", "50")));
        // Una pagina fuera de rango llega vacia pero con el total correcto (el frontend vuelve a la ultima).
        JsonNode beyond = directory("search", tag, "status", "ACTIVE", "page", "9", "size", "5");
        assertThat(beyond.get("content").size()).isZero();
        assertThat(beyond.get("totalElements").asLong()).isEqualTo(12);
    }

    @Test
    @DisplayName("Orden por unidad permitido; un orden no permitido se ignora sin error")
    void sortingWhitelist() throws Exception {
        List<String> byUnit = new ArrayList<>();
        directory("search", tag, "status", "ACTIVE", "size", "50", "sort", "propertyNumber,desc").get("content")
                .forEach(n -> byUnit.add(n.get("propertyNumber").asText()));
        assertThat(byUnit.get(0)).startsWith("T2-");
        assertThat(directory("search", tag, "sort", "passwordHash,asc").get("totalElements").asLong()).isEqualTo(15);
    }

    @Test
    @DisplayName("Una persona con dos unidades aparece una vez por unidad (un registro del censo por relacion)")
    void multiUnitPerson() throws Exception {
        Property second = property(a, null, "T9-901-" + tag);
        Resident first = resident(a, tower2Unit, a.owner(), tag + " Multiunidad", "Propietario", ResidentStatus.ACTIVE, "DOC-M1");
        resident(a, second, a.owner(), tag + " Multiunidad", "Propietario", ResidentStatus.ACTIVE, "DOC-M2");
        JsonNode page = directory("search", tag + " Multiunidad");
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        Set<String> units = new HashSet<>();
        page.get("content").forEach(n -> units.add(n.get("propertyNumber").asText()));
        assertThat(units).containsExactlyInAnyOrder(tower2Unit.getNumber(), second.getNumber());
        assertThat(first.getId()).isNotNull();
    }

    @Test
    @DisplayName("Aislamiento: nunca aparecen residentes de otra comunidad; sin rol de gestion no hay acceso")
    void tenantIsolationAndPermissions() throws Exception {
        assertThat(names(directory("search", tag, "size", "100"))).noneMatch(n -> n.contains("Ajena"));
        assertThat(page(b.admin(), b, get("/api/v1/residents").param("search", tag)).get("totalElements").asLong()).isEqualTo(1);

        int asResident = mvc.perform(get("/api/v1/residents").param("search", tag)
                        .header("Authorization", fixture.bearer(a.owner())).header("X-Tenant-ID", a.tenantId().toString()))
                .andReturn().getResponse().getStatus();
        assertThat(asResident).isEqualTo(403);
        int forged = mvc.perform(get("/api/v1/residents").param("search", tag)
                        .header("Authorization", fixture.bearer(a.admin())).header("X-Tenant-ID", b.tenantId().toString()))
                .andReturn().getResponse().getStatus();
        assertThat(forged).isIn(401, 403);
        assertThat(page(a.guard(), a, get("/api/v1/residents").param("search", tag)).get("totalElements").asLong()).isEqualTo(15);
    }

    @Test
    @DisplayName("Sin N+1: el numero de sentencias SQL no depende del tamano de la pagina")
    void noNPlusOne() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            directory("search", tag, "size", "1");                    // calentamiento
            stats.clear();
            directory("search", tag, "size", "1");
            long small = stats.getPrepareStatementCount();
            stats.clear();
            directory("search", tag, "size", "15");
            long large = stats.getPrepareStatementCount();
            assertThat(large).isEqualTo(small);
            // SEC-9: +1 consulta fija por peticion (permisos efectivos desde PostgreSQL), independiente de la pagina.
            assertThat(large).isLessThanOrEqualTo(5);
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }
}
