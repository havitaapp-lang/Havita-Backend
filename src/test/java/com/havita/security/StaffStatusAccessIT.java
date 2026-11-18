package com.havita.security;

import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.staff.domain.StaffMember;
import com.havita.modules.staff.domain.StaffMemberStatus;
import com.havita.modules.staff.infrastructure.StaffMemberRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/staff/{id}/status}: desactivar a un colaborador le quita el acceso al sistema en ESA comunidad (su
 * membresia de personal pasa a INACTIVE) y reactivarlo se lo devuelve. Antes la pantalla solo lo guardaba en el
 * navegador y un portero «suspendido» seguia entrando. Datos sinteticos.
 */
class StaffStatusAccessIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private StaffMemberRepository staffRepository;
    @Autowired private MembershipRepository membershipRepository;

    private Side a;
    private Side b;
    private StaffMember guardRecord;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        guardRecord = staff(a, a.guard());
    }

    private StaffMember staff(Side side, User user) {
        StaffMember s = StaffMember.builder().firstName("Portero").lastName("Sintetico").documentType("CC")
                .documentNumber("DOC" + UUID.randomUUID().toString().substring(0, 8)).position("Guarda").area("Portería")
                .contractType("Empleado").startDate(LocalDate.now()).status(StaffMemberStatus.ACTIVE).user(user).build();
        s.setTenantId(side.tenantId());
        return staffRepository.save(s);
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private MockHttpServletRequestBuilder setStatus(UUID id, String value) {
        return post("/api/v1/staff/{id}/status", id).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + value + "\"}");
    }

    private MembershipStatus guardMembership() {
        return membershipRepository.findByUserIdAndTenantId(a.guard().getId(), a.tenantId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("Desactivar al portero le quita el acceso en la siguiente petición; reactivarlo se lo devuelve")
    void deactivateRevokesAccess() throws Exception {
        as(a.guard(), a, get("/api/v1/access/packages")).andExpect(status().isOk());

        as(a.admin(), a, setStatus(guardRecord.getId(), "INACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
        assertThat(guardMembership()).isEqualTo(MembershipStatus.INACTIVE);
        int denied = as(a.guard(), a, get("/api/v1/access/packages")).andReturn().getResponse().getStatus();
        assertThat(denied).isIn(401, 403);

        as(a.admin(), a, setStatus(guardRecord.getId(), "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(guardMembership()).isEqualTo(MembershipStatus.ACTIVE);
        as(a.guard(), a, get("/api/v1/access/packages")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Otra comunidad no puede cambiarlo (404, sin cambios); portería y residentes no pueden usar el endpoint (403)")
    void onlyOwnCommunityAdmin() throws Exception {
        as(b.admin(), b, setStatus(guardRecord.getId(), "INACTIVE")).andExpect(status().isNotFound());
        // Aunque envíe la cabecera de la comunidad A, el administrador de B no tiene acceso a ella.
        int crossHeader = as(b.admin(), a, setStatus(guardRecord.getId(), "INACTIVE")).andReturn().getResponse().getStatus();
        assertThat(crossHeader).isIn(401, 403, 404);
        as(a.guard(), a, setStatus(guardRecord.getId(), "INACTIVE")).andExpect(status().isForbidden());
        as(a.owner(), a, setStatus(guardRecord.getId(), "INACTIVE")).andExpect(status().isForbidden());

        assertThat(staffRepository.findById(guardRecord.getId()).orElseThrow().getStatus()).isEqualTo(StaffMemberStatus.ACTIVE);
        assertThat(guardMembership()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("Un colaborador sin cuenta solo cambia de estado; un estado no válido o vacío responde 400")
    void withoutAccountAndValidation() throws Exception {
        StaffMember noAccount = staff(a, null);
        as(a.admin(), a, setStatus(noAccount.getId(), "INACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        as(a.admin(), a, setStatus(guardRecord.getId(), "SUSPENDIDO")).andExpect(status().isBadRequest());
        as(a.admin(), a, post("/api/v1/staff/{id}/status", guardRecord.getId()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(guardMembership()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("No toca la membresía de administración aunque el registro de personal apunte a esa cuenta")
    void neverTouchesAdminMembership() throws Exception {
        StaffMember adminRecord = staff(a, a.admin());
        as(a.admin(), a, setStatus(adminRecord.getId(), "INACTIVE")).andExpect(status().isOk());
        assertThat(membershipRepository.findByUserIdAndTenantId(a.admin().getId(), a.tenantId()).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.ACTIVE);
        as(a.admin(), a, get("/api/v1/staff")).andExpect(status().isOk());
    }
}
