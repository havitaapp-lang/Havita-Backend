package com.havita.modules.audit;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.audit.api.AuditController;
import com.havita.modules.audit.api.dto.AuditEventResponse;
import com.havita.modules.audit.application.AuditService;
import com.havita.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class AuditControllerTest {

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
    private AuditService auditService;

    @MockBean
    private com.havita.modules.identity.infrastructure.UserRepository userRepository;

    private static final String SUPER_ADMIN_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void shouldFindAllAuditLogsSuccessfully() throws Exception {
        AuditEventResponse event = new AuditEventResponse(
                UUID.randomUUID(), OffsetDateTime.now(), "Admin General",
                "admin@havita.com", "SUPER_ADMIN", "SUSPEND_TENANT",
                "Tenant/123", UUID.randomUUID(), "Limonar",
                "SUCCESS", "Mora en pago", "192.168.1.1",
                OffsetDateTime.now());

        when(auditService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/audit/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].action").value("SUSPEND_TENANT"))
                .andExpect(jsonPath("$.data.content[0].actorName").value("Admin General"));
    }

    /** SEC-9: registrar eventos es interno del panel global; la administracion de comunidad ya no puede (403). */
    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldDenyCommunityAdminToRecordAuditEvents() throws Exception {
        mockMvc.perform(post("/api/v1/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorName\":\"Carlos Admin\",\"actorEmail\":\"admin@limonar.com\",\"actorRole\":\"SUPER_ADMIN\",\"action\":\"UPDATE_GATE_SCHEDULE\",\"resource\":\"Gatehouse/North\",\"result\":\"SUCCESS\",\"details\":\"-\",\"ipAddress\":\"10.0.0.1\"}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(auditService);
    }

    /** SEC-9: el SUPER_ADMIN global registra eventos; actor, rol e IP los fija el servidor, no el cuerpo. */
    @Test
    @WithMockUser(username = SUPER_ADMIN_ID, authorities = {"ROLE_SUPER_ADMIN", "SCOPE_GLOBAL"})
    void shouldRecordAuditEventSuccessfully() throws Exception {
        com.havita.modules.identity.domain.User actor = com.havita.modules.identity.domain.User.builder()
                .email("superadmin@example.test").fullName("Super Admin Real").passwordHash("x").build();
        when(userRepository.findById(UUID.fromString(SUPER_ADMIN_ID))).thenReturn(java.util.Optional.of(actor));
        AuditEventResponse response = new AuditEventResponse(
                UUID.randomUUID(), OffsetDateTime.now(), "Super Admin Real",
                "superadmin@example.test", "SYSTEM", "UPDATE_GATE_SCHEDULE",
                "Gatehouse/North", UUID.randomUUID(), "Limonar",
                "SUCCESS", "Cambio de turno guardia", "127.0.0.1",
                OffsetDateTime.now());

        org.mockito.ArgumentCaptor<com.havita.modules.audit.api.dto.RecordAuditEventRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.havita.modules.audit.api.dto.RecordAuditEventRequest.class);
        when(auditService.recordEvent(captor.capture())).thenReturn(response);

        String body = """
                {
                    "actorName": "Carlos Admin",
                    "actorEmail": "admin@limonar.com",
                    "actorRole": "COMMUNITY_ADMIN",
                    "action": "UPDATE_GATE_SCHEDULE",
                    "resource": "Gatehouse/North",
                    "result": "SUCCESS",
                    "details": "Cambio de turno guardia",
                    "ipAddress": "190.24.11.2"
                }
                """;

        mockMvc.perform(post("/api/v1/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.action").value("UPDATE_GATE_SCHEDULE"));

        var recorded = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("Super Admin Real", recorded.actorName());
        org.junit.jupiter.api.Assertions.assertEquals("superadmin@example.test", recorded.actorEmail());
        org.junit.jupiter.api.Assertions.assertNotEquals("COMMUNITY_ADMIN", recorded.actorRole());
        org.junit.jupiter.api.Assertions.assertNotEquals("190.24.11.2", recorded.ipAddress());
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentToQueryFullAuditLogs() throws Exception {
        mockMvc.perform(get("/api/v1/audit/logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/audit/logs"))
                .andExpect(status().isUnauthorized());
    }
}




