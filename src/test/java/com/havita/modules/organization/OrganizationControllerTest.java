package com.havita.modules.organization;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.organization.api.OrganizationController;
import com.havita.modules.organization.api.dto.OrganizationResponse;
import com.havita.modules.organization.application.OrganizationService;
import com.havita.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@WebMvcTest(OrganizationController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class OrganizationControllerTest {

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
    private OrganizationService organizationService;

    @Test
    @WithMockUser(authorities = {"ROLE_SUPER_ADMIN", "SCOPE_GLOBAL"}) // SEC-9: SUPER_ADMIN global
    void shouldCreateOrganizationWithValidData() throws Exception {
        OrganizationResponse response = new OrganizationResponse(
                UUID.randomUUID(), "Grupo Admin XYZ", "900123456-1",
                "admin@xyz.com", "+573001234567", "Bogota", "Calle 100 #15-20",
                null, com.havita.modules.organization.domain.OrganizationStatus.ACTIVE, 0, OffsetDateTime.now(), OffsetDateTime.now());

        when(organizationService.create(any())).thenReturn(response);

        String body = """
                {
                    "name": "Grupo Admin XYZ",
                    "nit": "900123456-1",
                    "email": "admin@xyz.com",
                    "phone": "+573001234567",
                    "city": "Bogota",
                    "address": "Calle 100 #15-20"
                }
                """;

        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Grupo Admin XYZ"))
                .andExpect(jsonPath("$.data.nit").value("900123456-1"));
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void shouldRejectOrganizationWithMissingRequiredFields() throws Exception {
        String body = """
                {
                    "name": "",
                    "nit": ""
                }
                """;

        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldDenyAccessToNonSuperAdminForCreate() throws Exception {
        String body = """
                {
                    "name": "Unauthorized Org",
                    "nit": "111222333-4"
                }
                """;

        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyAccessToUnauthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/v1/organizations"))
                .andExpect(status().isUnauthorized());
    }
}




