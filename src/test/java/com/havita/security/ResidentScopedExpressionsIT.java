package com.havita.security;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import com.havita.support.UnitDataFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cierre SEC-9: las 14 expresiones que comprueban el recurso solo para RESIDENT
 * ({@code hasAnyRole(...) or (hasRole('RESIDENT') and @accessValidator...)}). Para los demas roles la expresion solo
 * mira el rol, asi que la comunidad la tiene que imponer el servicio. Esta prueba lo demuestra con datos reales de una
 * unidad de A (paquete, factura con saldo, reserva, residente; el chat por unidad se retiro en la Fase 3):
 * <ul>
 *   <li>dentro de A: cada rol permitido llega (no 401/403) y el resto recibe 403, incluido un rol con residencia;</li>
 *   <li>desde B: administracion, porteria, STAFF, contabilidad y residente no obtienen ningun dato de A ni escriben;</li>
 *   <li>RESIDENT: su unidad si, la de su vecino no.</li>
 * </ul>
 */
class ResidentScopedExpressionsIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UnitDataFixture unitData;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private JdbcTemplate jdbc;

    private Side a;
    private Side b;
    /** Unidad objetivo de A (la del vecino): tiene paquete, factura PENDIENTE, reserva y residente. */
    private UUID target;
    private UUID targetResident;
    private Map<RoleType, User> rolesA;
    private Map<RoleType, User> rolesB;

    private record Ep(String name, Set<RoleType> roles, boolean hasData, Supplier<MockHttpServletRequestBuilder> req) {}

    private static final Set<RoleType> OPS = EnumSet.of(RoleType.COMMUNITY_ADMIN, RoleType.SECURITY, RoleType.STAFF);
    private static final Set<RoleType> MONEY = EnumSet.of(RoleType.COMMUNITY_ADMIN, RoleType.ACCOUNTANT);
    private static final Set<RoleType> ADMIN = EnumSet.of(RoleType.COMMUNITY_ADMIN);

    /** Las 14 rutas, con los roles no RESIDENT que su expresion admite (ademas de SUPER_ADMIN). */
    private List<Ep> endpoints() {
        return List.of(
                new Ep("paquetes por unidad", OPS, true, () -> get("/api/v1/access/packages/by-property/{propertyId}", target)),
                new Ep("cuentas de cobro por unidad", MONEY, true, () -> get("/api/v1/accounting/invoices/by-property/{propertyId}", target)),
                new Ep("saldo de la unidad", MONEY, false, () -> get("/api/v1/accounting/properties/{propertyId}/balance", target)),
                new Ep("PQRS por unidad", OPS, false, () -> get("/api/v1/pqrs/by-property/{propertyId}", target)),
                new Ep("ficha de la unidad", OPS, true, () -> get("/api/v1/properties/{id}", target)),
                new Ep("revocar delegacion de voto", ADMIN, false, () -> delete("/api/v1/properties/{propertyId}/voting-delegation", target)),
                new Ep("consultar delegacion de voto", ADMIN, false, () -> get("/api/v1/properties/{propertyId}/voting-delegation", target)),
                new Ep("delegar voto", ADMIN, false, () -> post("/api/v1/properties/{propertyId}/voting-delegation", target)),
                new Ep("paz y salvo", MONEY, false, () -> get("/api/v1/reporting/properties/{propertyId}/paz-y-salvo", target)),
                new Ep("reservas por unidad", OPS, true, () -> get("/api/v1/reservations/by-property/{propertyId}", target)),
                new Ep("residentes por unidad", OPS, true, () -> get("/api/v1/residents/by-property/{propertyId}", target)),
                new Ep("mascotas por unidad", OPS, false, () -> get("/api/v1/residents/pets/by-property/{propertyId}", target)),
                new Ep("vehiculos por unidad", OPS, false, () -> get("/api/v1/residents/vehicles/by-property/{propertyId}", target)),
                new Ep("residente por id", OPS, true, () -> get("/api/v1/residents/{id}", targetResident)));
    }

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        unitData.seed(a);
        unitData.seed(b);
        target = a.neighbourHome().getId();
        targetResident = a.neighbourResident().getId();
        rolesA = roles(a);
        rolesB = roles(b);
    }

    private Map<RoleType, User> roles(Side side) {
        Map<RoleType, User> m = new LinkedHashMap<>();
        m.put(RoleType.COMMUNITY_ADMIN, side.admin());
        m.put(RoleType.SECURITY, side.guard());
        m.put(RoleType.STAFF, member(side, RoleType.STAFF));
        m.put(RoleType.ACCOUNTANT, member(side, RoleType.ACCOUNTANT));
        m.put(RoleType.READ_ONLY, member(side, RoleType.READ_ONLY)); // rol "global" asignado a una comunidad (caso R1)
        return m;
    }

    private User member(Side side, RoleType role) {
        String handle = role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder().email(handle + "@example.test").passwordHash("{noop}x")
                .fullName("Cuenta " + handle).phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(side.tenantId()).role(role)
                .status(MembershipStatus.ACTIVE).build());
        return u;
    }

    private MvcResult call(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(u)).header("X-Tenant-ID", side.tenantId().toString()))
                .andReturn();
    }

    private static String what(Ep ep, String who, MvcResult r) throws Exception {
        return ep.name() + " como " + who + " -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString();
    }

    private int countA(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Integer.class, a.tenantId());
    }

    @Test
    @DisplayName("dentro de A: cada rol permitido llega a los datos de la unidad y los demas reciben 403")
    void roleMatrixInsideTheCommunity() throws Exception {
        for (Ep ep : endpoints()) {
            for (Map.Entry<RoleType, User> e : rolesA.entrySet()) {
                MvcResult r = call(e.getValue(), a, ep.req().get());
                int status = r.getResponse().getStatus();
                if (ep.roles().contains(e.getKey())) {
                    assertThat(status).as(what(ep, e.getKey().name(), r)).isNotIn(401, 403);
                    if (ep.hasData() && status == 200) {
                        assertThat(r.getResponse().getContentAsString()).as(what(ep, e.getKey().name(), r))
                                .contains(target.toString());
                    }
                } else {
                    assertThat(status).as(what(ep, e.getKey().name(), r)).isEqualTo(403);
                }
            }
        }
    }

    @Test
    @DisplayName("desde B: ningun rol (administracion, porteria, STAFF, contabilidad, residente) lee ni escribe en la unidad de A")
    void otherCommunityGetsNothing() throws Exception {
        int delegations = countA("voting_delegations");
        String residentName = a.neighbourResident().getName();
        Map<RoleType, User> attackers = new LinkedHashMap<>(rolesB);
        attackers.put(RoleType.RESIDENT, b.owner());
        for (Ep ep : endpoints()) {
            for (Map.Entry<RoleType, User> e : attackers.entrySet()) {
                MvcResult r = call(e.getValue(), b, ep.req().get());
                int status = r.getResponse().getStatus();
                String body = r.getResponse().getContentAsString();
                if ("GET".equals(r.getRequest().getMethod()) && status == 200) {
                    // Las listas filtradas por comunidad pueden responder 200, pero vacias.
                    assertThat(body).as(what(ep, "B/" + e.getKey(), r)).doesNotContain(target.toString()).doesNotContain(residentName);
                } else {
                    assertThat(status).as(what(ep, "B/" + e.getKey(), r)).isNotIn(200, 201, 202, 204);
                }
            }
        }
        assertThat(countA("voting_delegations")).as("delegaciones de A intactas").isEqualTo(delegations);
    }

    @Test
    @DisplayName("RESIDENT: su unidad si; la unidad del vecino, no (403)")
    void residentBranch() throws Exception {
        for (Ep ep : endpoints()) {
            MvcResult own = call(a.neighbour(), a, ep.req().get());
            assertThat(own.getResponse().getStatus()).as(what(ep, "residente titular", own)).isNotIn(401, 403);
            MvcResult other = call(a.owner(), a, ep.req().get());
            assertThat(other.getResponse().getStatus()).as(what(ep, "residente de otra unidad", other)).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("regla de rol unico: una residencia ACTIVE no da permisos de residente a porteria, STAFF ni contabilidad")
    void residenceDoesNotAddPermissionsToAnotherRole() throws Exception {
        for (RoleType role : List.of(RoleType.SECURITY, RoleType.STAFF, RoleType.ACCOUNTANT, RoleType.READ_ONLY)) {
            User u = rolesA.get(role);
            // P0-B: la residencia sigue ACTIVE y en la unidad objetivo (neighbourHome). Una unidad admite un solo
            // arrendatario activo (uq_active_arrendatario_per_property): el primer rol es el arrendatario y los demas
            // son residentes no propietarios; cada usuario tiene una unica residencia no propietaria activa.
            String type = role == RoleType.SECURITY ? "Arrendatario" : "Residente";
            Resident r = Resident.builder().property(a.neighbourHome()).user(u).name("Residencia de " + role)
                    .type(type).email("res-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test")
                    .phone("3100000000").documentId("DOC-" + UUID.randomUUID().toString().substring(0, 6))
                    .status(ResidentStatus.ACTIVE).build();
            r.setTenantId(a.tenantId());
            residentRepository.save(r);
        }
        for (Ep ep : endpoints()) {
            for (RoleType role : List.of(RoleType.SECURITY, RoleType.STAFF, RoleType.ACCOUNTANT, RoleType.READ_ONLY)) {
                if (ep.roles().contains(role)) continue;
                MvcResult r = call(rolesA.get(role), a, ep.req().get());
                assertThat(r.getResponse().getStatus()).as(what(ep, role + " con residencia", r)).isEqualTo(403);
            }
        }
        // Ni el portal del residente.
        MvcResult portal = call(rolesA.get(RoleType.SECURITY), a, get("/api/v1/resident/me/vehicles"));
        assertThat(portal.getResponse().getStatus()).isEqualTo(403);
    }
}
