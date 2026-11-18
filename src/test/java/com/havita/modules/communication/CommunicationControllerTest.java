package com.havita.modules.communication;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.communication.api.CommunicationController;
import com.havita.modules.communication.api.dto.CommunicationResponse;
import com.havita.modules.communication.api.dto.CommunityDocumentResponse;
import com.havita.modules.communication.application.CommunicationService;
import com.havita.modules.communication.application.CommunityDocumentService;
import com.havita.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommunicationController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class CommunicationControllerTest {

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
    private CommunicationService communicationService;

    @MockBean
    private CommunityDocumentService communityDocumentService;

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreateCommunicationSuccessfully() throws Exception {
        CommunicationResponse response = new CommunicationResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Administración",
                "Mantenimiento de bombas de agua", "Se suspenderá el servicio el jueves de 8am a 12pm",
                "Todos", "Mantenimiento", com.havita.modules.communication.domain.CommunicationStatus.PUBLISHED,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());

        when(communicationService.create(any(), any())).thenReturn(response);

        String body = """
                {
                    "title": "Mantenimiento de bombas de agua",
                    "content": "Se suspenderá el servicio el jueves de 8am a 12pm",
                    "audience": "Todos",
                    "category": "Mantenimiento"
                }
                """;

        mockMvc.perform(post("/api/v1/communications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("Mantenimiento de bombas de agua"))
                .andExpect(jsonPath("$.data.audience").value("Todos"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreateDocumentSuccessfully() throws Exception {
        CommunityDocumentResponse response = new CommunityDocumentResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Reglamento de Propiedad Horizontal 2026", "Reglamento",
                "PDF", "3.5 MB", "https://cdn.havita.com/docs/reglamento.pdf",
                "tenants/test/docs/reglamento.pdf",
                "Versión actualizada aprobada en asamblea", 0,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(communityDocumentService.create(any(), any())).thenReturn(response);

        String body = """
                {
                    "title": "Reglamento de Propiedad Horizontal 2026",
                    "category": "Reglamento",
                    "fileType": "PDF",
                    "fileSize": "3.5 MB",
                    "fileUrl": "https://cdn.havita.com/docs/reglamento.pdf",
                    "description": "Versión actualizada aprobada en asamblea"
                }
                """;

        mockMvc.perform(post("/api/v1/communications/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("Reglamento de Propiedad Horizontal 2026"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentToCreateCommunication() throws Exception {
        String body = """
                {
                    "title": "Aviso ilegal",
                    "content": "Contenido no autorizado"
                }
                """;

        mockMvc.perform(post("/api/v1/communications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/communications"))
                .andExpect(status().isUnauthorized());
    }
}




