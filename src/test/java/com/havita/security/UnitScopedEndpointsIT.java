package com.havita.security;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import com.havita.support.UnitDataFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-3: los endpoints por unidad de paqueteria, facturacion, reservas, notificaciones y paz y salvo
 * solo responden a un RESIDENT para unidades en las que hoy tiene relacion ACTIVE y membresia ACTIVE,
 * comprobado contra PostgreSQL y no contra el JWT. Los roles administrativos conservan su acceso.
 */
class UnitScopedEndpointsIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UnitDataFixture unitData;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        unitData.seed(a);
        unitData.seed(b);
    }

    /** Endpoint por unidad y estado esperado para un COMMUNITY_ADMIN sobre la unidad del vecino (con deuda). */
    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("/api/v1/access/packages/by-property/{id}", 200),
                Arguments.of("/api/v1/accounting/invoices/by-property/{id}", 200),
                Arguments.of("/api/v1/accounting/properties/{id}/balance", 200),
                Arguments.of("/api/v1/reservations/by-property/{id}", 200),
                Arguments.of("/api/v1/realtime/notifications?propertyId={id}", 200),
                // La unidad del vecino tiene saldo pendiente: el admin esta autorizado y la regla de negocio responde 409.
                Arguments.of("/api/v1/reporting/properties/{id}/paz-y-salvo", 409));
    }

    private ResultActions as(User user, UUID tenantHeader, String path, UUID propertyId) throws Exception {
        return mvc.perform(get(path, propertyId)
                .header("Authorization", fixture.bearer(user))
                .header("X-Tenant-ID", tenantHeader.toString()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("RESIDENT: acceso autorizado a su propia unidad")
    void ownUnitIsReadable(String path, int ignored) throws Exception {
        as(a.owner(), a.tenantId(), path, a.home().getId()).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("RESIDENT: otra unidad de su comunidad responde 403")
    void neighbourUnitIsForbidden(String path, int ignored) throws Exception {
        as(a.owner(), a.tenantId(), path, a.neighbourHome().getId()).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("RESIDENT: una unidad de otra comunidad o un UUID inventado responden igual (403)")
    void foreignOrUnknownUnitIsForbidden(String path, int ignored) throws Exception {
        as(a.owner(), a.tenantId(), path, b.home().getId()).andExpect(status().isForbidden());
        as(a.owner(), a.tenantId(), path, UUID.randomUUID()).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("RESIDENT: con la relacion con la unidad INACTIVE pierde el acceso aunque su JWT siga vigente")
    void revokedRelationLosesAccess(String path, int ignored) throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        String token = fixture.bearer(a.owner());
        var rel = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        rel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(rel);

        mvc.perform(get(path, a.home().getId()).header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("RESIDENT: con la membresia INACTIVE pierde el acceso aunque su JWT siga vigente")
    void revokedMembershipLosesAccess(String path, int ignored) throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        String token = fixture.bearer(a.owner());
        var membership = membershipRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId()).orElseThrow();
        membership.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(membership);

        mvc.perform(get(path, a.home().getId()).header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("X-Tenant-ID de una comunidad sin membresia no autentica la peticion")
    void forgedTenantHeaderIsRejected(String path, int ignored) throws Exception {
        int status = as(a.owner(), b.tenantId(), path, b.home().getId()).andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("Residente en dos comunidades: en el contexto de B no lee su unidad de A")
    void membershipInAnotherTenantDoesNotCrossOver(String path, int ignored) throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        membershipRepository.save(Membership.builder()
                .user(a.owner()).tenantId(b.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());

        as(a.owner(), b.tenantId(), path, a.home().getId()).andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
        as(a.owner(), a.tenantId(), path, a.home().getId()).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("COMMUNITY_ADMIN conserva el acceso a cualquier unidad de su comunidad")
    void adminKeepsAccess(String path, int expectedForNeighbourUnit) throws Exception {
        as(a.admin(), a.tenantId(), path, a.neighbourHome().getId()).andExpect(status().is(expectedForNeighbourUnit));
        as(a.admin(), a.tenantId(), path, a.home().getId()).andExpect(status().isOk());
    }
}
