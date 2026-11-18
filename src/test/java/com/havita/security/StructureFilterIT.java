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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Fase 2 / E4 (D7): filtro por estructura en el directorio administrativo (GET /residents?structureId=…). Por
 * IDENTIFICADOR (nunca por nombre: "Torre 1" no incluye "Torre 10"), "none" para las unidades sin estructura, combinado
 * con busqueda y paginacion en la misma consulta, y sin aceptar estructuras de otra comunidad.
 */
class StructureFilterIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private StructureRepository structureRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;

    private Side a;
    private Side b;
    private Structure t1;
    private Structure t10;
    private Structure tB;
    private Resident houseResident;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        t1 = structure(a, "Torre 1");
        t10 = structure(a, "Torre 10");
        tB = structure(b, "Torre 1");
        place(a.home(), t1);          // ownerResident y censusOnly
        place(a.neighbourHome(), t10); // neighbourResident
        place(b.home(), tB);
        // a.vacant queda SIN estructura (casa): un residente en ella.
        houseResident = Resident.builder().property(a.vacant()).name("Habitante Casa").type("Propietario")
                .email("casa-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test").status(ResidentStatus.ACTIVE).build();
        houseResident.setTenantId(a.tenantId());
        houseResident = residentRepository.save(houseResident);
    }

    private Structure structure(Side side, String name) {
        Structure s = Structure.builder().name(name).type("Torre").build();
        s.setTenantId(side.tenantId());
        return structureRepository.save(s);
    }

    private void place(Property p, Structure s) {
        Property x = propertyRepository.findById(p.getId()).orElseThrow();
        x.setStructure(s);
        propertyRepository.save(x);
    }

    private User member(Side side, RoleType role) {
        String h = role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder().email(h + "@example.test").passwordHash("{noop}x").fullName("Cuenta " + h)
                .phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(side.tenantId()).role(role).status(MembershipStatus.ACTIVE).build());
        return u;
    }

    private MvcResult call(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(u)).header("X-Tenant-ID", side.tenantId().toString())).andReturn();
    }

    private JsonNode page(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = call(u, side, req);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
    }

    private static Set<String> ids(JsonNode page) {
        Set<String> s = new HashSet<>();
        page.path("content").forEach(n -> s.add(n.path("id").asText()));
        return s;
    }

    @Test
    @DisplayName("1-3/8 · por estructura: Torre 1 no incluye Torre 10; cambiar de estructura cambia el resultado")
    void byStructureIdExact() throws Exception {
        JsonNode p1 = page(a.admin(), a, get("/api/v1/residents").param("structureId", t1.getId().toString()));
        assertThat(ids(p1)).containsExactlyInAnyOrder(a.ownerResident().getId().toString(), a.censusOnly().getId().toString());
        JsonNode p10 = page(a.admin(), a, get("/api/v1/residents").param("structureId", t10.getId().toString()));
        assertThat(ids(p10)).containsExactly(a.neighbourResident().getId().toString());
        assertThat(p10.path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("4/5/7 · «Todas» y «Sin estructura»: las unidades sin torre siguen accesibles")
    void allAndWithoutStructure() throws Exception {
        JsonNode all = page(a.admin(), a, get("/api/v1/residents").param("size", "50"));
        assertThat(ids(all)).contains(a.ownerResident().getId().toString(), a.neighbourResident().getId().toString(),
                houseResident.getId().toString());
        JsonNode none = page(a.admin(), a, get("/api/v1/residents").param("structureId", "none"));
        assertThat(ids(none)).containsExactly(houseResident.getId().toString());
    }

    @Test
    @DisplayName("9/10 · combinado con búsqueda y paginación: totales y páginas sobre el conjunto filtrado")
    void withSearchAndPagination() throws Exception {
        JsonNode search = page(a.admin(), a, get("/api/v1/residents").param("structureId", t1.getId().toString())
                .param("search", "Familiar"));
        assertThat(ids(search)).containsExactly(a.censusOnly().getId().toString());
        JsonNode first = page(a.admin(), a, get("/api/v1/residents").param("structureId", t1.getId().toString())
                .param("size", "1").param("page", "0"));
        JsonNode second = page(a.admin(), a, get("/api/v1/residents").param("structureId", t1.getId().toString())
                .param("size", "1").param("page", "1"));
        assertThat(first.path("totalElements").asLong()).isEqualTo(2);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        Set<String> both = new HashSet<>(ids(first));
        both.addAll(ids(second));
        assertThat(both).containsExactlyInAnyOrder(a.ownerResident().getId().toString(), a.censusOnly().getId().toString());
        // Estado vacío: estructura sin residentes coincidentes.
        JsonNode empty = page(a.admin(), a, get("/api/v1/residents").param("structureId", t10.getId().toString())
                .param("search", "no-existe-xyz"));
        assertThat(empty.path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("6/11 · estructura de otra comunidad o identificador inválido: rechazados sin datos")
    void foreignOrInvalidStructure() throws Exception {
        MvcResult foreign = call(a.admin(), a, get("/api/v1/residents").param("structureId", tB.getId().toString()));
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(foreign.getResponse().getContentAsString()).doesNotContain(b.ownerResident().getId().toString());
        MvcResult fromB = call(b.admin(), b, get("/api/v1/residents").param("structureId", t1.getId().toString()));
        assertThat(fromB.getResponse().getStatus()).isEqualTo(404);
        assertThat(call(a.admin(), a, get("/api/v1/residents").param("structureId", "no-es-uuid")).getResponse().getStatus()).isEqualTo(400);
        // B con su propia estructura solo ve lo suyo.
        assertThat(ids(page(b.admin(), b, get("/api/v1/residents").param("structureId", tB.getId().toString()))))
                .containsExactlyInAnyOrder(b.ownerResident().getId().toString(), b.censusOnly().getId().toString());
    }

    @Test
    @DisplayName("14/15 · roles: administración, portería y personal autorizados; residente sin permisos administrativos 403")
    void roles() throws Exception {
        for (User u : new User[]{a.admin(), a.guard(), member(a, RoleType.STAFF)}) {
            assertThat(call(u, a, get("/api/v1/residents").param("structureId", t1.getId().toString())).getResponse().getStatus())
                    .isEqualTo(200);
        }
        assertThat(call(a.owner(), a, get("/api/v1/residents").param("structureId", t1.getId().toString())).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(call(member(a, RoleType.ACCOUNTANT), a, get("/api/v1/residents").param("structureId", t1.getId().toString()))
                .getResponse().getStatus()).isEqualTo(403);
    }
}
