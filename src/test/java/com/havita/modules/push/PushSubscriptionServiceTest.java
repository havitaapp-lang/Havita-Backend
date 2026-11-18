package com.havita.modules.push;

import com.havita.modules.push.api.dto.PushSubscriptionRequest;
import com.havita.modules.push.api.dto.PushSubscriptionResponse;
import com.havita.modules.push.application.PushNotificationService;
import com.havita.modules.push.application.PushSubscriptionService;
import com.havita.modules.push.application.WebPushService;
import com.havita.modules.push.domain.PushSubscription;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {

    @Mock
    private PushSubscriptionRepository subscriptionRepository;

    @Mock
    private PushNotificationService pushNotificationService;

    @Mock
    private WebPushService webPushService;

    @InjectMocks
    private PushSubscriptionService pushSubscriptionService;

    private UUID userId;
    private UUID tenantId;
    private PushSubscriptionRequest sampleRequest;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        sampleRequest = new PushSubscriptionRequest(
                "https://updates.push.services.mozilla.com/wpush/v2/gAAAAAB...",
                new PushSubscriptionRequest.Keys("BCn44dG7...", "authSecret123"),
                "Firefox en macOS"
        );
    }

    @Test
    @DisplayName("subscribe should create a new subscription when endpoint does not exist")
    void testSubscribe_CreatesNewSubscription() {
        when(subscriptionRepository.findByEndpoint(sampleRequest.endpoint()))
                .thenReturn(Optional.empty());

        when(subscriptionRepository.save(any(PushSubscription.class)))
                .thenAnswer(inv -> {
                    PushSubscription sub = inv.getArgument(0);
                    sub.setId(UUID.randomUUID());
                    return sub;
                });

        PushSubscriptionResponse response = pushSubscriptionService.subscribe(userId, tenantId, sampleRequest);

        assertThat(response).isNotNull();
        assertThat(response.deviceName()).isEqualTo("Firefox en macOS");
        assertThat(response.active()).isTrue();

        ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
        verify(subscriptionRepository).save(captor.capture());

        PushSubscription saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getEndpoint()).isEqualTo(sampleRequest.endpoint());
        assertThat(saved.getP256dh()).isEqualTo(sampleRequest.keys().p256dh());
        assertThat(saved.getAuth()).isEqualTo(sampleRequest.keys().auth());
    }

    @Test
    @DisplayName("subscribe should update existing subscription when endpoint already exists (idempotent)")
    void testSubscribe_UpdatesExistingSubscription() {
        PushSubscription existing = PushSubscription.builder()
                .userId(UUID.randomUUID()) // previously registered to another user or device
                .endpoint(sampleRequest.endpoint())
                .p256dh("oldKey")
                .auth("oldAuth")
                .deviceName("Old Device")
                .active(false)
                .build();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);

        when(subscriptionRepository.findByEndpoint(sampleRequest.endpoint()))
                .thenReturn(Optional.of(existing));

        when(subscriptionRepository.save(any(PushSubscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PushSubscriptionResponse response = pushSubscriptionService.subscribe(userId, tenantId, sampleRequest);

        assertThat(response.active()).isTrue();
        assertThat(existing.getUserId()).isEqualTo(userId);
        assertThat(existing.getP256dh()).isEqualTo(sampleRequest.keys().p256dh());
        assertThat(existing.getAuth()).isEqualTo(sampleRequest.keys().auth());
        assertThat(existing.getDeviceName()).isEqualTo("Firefox en macOS");
    }

    @Test
    @DisplayName("unsubscribe should deactivate all subscriptions for user and tenant")
    void testUnsubscribe_DeactivatesAllForUserAndTenant() {
        pushSubscriptionService.unsubscribe(userId, tenantId);
        verify(subscriptionRepository).deactivateAllByUserIdAndTenantId(userId, tenantId);
    }

    @Test
    @DisplayName("getStatus should return correct subscription count and push config state")
    void testGetStatus() {
        when(subscriptionRepository.existsByUserIdAndTenantIdAndActiveTrue(userId, tenantId)).thenReturn(true);
        when(subscriptionRepository.countByUserIdAndTenantIdAndActiveTrue(userId, tenantId)).thenReturn(2L);
        when(webPushService.isConfigured()).thenReturn(true);

        PushSubscriptionService.PushStatusResponse status = pushSubscriptionService.getStatus(userId, tenantId);

        assertThat(status.pushConfigured()).isTrue();
        assertThat(status.subscribed()).isTrue();
        assertThat(status.deviceCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("listSubscriptions should not expose endpoint or sensitive cryptographic keys")
    void testListSubscriptions_SafeDtoMapping() {
        PushSubscription sub = PushSubscription.builder()
                .userId(userId)
                .endpoint("https://secret.endpoint.com/sensitive")
                .p256dh("sensitiveP256dhKey")
                .auth("sensitiveAuthKey")
                .deviceName("Mi iPhone")
                .active(true)
                .build();
        sub.setId(UUID.randomUUID());
        sub.setTenantId(tenantId);

        when(subscriptionRepository.findByUserIdAndTenantIdAndActiveTrue(userId, tenantId))
                .thenReturn(List.of(sub));

        List<PushSubscriptionResponse> result = pushSubscriptionService.listSubscriptions(userId, tenantId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).deviceName()).isEqualTo("Mi iPhone");
        assertThat(result.get(0).active()).isTrue();
    }
}
