package com.havita.modules.pqrs;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.pqrs.api.PqrsController;
import com.havita.modules.pqrs.api.dto.PqrsResponse;
import com.havita.modules.pqrs.application.PqrsService;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PqrsController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class PqrsControllerTest {

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
    private PqrsService pqrsService;

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldCreatePqrsSuccessfully() throws Exception {
        UUID propertyId = UUID.randomUUID();
        PqrsResponse response = new PqrsResponse(
                UUID.randomUUID(), UUID.randomUUID(), propertyId, "101", "Apto 101",
                null, null, "Plomería", "Fuga en tubería principal",
                "Se observa humedad en la pared del baño", com.havita.modules.pqrs.domain.PqrsRequestStatus.NEW, "Alta",
                null, null, null, null, null, List.of(),
                OffsetDateTime.now(), OffsetDateTime.now());

        when(pqrsService.create(any(), any())).thenReturn(response);

        String body = String.format("""
                {
                    "propertyId": "%s",
                    "category": "Plomería",
                    "title": "Fuga en tubería principal",
                    "description": "Se observa humedad en la pared del baño",
                    "priority": "Alta"
                }
                """, propertyId);

        mockMvc.perform(post("/api/v1/pqrs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("Fuga en tubería principal"))
                .andExpect(jsonPath("$.data.category").value("Plomería"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldResolvePqrsSuccessfully() throws Exception {
        UUID pqrsId = UUID.randomUUID();
        PqrsResponse response = new PqrsResponse(
                pqrsId, UUID.randomUUID(), UUID.randomUUID(), "101", "Apto 101",
                null, null, "Plomería", "Fuga en tubería", "Descripción",
                com.havita.modules.pqrs.domain.PqrsRequestStatus.RESOLVED, "Alta", "Se cambió el empaque de la válvula",
                null, null, OffsetDateTime.now(), null, List.of(),
                OffsetDateTime.now(), OffsetDateTime.now());

        when(pqrsService.resolve(any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/pqrs/" + pqrsId + "/resolve")
                        .param("responseNotes", "Se cambió el empaque de la válvula"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.responseNotes").value("Se cambió el empaque de la válvula"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentToAssignTechnician() throws Exception {
        UUID pqrsId = UUID.randomUUID();
        UUID technicianId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/pqrs/" + pqrsId + "/assign/" + technicianId))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/pqrs"))
                .andExpect(status().isUnauthorized());
    }
}




