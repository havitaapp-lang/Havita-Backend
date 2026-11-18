package com.havita.modules.staff;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.staff.api.StaffController;
import com.havita.modules.staff.api.dto.AttendanceResponse;
import com.havita.modules.staff.api.dto.ShiftResponse;
import com.havita.modules.staff.api.dto.StaffMemberResponse;
import com.havita.modules.staff.application.ShiftAndAttendanceService;
import com.havita.modules.staff.application.StaffService;
import com.havita.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class StaffControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantRepository tenantRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUpTenant() {
        com.havita.modules.organization.domain.Tenant activeTenant = new com.havita.modules.organization.domain.Tenant();
        activeTenant.setStatus(com.havita.modules.organization.domain.TenantStatus.ACTIVE);
        org.mockito.Mockito.when(tenantRepository.findById(org.mockito.ArgumentMatchers.any(java.util.UUID.class))).thenReturn(java.util.Optional.of(activeTenant));
    }


    @MockBean
    private JwtService jwtService;

    @MockBean
    private StaffService staffService;

    @MockBean
    private ShiftAndAttendanceService shiftAndAttendanceService;

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreateStaffMemberSuccessfully() throws Exception {
        UUID tenantId = UUID.randomUUID();
        StaffMemberResponse response = new StaffMemberResponse(
                UUID.randomUUID(), tenantId, null, "Mario", "Bros", "Mario Bros",
                "CC", "1012345678", "+573009998877", "mario@seguridad.com",
                "Guarda de seguridad", "Portería", "Empleado", "Seguridad Total Ltda",
                LocalDate.now(), com.havita.modules.staff.domain.StaffMemberStatus.ACTIVE, "Portería principal", null, null,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(staffService.create(any())).thenReturn(response);

        String body = """
                {
                    "firstName": "Mario",
                    "lastName": "Bros",
                    "documentType": "CC",
                    "documentNumber": "1012345678",
                    "position": "Guarda de seguridad",
                    "area": "Portería",
                    "contractType": "Empleado"
                }
                """;

        mockMvc.perform(post("/api/v1/staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fullName").value("Mario Bros"))
                .andExpect(jsonPath("$.data.position").value("Guarda de seguridad"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreateShiftSuccessfully() throws Exception {
        UUID staffId = UUID.randomUUID();
        ShiftResponse response = new ShiftResponse(
                UUID.randomUUID(), UUID.randomUUID(), staffId, "Mario Bros",
                "Guarda de seguridad", "Portería", LocalDate.now(),
                "Turno Día 12H", LocalTime.of(6, 0), LocalTime.of(18, 0),
                "Portería principal", "Normal", com.havita.modules.staff.domain.ShiftStatus.SCHEDULED, "Sin novedades",
                OffsetDateTime.now(), OffsetDateTime.now());

        when(shiftAndAttendanceService.createShift(any())).thenReturn(response);

        String body = String.format("""
                {
                    "staffMemberId": "%s",
                    "shiftDate": "%s",
                    "shiftName": "Turno Día 12H",
                    "startTime": "06:00:00",
                    "endTime": "18:00:00",
                    "gate": "Portería principal"
                }
                """, staffId, LocalDate.now());

        mockMvc.perform(post("/api/v1/staff/shifts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shiftName").value("Turno Día 12H"));
    }

    @Test
    @WithMockUser(roles = "SECURITY")
    void shouldCheckInSuccessfully() throws Exception {
        UUID staffId = UUID.randomUUID();
        AttendanceResponse response = new AttendanceResponse(
                UUID.randomUUID(), UUID.randomUUID(), staffId, "Mario Bros",
                "Guarda de seguridad", null, LocalDate.now(),
                LocalTime.of(6, 0), OffsetDateTime.now(),
                LocalTime.of(18, 0), null, "Portería principal",
                com.havita.modules.staff.domain.AttendanceRecordStatus.PRESENT, "Entrada a tiempo",
                OffsetDateTime.now(), OffsetDateTime.now());

        when(shiftAndAttendanceService.checkIn(any())).thenReturn(response);

        String body = String.format("""
                {
                    "staffMemberId": "%s",
                    "gate": "Portería principal",
                    "observations": "Entrada a tiempo"
                }
                """, staffId);

        mockMvc.perform(post("/api/v1/staff/attendance/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PRESENT"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentToCreateStaff() throws Exception {
        String body = """
                {
                    "firstName": "Unauthorized",
                    "lastName": "User",
                    "documentNumber": "99988877",
                    "position": "Guarda"
                }
                """;

        mockMvc.perform(post("/api/v1/staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/staff"))
                .andExpect(status().isUnauthorized());
    }
}




