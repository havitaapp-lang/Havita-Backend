package com.havita.security;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/v1/staff/{id}} y {@code PUT /api/v1/staff/{id}/access}: antes la pantalla solo guardaba estos cambios
 * en el navegador (incluida la contrasena). Se comprueba que se guardan en el servidor sin escalar privilegios, sin
 * exponer la contrasena, sin tomar el control de una cuenta que tambien usa otra comunidad y sin acceso entre
 * comunidades. Datos sinteticos.
 */
class StaffAccessManagementIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private StaffMemberRepository staffRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    private StaffMember staff(Side side, User user) {
        StaffMember s = StaffMember.builder().firstName("Colaborador").lastName("Sintetico").documentType("CC")
                .documentNumber("DOC" + UUID.randomUUID().toString().substring(0, 8)).position("Guarda").area("Portería")
                .contractType("Empleado").startDate(LocalDate.now()).status(StaffMemberStatus.ACTIVE).user(user).build();
        s.setTenantId(side.tenantId());
        return staffRepository.save(s);
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private MockHttpServletRequestBuilder access(UUID id, String json) {
        return put("/api/v1/staff/{id}/access", id).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MembershipStatus membershipOf(User u, Side side) {
        return membershipRepository.findByUserIdAndTenantId(u.getId(), side.tenantId()).map(Membership::getStatus).orElse(null);
    }

    @Test
    @DisplayName("Crear acceso: cuenta nueva con rol SECURITY que entra de verdad; la contraseña nunca se devuelve")
    void createsAccount() throws Exception {
        StaffMember s = staff(a, null);
        String email = "staff-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"loginEmail\":\"" + email + "\",\"password\":\"Clave-Sintetica-1\",\"systemRole\":\"SECURITY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemAccess.enabled").value(true))
                .andExpect(jsonPath("$.data.systemAccess.role").value("SECURITY"))
                .andExpect(jsonPath("$.data.systemAccess.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.systemAccess.loginEmail").value(email))
                .andExpect(jsonPath("$.data.systemAccess.password").doesNotExist());
        User created = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(passwordEncoder.matches("Clave-Sintetica-1", created.getPasswordHash())).isTrue();
        as(created, a, get("/api/v1/access/packages")).andExpect(status().isOk());

