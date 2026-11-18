package com.havita.modules.featureflag;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.featureflag.api.FeatureFlagController;
import com.havita.modules.featureflag.api.dto.FeatureFlagResponse;
import com.havita.modules.featureflag.application.FeatureFlagService;
import com.havita.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeatureFlagController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class FeatureFlagControllerTest {

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
    private FeatureFlagService featureFlagService;

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldListFeatureFlagsForCurrentTenant() throws Exception {
        FeatureFlagResponse flag = new FeatureFlagResponse(
                UUID.randomUUID(), "ASSEMBLIES_VOTING", "Asambleas y Votaciones",
                "Módulo de asambleas", "Comunidad", "PROFESSIONAL,ENTERPRISE",
                true, false, true);

        when(featureFlagService.findAllFlagsForCurrentTenant()).thenReturn(List.of(flag));

        mockMvc.perform(get("/api/v1/feature-flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].key").value("ASSEMBLIES_VOTING"))
                .andExpect(jsonPath("$.data[0].isEffectiveEnabledForTenant").value(true));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCheckSingleFeatureFlag() throws Exception {
        when(featureFlagService.isFeatureEnabled("GATEHOUSE_ACCESS")).thenReturn(true);

        mockMvc.perform(get("/api/v1/feature-flags/check/GATEHOUSE_ACCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_SUPER_ADMIN", "SCOPE_GLOBAL"}) // SEC-9: SUPER_ADMIN global
    void shouldCreateFeatureFlagSuccessfully() throws Exception {
        FeatureFlagResponse response = new FeatureFlagResponse(
                UUID.randomUUID(), "SMART_PARKING", "Parqueadero Inteligente",
                "Control IoT de talanqueras", "Seguridad", "ENTERPRISE",
                true, true, true);

        when(featureFlagService.createFlag(any())).thenReturn(response);

        String body = """
                {
                    "key": "SMART_PARKING",
                    "name": "Parqueadero Inteligente",
                    "description": "Control IoT de talanqueras",
                    "category": "Seguridad",
                    "enabledByPlan": "ENTERPRISE",
                    "isGlobalEnabled": true,
                    "isBeta": true
                }
                """;

        mockMvc.perform(post("/api/v1/feature-flags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.key").value("SMART_PARKING"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldDenyCommunityAdminToCreateFeatureFlag() throws Exception {
        String body = """
                {
                    "key": "ILLEGAL_FLAG",
                    "name": "Illegal",
                    "enabledByPlan": "STARTER"
                }
                """;

        mockMvc.perform(post("/api/v1/feature-flags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/feature-flags"))
                .andExpect(status().isUnauthorized());
    }
}




