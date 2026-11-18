package com.havita.modules.organization;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.organization.api.TenantController;
import com.havita.modules.organization.api.dto.TenantResponse;
import com.havita.modules.organization.application.TenantService;
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

@WebMvcTest(TenantController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class TenantControllerTest {

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
    private TenantService tenantService;

    @Test
    @WithMockUser(authorities = {"ROLE_SUPER_ADMIN", "SCOPE_GLOBAL"}) // SEC-9: SUPER_ADMIN global
    void shouldCreateTenantWithValidData() throws Exception {
        TenantResponse response = new TenantResponse(
                UUID.randomUUID(), null, null, "Conjunto El Limonar",
                "limonar", "limonar", "800111222-3", "Cali", "Calle 5 #25-30",
                "+573109876543", "admin@limonar.com", com.havita.modules.organization.domain.TenantStatus.ACTIVE,
                5120, 0, null, "#3B82F6", "#1E40AF",
                null, "Starter", null, null,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(tenantService.create(any())).thenReturn(response);

        String body = """
                {
                    "name": "Conjunto El Limonar",
                    "slug": "limonar",
                    "subdomain": "limonar",
                    "nit": "800111222-3",
                    "city": "Cali",
                    "address": "Calle 5 #25-30",
                    "phone": "+573109876543",
                    "contactEmail": "admin@limonar.com"
                }
                """;

        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Conjunto El Limonar"))
                .andExpect(jsonPath("$.data.slug").value("limonar"));
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void shouldRejectTenantWithInvalidSlug() throws Exception {
        String body = """
                {
                    "name": "Test Tenant",
                    "slug": "Invalid Slug!",
                    "subdomain": "valid-sub"
                }
                """;

        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentAccessToCreateTenant() throws Exception {
        String body = """
                {
                    "name": "Unauthorized",
                    "slug": "unauthorized",
                    "subdomain": "unauthorized"
                }
                """;

        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldAllowCommunityAdminToReadTenantById() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantResponse response = new TenantResponse(
                tenantId, null, null, "Conjunto Test",
                "test", "test", null, "Bogota", null,
                null, null, com.havita.modules.organization.domain.TenantStatus.ACTIVE,
                5120, 100, null, "#3B82F6", "#1E40AF",
                null, "Starter", null, null,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(tenantService.findById(tenantId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/tenants/" + tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Conjunto Test"));
    }

    @Test
    void shouldDenyUnauthenticatedAccessToTenants() throws Exception {
        mockMvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isUnauthorized());
    }
}




