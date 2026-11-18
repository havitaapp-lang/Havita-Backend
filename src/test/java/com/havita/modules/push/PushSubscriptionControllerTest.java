package com.havita.modules.push;

import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.domain.TenantStatus;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.push.api.PushSubscriptionController;
import com.havita.modules.push.api.dto.PushSubscriptionRequest;
import com.havita.modules.push.api.dto.PushSubscriptionResponse;
import com.havita.modules.push.application.PushSubscriptionService;
import com.havita.modules.push.application.WebPushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PushSubscriptionController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class PushSubscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PushSubscriptionService subscriptionService;

    @MockBean
    private WebPushService webPushService;

    @MockBean
    private TenantRepository tenantRepository;

    @MockBean
    private com.havita.security.JwtService jwtService;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();

        Tenant tenant = new Tenant();
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findById(any(UUID.class))).thenReturn(Optional.of(tenant));
        com.havita.security.TenantContextHolder.setTenantId(tenantId);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.havita.security.TenantContextHolder.clear();
    }

    @Test
    @DisplayName("GET /api/v1/push-subscriptions/public-key is accessible without authentication")
    void testGetPublicKey_Public() throws Exception {
        when(webPushService.getVapidPublicKey()).thenReturn("BD_some_valid_vapid_public_key");

        mockMvc.perform(get("/api/v1/push-subscriptions/public-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.publicKey").value("BD_some_valid_vapid_public_key"))
                .andExpect(jsonPath("$.data.configured").value("true"));
    }

    @Test
    @WithMockUser(username = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
    @DisplayName("POST /api/v1/push-subscriptions requires auth and registers subscription")
    void testSubscribe_Authenticated() throws Exception {
        PushSubscriptionRequest request = new PushSubscriptionRequest(
                "https://updates.push.services.mozilla.com/wpush/v2/gAAAAAB...",
                new PushSubscriptionRequest.Keys("BCn44dG7...", "authSecret123"),
                "Firefox en macOS"
        );

        PushSubscriptionResponse response = new PushSubscriptionResponse(
                UUID.randomUUID(),
                "Firefox en macOS",
                true,
                OffsetDateTime.now(),
                null
        );

        when(subscriptionService.subscribe(any(UUID.class), any(UUID.class), any(PushSubscriptionRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/push-subscriptions")
                        .header("X-Tenant-ID", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deviceName").value("Firefox en macOS"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @WithMockUser(username = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
    @DisplayName("GET /api/v1/push-subscriptions/status returns current user push status")
    void testGetStatus() throws Exception {
        PushSubscriptionService.PushStatusResponse statusResponse =
                new PushSubscriptionService.PushStatusResponse(true, true, 1L);

        when(subscriptionService.getStatus(any(UUID.class), any(UUID.class)))
                .thenReturn(statusResponse);

        mockMvc.perform(get("/api/v1/push-subscriptions/status")
                        .header("X-Tenant-ID", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pushConfigured").value(true))
                .andExpect(jsonPath("$.data.subscribed").value(true))
                .andExpect(jsonPath("$.data.deviceCount").value(1));
    }

    @Test
    @WithMockUser(username = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
    @DisplayName("DELETE /api/v1/push-subscriptions deactivates user subscriptions")
    void testUnsubscribe() throws Exception {
        mockMvc.perform(delete("/api/v1/push-subscriptions")
                        .header("X-Tenant-ID", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
