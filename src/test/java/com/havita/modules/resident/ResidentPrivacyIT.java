package com.havita.modules.resident;

import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Community;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-2: un RESIDENT solo consulta el censo, vehiculos, mascotas y ficha de las unidades en las que tiene
 * una relacion ACTIVE y membresia ACTIVE. Los roles administrativos conservan su acceso.
 */
class ResidentPrivacyIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        Community c = fixture.create();
        a = c.a();
        b = c.b();
    }

    private ResultActions getAs(User user, Side side, String path, Object... vars) throws Exception {
        return mvc.perform(get(path, vars)
                .header("Authorization", fixture.bearer(user))
                .header("X-Tenant-ID", side.tenantId().toString()));
    }

    // ── Negativos: otra unidad de la misma comunidad ───────────────────────────

    @Test
    @DisplayName("RESIDENT no puede listar el censo de otra unidad de su comunidad")
    void cannotListNeighbourCensus() throws Exception {
        getAs(a.owner(), a, "/api/v1/residents/by-property/{id}", a.neighbourHome().getId())
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RESIDENT no puede leer el registro de un vecino por su UUID")
    void cannotReadNeighbourById() throws Exception {
        getAs(a.owner(), a, "/api/v1/residents/{id}", a.neighbourResident().getId())
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RESIDENT no puede listar vehiculos ni mascotas de otra unidad")
    void cannotListNeighbourVehiclesOrPets() throws Exception {
        getAs(a.owner(), a, "/api/v1/residents/vehicles/by-property/{id}", a.neighbourHome().getId())
                .andExpect(status().isForbidden());
        getAs(a.owner(), a, "/api/v1/residents/pets/by-property/{id}", a.neighbourHome().getId())
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RESIDENT no puede leer la ficha de otra unidad")
    void cannotReadNeighbourProperty() throws Exception {
        getAs(a.owner(), a, "/api/v1/properties/{id}", a.neighbourHome().getId())
                .andExpect(status().isForbidden());
    }

    // ── Negativos: otra comunidad e identificadores inexistentes ───────────────

    @Test
    @DisplayName("RESIDENT no puede leer unidades ni residentes de otra comunidad")
    void cannotReadOtherTenant() throws Exception {
        getAs(a.owner(), a, "/api/v1/residents/by-property/{id}", b.home().getId()).andExpect(status().isForbidden());
        getAs(a.owner(), a, "/api/v1/residents/{id}", b.ownerResident().getId()).andExpect(status().isForbidden());
        getAs(a.owner(), a, "/api/v1/properties/{id}", b.home().getId()).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un UUID inexistente responde igual que uno ajeno (403), sin revelar si existe")
    void unknownIdsLookLikeForeignOnes() throws Exception {
        getAs(a.owner(), a, "/api/v1/residents/{id}", UUID.randomUUID()).andExpect(status().isForbidden());
        getAs(a.owner(), a, "/api/v1/residents/by-property/{id}", UUID.randomUUID()).andExpect(status().isForbidden());
    }

    // ── Negativos: relacion o membresia revocadas ──────────────────────────────

    @Test
    @DisplayName("Con la relacion con la unidad inactiva, el residente pierde el acceso aunque su JWT siga vigente")
    void inactiveRelationLosesAccess() throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        String token = fixture.bearer(a.owner());
        var rel = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        rel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(rel);

        mvc.perform(get("/api/v1/residents/by-property/{id}", a.home().getId())
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @Test
    @DisplayName("Con la membresia inactiva, el residente pierde el acceso aunque su JWT siga vigente")
    void inactiveMembershipLosesAccess() throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        String token = fixture.bearer(a.owner());
        var membership = membershipRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId()).orElseThrow();
        membership.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(membership);

        mvc.perform(get("/api/v1/residents/{id}", a.ownerResident().getId())
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    // ── Positivos: su propia unidad y roles administrativos ────────────────────

    @Test
    @DisplayName("RESIDENT consulta el censo, vehiculos, mascotas y ficha de su propia unidad")
    void canReadOwnUnit() throws Exception {
        getAs(a.owner(), a, "/api/v1/residents/by-property/{id}", a.home().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        getAs(a.owner(), a, "/api/v1/residents/{id}", a.censusOnly().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(a.censusOnly().getId().toString()));
        getAs(a.owner(), a, "/api/v1/residents/vehicles/by-property/{id}", a.home().getId()).andExpect(status().isOk());
        getAs(a.owner(), a, "/api/v1/residents/pets/by-property/{id}", a.home().getId()).andExpect(status().isOk());
        getAs(a.owner(), a, "/api/v1/properties/{id}", a.home().getId()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("COMMUNITY_ADMIN y SECURITY siguen consultando cualquier unidad de su comunidad")
    void administrativeRolesKeepAccess() throws Exception {
        getAs(a.admin(), a, "/api/v1/residents/by-property/{id}", a.neighbourHome().getId()).andExpect(status().isOk());
        getAs(a.admin(), a, "/api/v1/residents/{id}", a.neighbourResident().getId()).andExpect(status().isOk());
        getAs(a.guard(), a, "/api/v1/residents/by-property/{id}", a.neighbourHome().getId()).andExpect(status().isOk());
        getAs(a.guard(), a, "/api/v1/properties/{id}", a.neighbourHome().getId()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("COMMUNITY_ADMIN tampoco obtiene datos de otra comunidad por estos endpoints")
    void adminCannotReadOtherTenantUnit() throws Exception {
        getAs(a.admin(), a, "/api/v1/residents/by-property/{id}", b.home().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        getAs(a.admin(), a, "/api/v1/residents/{id}", b.ownerResident().getId()).andExpect(status().isNotFound());
        getAs(a.admin(), a, "/api/v1/properties/{id}", b.home().getId()).andExpect(status().isNotFound());
    }
}
