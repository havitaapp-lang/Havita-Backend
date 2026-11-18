package com.havita.modules.push;

import com.havita.modules.push.application.WebPushService;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class WebPushServiceTest {

    @Mock
    private PushSubscriptionRepository subscriptionRepository;

    @Test
    @DisplayName("buildPayload formats correct push payload structure for Service Worker")
    void testBuildPayload() {
        WebPushService service = new WebPushService(subscriptionRepository, new ObjectMapper());

        Map<String, Object> payload = service.buildPayload(
                "Visita en portería",
                "Ha llegado Juan Pérez a tu apartamento",
                "VISIT_ARRIVAL",
                "/visits",
                "{\"visitorId\":\"123\"}"
        );

        assertThat(payload).isNotNull();
        assertThat(payload.get("title")).isEqualTo("Visita en portería");
        assertThat(payload.get("body")).isEqualTo("Ha llegado Juan Pérez a tu apartamento");
        assertThat(payload.get("type")).isEqualTo("VISIT_ARRIVAL");
        assertThat(payload.get("url")).isEqualTo("/visits");
        assertThat(payload.get("data")).isEqualTo("{\"visitorId\":\"123\"}");
    }

    @Test
    @DisplayName("isConfigured returns false when VAPID keys are absent")
    void testIsConfigured_FalseWhenKeysAbsent() {
        WebPushService service = new WebPushService(subscriptionRepository, new ObjectMapper());
        service.init();
        assertThat(service.isConfigured()).isFalse();
    }
}
