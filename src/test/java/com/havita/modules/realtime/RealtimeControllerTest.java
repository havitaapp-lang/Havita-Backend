package com.havita.modules.realtime;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.realtime.api.RealtimeController;
import com.havita.modules.realtime.api.dto.NotificationResponse;
import com.havita.modules.realtime.api.dto.PanicAlertResponse;
import com.havita.modules.realtime.api.dto.ResolvePanicAlertRequest;
import com.havita.modules.realtime.api.dto.SendNotificationRequest;
import com.havita.modules.realtime.api.dto.TriggerPanicAlertRequest;
import com.havita.modules.realtime.application.RealtimeService;
import com.havita.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RealtimeController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class RealtimeControllerTest {

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


    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private RealtimeService realtimeService;

    /** Fase 2 / E1: la unidad del panico la da el resolvedor central (su comportamiento real: ResidentUnitResolutionIT). */
    @MockBean
    private com.havita.security.ResidentUnitResolver unitResolver;

    /** SEC-3: la regla de unidad de @PreAuthorize se simula; su comportamiento real lo cubren UnitScoped*IT. */
    @MockBean(name = "accessValidator")
    private com.havita.security.AccessValidator accessValidator;

    @Test
    @WithMockUser(roles = "RESIDENT", username = "00000000-0000-0000-0000-000000000001")
    void shouldTriggerPanicAlert() throws Exception {
        UUID propertyId = UUID.randomUUID();
        com.havita.modules.realtime.api.dto.PanicAlertRequest request = new com.havita.modules.realtime.api.dto.PanicAlertRequest(
                com.havita.modules.realtime.domain.AlertType.MEDICAL, "Emergencia médica en piso 3"
        );

        PanicAlertResponse response = new PanicAlertResponse(
                UUID.randomUUID(),
                "MEDICAL",
                "Emergencia Médica",
                "Emergencia médica en piso 3",
                com.havita.modules.realtime.domain.PanicAlertStatus.ACTIVE,
                UUID.randomUUID(),
                "Juan Perez",
                propertyId,
                "Torre A",
                "302",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        UUID residentId = UUID.randomUUID();
        when(unitResolver.resolveCurrent()).thenReturn(
                new com.havita.security.ResidentUnitResolver.ResolvedUnit(propertyId, residentId, null));
        when(realtimeService.triggerPanicAlert(
                eq(com.havita.modules.realtime.domain.AlertType.MEDICAL),
                eq("Emergencia médica en piso 3"),
                any(UUID.class),
                eq(propertyId),
                eq(residentId))).thenReturn(response);

        mockMvc.perform(post("/api/v1/realtime/panic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.alertType").value("MEDICAL"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(roles = "SECURITY")
    void shouldGetActivePanicAlerts() throws Exception {
        PanicAlertResponse response = new PanicAlertResponse(
                UUID.randomUUID(),
                "FIRE",
                "Incendio",
                "Humo en pasillo",
                com.havita.modules.realtime.domain.PanicAlertStatus.ACTIVE,
                UUID.randomUUID(),
                "Juan Perez",
                UUID.randomUUID(),
                "Torre B",
                "101",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        when(realtimeService.getActiveAlerts()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/realtime/panic/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].alertType").value("FIRE"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN", username = "00000000-0000-0000-0000-000000000001")
    void shouldResolvePanicAlert() throws Exception {
        UUID alertId = UUID.randomUUID();
        ResolvePanicAlertRequest request = new ResolvePanicAlertRequest(com.havita.modules.realtime.domain.PanicAlertStatus.RESOLVED, "Atendido por brigada interna");

        PanicAlertResponse response = new PanicAlertResponse(
                alertId,
                "FIRE",
                "Incendio",
                "Humo en pasillo",
                com.havita.modules.realtime.domain.PanicAlertStatus.RESOLVED,
                UUID.randomUUID(),
                "Juan Perez",
                UUID.randomUUID(),
                "Torre B",
                "101",
                null,
                null,
                null,
                UUID.randomUUID(),
                "Admin User",
                OffsetDateTime.now(),
                "Atendido por brigada interna",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        when(realtimeService.resolveAlert(
                eq(alertId),
                any(UUID.class),
                eq(com.havita.modules.realtime.domain.PanicAlertStatus.RESOLVED),
                eq("Atendido por brigada interna")))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/realtime/panic/" + alertId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldSendNotification() throws Exception {
        SendNotificationRequest request = new SendNotificationRequest(
                null, UUID.randomUUID(), "Visita en Portería", "Su visita ha llegado", "VISIT_ARRIVAL", null
        );

        NotificationResponse response = new NotificationResponse(
                UUID.randomUUID(), UUID.randomUUID(), null, request.propertyId(),
                "Visita en Portería", "Su visita ha llegado", "VISIT_ARRIVAL", null,
                false, null, OffsetDateTime.now()
        );

        when(realtimeService.sendNotification(any(SendNotificationRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/realtime/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("VISIT_ARRIVAL"));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/realtime/panic/active"))
                .andExpect(status().isUnauthorized());
    }
}




