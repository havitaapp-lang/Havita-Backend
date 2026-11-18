package com.havita.modules.subscription;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.subscription.api.SubscriptionController;
import com.havita.modules.subscription.api.dto.PlanResponse;
import com.havita.modules.subscription.api.dto.SubscriptionResponse;
import com.havita.modules.subscription.application.SubscriptionService;
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
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class SubscriptionControllerTest {

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
    private SubscriptionService subscriptionService;

    @Test
    @WithMockUser(authorities = {"ROLE_SUPER_ADMIN", "SCOPE_GLOBAL"}) // SEC-9: SUPER_ADMIN global
    void shouldCreatePlanSuccessfully() throws Exception {
        PlanResponse response = new PlanResponse(
                UUID.randomUUID(), "PROFESSIONAL", "Plan Profesional",
                "Ideal para copropiedades medianas", new BigDecimal("350000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, "COP", "Mensual", 250, 21474836480L,
                5, 10, 5, 100, 100, true,
                "Acceso, PQRS, Asambleas", com.havita.modules.subscription.domain.SaaSPlanStatus.ACTIVE,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(subscriptionService.createPlan(any())).thenReturn(response);

        String body = """
                {
                    "tier": "PROFESSIONAL",
                    "name": "Plan Profesional",
                    "tagline": "Ideal para copropiedades medianas",
                    "grossPricePerProperty": 350000.00,
                    "maxProperties": 250,
                    "maxAdminUsers": 5,
                    "maxStorageBytes": 21474836480,
                    "popular": true
                }
                """;

        mockMvc.perform(post("/api/v1/saas/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Plan Profesional"))
                .andExpect(jsonPath("$.data.tier").value("PROFESSIONAL"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldGetCurrentSubscriptionSuccessfully() throws Exception {
        UUID tenantId = UUID.randomUUID();
        SubscriptionResponse response = new SubscriptionResponse(
                UUID.randomUUID(), tenantId, "Conjunto El Limonar",
                UUID.randomUUID(), "Plan Profesional", "PROFESSIONAL",
                new BigDecimal("350000.00"), "MONTHLY", com.havita.modules.subscription.domain.SaaSSubscriptionStatus.ACTIVE,
                LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(11),
                "PSE", true, OffsetDateTime.now(), OffsetDateTime.now());

        when(subscriptionService.getCurrentSubscription()).thenReturn(response);

        mockMvc.perform(get("/api/v1/saas/subscription/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.planName").value("Plan Profesional"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldDenyCommunityAdminToCreatePlan() throws Exception {
        String body = """
                {
                    "tier": "ENTERPRISE",
                    "name": "Plan Enterprise",
                    "grossPricePerProperty": 900000.00
                }
                """;

        mockMvc.perform(post("/api/v1/saas/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/saas/plans"))
                .andExpect(status().isUnauthorized());
    }
}