        // El listado también trae el acceso, sin contraseña.
        as(a.admin(), a, get("/api/v1/staff")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id=='" + s.getId() + "')].systemAccess.role").value("SECURITY"));
    }

    @Test
    @DisplayName("Crear acceso con el mismo correo de contacto del colaborador (no es un duplicado); el de otra persona sí (409)")
    void createsAccountWithOwnContactEmail() throws Exception {
        String email = "contacto-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        StaffMember s = staff(a, null);
        s.setEmail(email);
        staffRepository.save(s);
        as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"password\":\"Clave-Sintetica-1\",\"systemRole\":\"STAFF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemAccess.loginEmail").value(email));

        StaffMember other = staff(a, null);
        as(a.admin(), a, access(other.getId(), "{\"enabled\":true,\"loginEmail\":\"" + email + "\",\"password\":\"Clave-Sintetica-1\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Crear acceso sin contraseña (409) o con una corta (400): no se crea nada")
    void createRequiresPassword() throws Exception {
        StaffMember s = staff(a, null);
        String email = "staff-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"loginEmail\":\"" + email + "\"}")).andExpect(status().isConflict());
        as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"loginEmail\":\"" + email + "\",\"password\":\"corta\"}"))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
    }

    @Test
    @DisplayName("No se puede asignar administración ni roles globales (403, sin cambios)")
    void noPrivilegeEscalation() throws Exception {
        StaffMember s = staff(a, a.guard());
        for (String role : new String[] {"COMMUNITY_ADMIN", "SUPER_ADMIN", "ADMINISTRADOR"}) {
            as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"systemRole\":\"" + role + "\"}")).andExpect(status().isForbidden());
        }
        Membership m = membershipRepository.findByUserIdAndTenantId(a.guard().getId(), a.tenantId()).orElseThrow();
        assertThat(m.getRole()).isEqualTo(RoleType.SECURITY);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("Desactivar el acceso le quita la entrada; reactivarlo con otro rol (STAFF) se la devuelve con ese rol")
    void disableAndRoleChange() throws Exception {
        StaffMember s = staff(a, a.guard());
        as(a.admin(), a, access(s.getId(), "{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemAccess.status").value("INACTIVE"));
        assertThat(membershipOf(a.guard(), a)).isEqualTo(MembershipStatus.INACTIVE);
        assertThat(as(a.guard(), a, get("/api/v1/access/packages")).andReturn().getResponse().getStatus()).isIn(401, 403);

        as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"systemRole\":\"STAFF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemAccess.role").value("STAFF"))
                .andExpect(jsonPath("$.data.systemAccess.status").value("ACTIVE"));
        assertThat(membershipRepository.findByUserIdAndTenantId(a.guard().getId(), a.tenantId()).orElseThrow().getRole())
                .isEqualTo(RoleType.STAFF);
    }

    @Test
    @DisplayName("Cambiar la contraseña de una cuenta exclusiva de la comunidad: se guarda cifrada")
    void changesPasswordOfExclusiveAccount() throws Exception {
        StaffMember s = staff(a, a.guard());
        as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"systemRole\":\"SECURITY\",\"password\":\"Nueva-Clave-123\"}"))
                .andExpect(status().isOk());
        User u = userRepository.findById(a.guard().getId()).orElseThrow();
        assertThat(passwordEncoder.matches("Nueva-Clave-123", u.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Cuenta que también tiene acceso en otra comunidad: ni correo ni contraseña (409, sin cambios)")
    void sharedAccountCredentialsAreProtected() throws Exception {
        membershipRepository.save(Membership.builder().user(a.guard()).tenantId(b.tenantId()).role(RoleType.SECURITY)
                .status(MembershipStatus.ACTIVE).build());
        StaffMember s = staff(a, a.guard());
        String hashBefore = userRepository.findById(a.guard().getId()).orElseThrow().getPasswordHash();
        String emailBefore = a.guard().getEmail();

        as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"systemRole\":\"SECURITY\",\"password\":\"Toma-Control-1\"}"))
                .andExpect(status().isConflict());
        as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"systemRole\":\"SECURITY\",\"loginEmail\":\"otro-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test\"}"))
                .andExpect(status().isConflict());
        User u = userRepository.findById(a.guard().getId()).orElseThrow();
        assertThat(u.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(u.getEmail()).isEqualTo(emailBefore);
        // La ficha lo indica, y su acceso en la otra comunidad sigue intacto.
        as(a.admin(), a, get("/api/v1/staff/{id}", s.getId())).andExpect(jsonPath("$.data.systemAccess.shared").value(true));
        assertThat(membershipOf(a.guard(), b)).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("Una cuenta de administración no se gestiona desde Personal (409, membresía intacta)")
    void adminAccountIsNotManageable() throws Exception {
        StaffMember s = staff(a, a.admin());
        as(a.admin(), a, access(s.getId(), "{\"enabled\":true,\"systemRole\":\"STAFF\"}")).andExpect(status().isConflict());
        Membership m = membershipRepository.findByUserIdAndTenantId(a.admin().getId(), a.tenantId()).orElseThrow();
        assertThat(m.getRole()).isEqualTo(RoleType.COMMUNITY_ADMIN);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("Otra comunidad 404; portería y residentes 403 (acceso y datos), sin cambios")
    void isolation() throws Exception {
        StaffMember s = staff(a, a.guard());
        as(b.admin(), b, access(s.getId(), "{\"enabled\":false}")).andExpect(status().isNotFound());
        as(a.guard(), a, access(s.getId(), "{\"enabled\":false}")).andExpect(status().isForbidden());
        as(a.owner(), a, access(s.getId(), "{\"enabled\":false}")).andExpect(status().isForbidden());
        String body = "{\"firstName\":\"Intruso\",\"lastName\":\"X\",\"documentNumber\":\"X1\",\"position\":\"X\"}";
        as(b.admin(), b, put("/api/v1/staff/{id}", s.getId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        as(a.owner(), a, put("/api/v1/staff/{id}", s.getId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(membershipOf(a.guard(), a)).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(staffRepository.findById(s.getId()).orElseThrow().getFirstName()).isEqualTo("Colaborador");
    }

    @Test
    @DisplayName("Editar datos: se guardan; un documento repetido en la comunidad responde 409")
    void updatesData() throws Exception {
        StaffMember s = staff(a, null);
        StaffMember other = staff(a, null);
        as(a.admin(), a, put("/api/v1/staff/{id}", s.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Ana\",\"lastName\":\"Editada\",\"documentNumber\":\"" + s.getDocumentNumber()
                        + "\",\"position\":\"Supervisora\",\"phone\":\"3001112233\",\"assignedGate\":\"Portería secundaria\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Ana Editada"))
                .andExpect(jsonPath("$.data.position").value("Supervisora"))
                .andExpect(jsonPath("$.data.assignedGate").value("Portería secundaria"));
        as(a.admin(), a, put("/api/v1/staff/{id}", s.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Ana\",\"lastName\":\"Editada\",\"documentNumber\":\"" + other.getDocumentNumber()
                        + "\",\"position\":\"Supervisora\"}"))
                .andExpect(status().isConflict());
    }
}
