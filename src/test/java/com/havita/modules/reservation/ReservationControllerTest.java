package com.havita.modules.reservation;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.reservation.api.ReservationController;
import com.havita.modules.reservation.api.dto.ReservationResponse;
import com.havita.modules.reservation.application.ReservationService;
import com.havita.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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

@WebMvcTest(ReservationController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class ReservationControllerTest {

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
    private ReservationService reservationService;

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldCreateReservationSuccessfully() throws Exception {
        UUID zoneId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        ReservationResponse response = new ReservationResponse(
                UUID.randomUUID(), UUID.randomUUID(), zoneId, "Salón Social",
                propertyId, "101", "Apto 101", null, null, null,
                LocalDate.now().plusDays(3), LocalTime.of(14, 0), LocalTime.of(18, 0),
                15, new BigDecimal("80000.00"), com.havita.modules.reservation.domain.ReservationStatus.PENDING,
                "Cumpleaños familiar", null,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(reservationService.create(any(), any())).thenReturn(response);

        String body = String.format("""
                {
                    "commonZoneId": "%s",
                    "propertyId": "%s",
                    "reservationDate": "%s",
                    "startTime": "14:00:00",
                    "endTime": "18:00:00",
                    "guestsCount": 15,
                    "observations": "Cumpleaños familiar"
                }
                """, zoneId, propertyId, LocalDate.now().plusDays(3));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.commonZoneName").value("Salón Social"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldConfirmReservationSuccessfully() throws Exception {
        UUID reservationId = UUID.randomUUID();
        ReservationResponse response = new ReservationResponse(
                reservationId, UUID.randomUUID(), UUID.randomUUID(), "Salón Social",
                UUID.randomUUID(), "101", "Apto 101", null, null, null,
                LocalDate.now().plusDays(3), LocalTime.of(14, 0), LocalTime.of(18, 0),
                15, new BigDecimal("80000.00"), com.havita.modules.reservation.domain.ReservationStatus.CONFIRMED,
                "Cumpleaños familiar", "Aprobado por administración",
                OffsetDateTime.now(), OffsetDateTime.now());

        when(reservationService.confirm(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm")
                        .param("notes", "Aprobado por administración"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentToConfirmReservation() throws Exception {
        UUID reservationId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/reservations"))
                .andExpect(status().isUnauthorized());
    }
}




