package com.havita.modules.push.application;

import com.havita.modules.push.domain.PushSubscription;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import java.nio.charset.StandardCharsets;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.Map;

/**
 * Low-level service responsible for sending Web Push messages using VAPID.
 * Handles encryption, signing, HTTP delivery, and stale subscription cleanup.
 */
@Slf4j
@Service
public class WebPushService {

    private final PushSubscriptionRepository subscriptionRepository;
    private final ObjectMapper objectMapper;

    @Value("${vapid.public-key:}")
    private String vapidPublicKey;

    @Value("${vapid.private-key:}")
    private String vapidPrivateKey;

    @Value("${vapid.subject:mailto:soporte@havita.com.co}")
    private String vapidSubject;

    private PushService pushService;
    private boolean configured = false;

    public WebPushService(PushSubscriptionRepository subscriptionRepository, ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        if (vapidPublicKey == null || vapidPublicKey.isBlank() ||
            vapidPrivateKey == null || vapidPrivateKey.isBlank()) {
            log.warn("[WebPush] Claves VAPID no configuradas. Las notificaciones push estarán deshabilitadas.");
            return;
        }

        try {
            this.pushService = new PushService(vapidPublicKey, vapidPrivateKey, vapidSubject);
            this.configured = true;
            log.info("[WebPush] Servicio de Web Push inicializado correctamente.");
        } catch (GeneralSecurityException e) {
            log.error("[WebPush] Error inicializando el servicio de Web Push: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    public String getVapidPublicKey() {
        return vapidPublicKey;
    }

    /**
     * Sends a push notification to a single subscription.
     * Deactivates the subscription if the push service returns 404 or 410 (subscription expired/gone).
     *
     * @return true if the push was sent successfully, false otherwise
     */
    public boolean sendPush(PushSubscription subscription, Map<String, Object> payload) {
        // Notificaciones generales: comportamiento previo (TTL por defecto de la libreria, sin Urgency ni Topic).
        return send(subscription, payload, null).outcome() == Outcome.SENT;
    }

    /** P8-B: opciones de entrega. {@code null} en un campo = no se envia esa cabecera. */
    public record PushOptions(Integer ttlSeconds, Urgency urgency, String topic) {
        /** RFC 8030 §5.4: el Topic admite como maximo 32 caracteres del alfabeto base64url. */
        public PushOptions {
            if (topic != null && !topic.matches("[A-Za-z0-9_-]{1,32}")) {
                throw new IllegalArgumentException("Topic de Web Push invalido");
            }
            if (ttlSeconds != null && ttlSeconds < 0) {
                throw new IllegalArgumentException("TTL negativo");
            }
        }
    }

    public enum Outcome { SENT, GONE, RATE_LIMITED, FAILED, NOT_CONFIGURED }

    /** Resultado de un envio; {@code httpStatus} es 0 si no hubo respuesta HTTP. */
    public record PushResult(Outcome outcome, int httpStatus) {
        public boolean retryable() {
            return outcome == Outcome.RATE_LIMITED || (outcome == Outcome.FAILED && (httpStatus == 0 || httpStatus >= 500));
        }
    }

    /**
     * P8-B: envio con TTL, Urgency y Topic. Desactiva la suscripcion ante 404/410. Nunca registra el endpoint ni las
     * claves: solo el id de la suscripcion y el host del servicio de push.
     */
    public PushResult send(PushSubscription subscription, Map<String, Object> payload, PushOptions options) {
        if (!configured) {
            log.debug("[WebPush] Push no enviado — servicio no configurado.");
            return new PushResult(Outcome.NOT_CONFIGURED, 0);
        }

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            Notification notification = buildNotification(subscription, payloadJson.getBytes(StandardCharsets.UTF_8), options);

            // P8-B: cifrado RFC 8291 (aes128gcm). La libreria usa por defecto el borrador antiguo «aesgcm», que Safari/iOS
            // no admite (detectado en WebPushDeliveryOptionsTest). Chrome, Firefox, Edge y Safari admiten aes128gcm.
            HttpResponse response = pushService.send(notification, nl.martijndwars.webpush.Encoding.AES128GCM);
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode >= 200 && statusCode < 300) {
                subscription.touchLastUsed();
                subscriptionRepository.save(subscription);
                return new PushResult(Outcome.SENT, statusCode);
            }

            // 404 or 410: subscription no longer valid
            if (statusCode == 404 || statusCode == 410) {
                log.info("[WebPush] Suscripción expirada (HTTP {}), desactivando id={}",
                        statusCode, subscription.getId());
                subscription.deactivate();
                subscriptionRepository.save(subscription);
                return new PushResult(Outcome.GONE, statusCode);
            }

            // 429: rate limited — log but don't deactivate
            if (statusCode == 429) {
                log.warn("[WebPush] Rate limited por el proveedor push para suscripción id={}", subscription.getId());
                return new PushResult(Outcome.RATE_LIMITED, statusCode);
            }

            log.warn("[WebPush] Respuesta inesperada HTTP {} para suscripción id={} (servicio {})",
                    statusCode, subscription.getId(), serviceHost(subscription));
            return new PushResult(Outcome.FAILED, statusCode);

        } catch (Exception e) {
            log.error("[WebPush] Error enviando push a suscripción id={}: {}", subscription.getId(), e.getClass().getSimpleName());
            return new PushResult(Outcome.FAILED, 0);
        }
    }

    private static Notification buildNotification(PushSubscription s, byte[] payload, PushOptions options) throws Exception {
        if (options == null) {
            return new Notification(s.getEndpoint(), s.getP256dh(), s.getAuth(), payload);
        }
        Notification.NotificationBuilder b = Notification.builder()
                .endpoint(s.getEndpoint())
                .userPublicKey(s.getP256dh())
                .userAuth(s.getAuth())
                .payload(payload);
        if (options.ttlSeconds() != null) b.ttl(options.ttlSeconds());
        if (options.urgency() != null) b.urgency(options.urgency());
        if (options.topic() != null) b.topic(options.topic());
        return b.build();
    }

    /** Host del servicio de push (FCM, APNs, Mozilla…): no es secreto y ayuda a diagnosticar. Nunca la ruta. */
    public static String serviceHost(PushSubscription s) {
        try {
            return java.net.URI.create(s.getEndpoint()).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Builds a standard Web Push payload map suitable for the Service Worker.
     */
    public Map<String, Object> buildPayload(String title, String message, String type, String url, String dataPayload) {
        return Map.of(
                "title", title != null ? title : "Havita",
                "body", message != null ? message : "",
                "type", type != null ? type : "GENERAL",
                "url", url != null ? url : "/",
                "data", dataPayload != null ? dataPayload : ""
        );
    }
}
