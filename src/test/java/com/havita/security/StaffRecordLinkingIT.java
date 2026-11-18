package com.havita.security;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.staff.domain.StaffMember;
import com.havita.modules.staff.domain.StaffMemberStatus;
import com.havita.modules.staff.infrastructure.AttendanceRecordRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Auditoria UNVERIFIED (2026-11-05): ShiftAndAttendanceService.resolveStaffMemberId recibia ids del cliente y, si eran
 * de una cuenta, vinculaba (escribia) a esa cuenta la ficha de personal de la comunidad con el mismo correo o el mismo
 * NOMBRE. Una cuenta STAFF podia apropiarse de la ficha de otra persona (y marcar o ver su asistencia y turnos), y se
 * podian vincular cuentas de otras comunidades. Ahora la resolucion es de solo lectura: id de ficha de la comunidad o
 * cuenta ya vinculada en ella.
 */
class StaffRecordLinkingIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private StaffMemberRepository staffMemberRepository;
    @Autowired private AttendanceRecordRepository attendanceRecordRepository;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    private StaffMember unlinkedStaff(UUID tenantId, String first, String last, String email) {
        StaffMember s = StaffMember.builder().firstName(first).lastName(last).documentType("CC")
                .documentNumber("DOC" + UUID.randomUUID().toString().substring(0, 8)).email(email).position("Aseo")
                .area("Aseo").contractType("Empleado").startDate(LocalDate.now()).status(StaffMemberStatus.ACTIVE).build();
        s.setTenantId(tenantId);
        return staffMemberRepository.save(s);
    }

    private User member(UUID tenantId, String fullName, String email, RoleType role) {
        User u = userRepository.save(User.builder().email(email).passwordHash("{noop}x").fullName(fullName).phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(tenantId).role(role).status(MembershipStatus.ACTIVE).build());
        return u;
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** user_id de la ficha leido directamente (sin proxies de Hibernate). */
    private String linkedUserId(UUID staffId) {
        return jdbc.queryForObject("select cast(user_id as varchar) from staff_members where id = ?", String.class, staffId);
    }

    private int status(User u, UUID tenant, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(u)).header("X-Tenant-ID", tenant.toString()))
                .andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder checkIn(UUID staffMemberId) {
        return post("/api/v1/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"staffMemberId\":\"" + staffMemberId + "\",\"observations\":\"prueba\"}");
    }

    @Test
    @DisplayName("una cuenta STAFF con el mismo nombre que una ficha ajena no se apropia de ella (ni marca ni ve su asistencia)")
    void sameNameDoesNotLinkStaffRecord() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        StaffMember victim = unlinkedStaff(a.tenantId(), "Ana", "Objetivo " + suffix, "ana-" + suffix + "@example.test");
        User attacker = member(a.tenantId(), "Ana Objetivo " + suffix, "atacante-" + suffix + "@example.test", RoleType.STAFF);

        int checkIn = status(attacker, a.tenantId(), checkIn(attacker.getId()));
        int mine = status(attacker, a.tenantId(), get("/api/v1/staff/me"));

        assertThat(linkedUserId(victim.getId())).as("la ficha de otra persona no queda vinculada a la cuenta atacante").isNull();
        assertThat(attendanceRecordRepository.findByTenantIdAndStaffMemberId(a.tenantId(), victim.getId()))
                .as("no se registra asistencia a nombre de la victima").isEmpty();
        assertThat(checkIn).isIn(400, 404, 409);
        assertThat(mine).isEqualTo(404);
    }

    @Test
    @DisplayName("la administracion de A no vincula cuentas de B a fichas de A por correo, ni consultando ni marcando")
    void foreignAccountIsNotLinkedByEmail() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String shared = "compartido-" + suffix + "@example.test";
        StaffMember record = unlinkedStaff(a.tenantId(), "Luis", "Ficha " + suffix, shared);
        User foreign = member(b.tenantId(), "Cuenta de B " + suffix, shared, RoleType.STAFF);

        status(a.admin(), a.tenantId(), get("/api/v1/staff/attendance/by-staff/{id}", foreign.getId()));
        status(a.admin(), a.tenantId(), get("/api/v1/staff/shifts/by-staff/{id}", foreign.getId()));
        int checkIn = status(a.admin(), a.tenantId(), checkIn(foreign.getId()));

        assertThat(linkedUserId(record.getId())).as("ficha de A sin vincular a la cuenta de B").isNull();
        assertThat(checkIn).isIn(400, 404, 409);
    }

    @Test
    @DisplayName("controles: la ficha vinculada de verdad sigue funcionando por id de ficha y por la propia cuenta")
    void linkedStaffKeepsWorking() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        User worker = member(a.tenantId(), "Trabajador " + suffix, "trabajador-" + suffix + "@example.test", RoleType.STAFF);
        StaffMember record = unlinkedStaff(a.tenantId(), "Trabajador", suffix, "ficha-" + suffix + "@example.test");
        record.setUser(worker);
        staffMemberRepository.save(record);

        assertThat(status(worker, a.tenantId(), get("/api/v1/staff/me"))).isEqualTo(200);
        assertThat(status(a.guard(), a.tenantId(), checkIn(record.getId()))).isIn(200, 201);
        assertThat(status(worker, a.tenantId(), get("/api/v1/staff/attendance/my-attendance"))).isEqualTo(200);
        assertThat(attendanceRecordRepository.findByTenantIdAndStaffMemberId(a.tenantId(), record.getId())).hasSize(1);
        // Otra comunidad no ve la ficha.
        assertThat(status(b.admin(), b.tenantId(), get("/api/v1/staff/attendance/by-staff/{id}", record.getId())))
                .isIn(200, 404);
        assertThat(status(b.admin(), b.tenantId(), checkIn(record.getId()))).isIn(400, 404, 409);
    }
}
