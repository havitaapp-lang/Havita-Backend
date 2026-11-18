package com.havita.modules.push.application;

import com.havita.modules.push.domain.PushSubscription;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import com.havita.modules.realtime.domain.RealtimeNotification;
import com.havita.modules.resident.domain.ResidentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * High-level orchestrator that determines WHO should receive a push notification
 * and dispatches the push via WebPushService. Never blocks the calling business operation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private final PushSubscriptionRepository subscriptionRepository;
    private final WebPushService webPushService;
    // P8-B: solo destinatarios con acceso vigente a la comunidad (decidido en el servidor al enviar).
    private final PushRecipientPolicy recipientPolicy;

    /**
     * Sends a push notification to all active subscriptions of a specific user in a tenant.
     * Called asynchronously so failures don't affect the calling transaction.
     */
    @Async
    public void sendPushToUser(UUID tenantId, UUID userId, String title, String message, String type, String url) {
        if (!webPushService.isConfigured()) {
            return;
        }

        List<PushSubscription> subscriptions = recipientPolicy.eligible(subscriptionRepository
                .findByUserIdAndTenantIdAndActiveTrue(userId, tenantId));

        if (subscriptions.isEmpty()) {
            log.debug("[Push] No hay suscripciones activas para userId={} en tenant={}", userId, tenantId);
            return;
        }

        Map<String, Object> payload = webPushService.buildPayload(title, message, type, url, null);

        int sent = 0;
        for (PushSubscription sub : subscriptions) {
            if (webPushService.sendPush(sub, payload)) {
                sent++;
            }
        }
        log.info("[Push] Enviadas {}/{} notificaciones push a userId={}", sent, subscriptions.size(), userId);
    }

    /**
     * Sends push notification to ALL active subscriptions in a tenant (broadcast).
     * Used for general announcements, panic alerts, etc.
     */
    @Async
    public void sendPushToTenant(UUID tenantId, String title, String message, String type, String url) {
        if (!webPushService.isConfigured()) {
            return;
        }

        List<PushSubscription> subscriptions = recipientPolicy.eligible(subscriptionRepository
                .findByTenantIdAndActiveTrue(tenantId));

        if (subscriptions.isEmpty()) {
            log.debug("[Push] No hay suscripciones activas en tenant={}", tenantId);
            return;
        }

        Map<String, Object> payload = webPushService.buildPayload(title, message, type, url, null);

        int sent = 0;
        for (PushSubscription sub : subscriptions) {
            if (webPushService.sendPush(sub, payload)) {
                sent++;
            }
        }
        log.info("[Push] Broadcast enviado: {}/{} en tenant={}", sent, subscriptions.size(), tenantId);
    }

    /**
     * Sends a push notification based on a RealtimeNotification entity.
     * Determines if it's targeted (user-specific) or broadcast (no user).
     */
    @Async
    public void sendPushForNotification(RealtimeNotification notification) {
        if (!webPushService.isConfigured()) {
            return;
        }

        String type = notification.getType();
        String title = notification.getTitle();
        String message = notification.getMessage();
        UUID tenantId = notification.getTenantId();
        String url = resolveUrlForType(type);

        if (notification.getUser() != null) {
            // Targeted to a specific user
            sendPushToUser(tenantId, notification.getUser().getId(), title, message, type, url);
        } else if (notification.getProperty() != null) {
            // P8-B (fuga D3): antes se enviaba a TODA la comunidad. Ahora solo a los residentes ACTIVOS de esa unidad,
            // resueltos en el servidor a partir de la unidad de la notificacion (nunca de datos del cliente).
            sendPushToProperty(tenantId, notification.getProperty().getId(), title, message, type, url);
        } else {
            // General broadcast
            sendPushToTenant(tenantId, title, message, type, url);
        }
    }

    /**
     * P8-B: notificacion de una unidad, solo a las suscripciones de sus residentes ACTIVOS en esa comunidad (y con acceso
     * vigente). Ningun otro usuario de la comunidad la recibe.
     */
    void sendPushToProperty(UUID tenantId, UUID propertyId, String title, String message, String type, String url) {
        List<PushSubscription> subscriptions = recipientPolicy.eligible(subscriptionRepository
                .findActiveForActiveResidentsOfProperty(tenantId, propertyId, ResidentStatus.ACTIVE));
        if (subscriptions.isEmpty()) {
            log.debug("[Push] Sin suscripciones de residentes activos para la unidad {} en tenant={}", propertyId, tenantId);
            return;
        }
        Map<String, Object> payload = webPushService.buildPayload(title, message, type, url, null);
        int sent = 0;
        for (PushSubscription sub : subscriptions) {
            if (webPushService.sendPush(sub, payload)) {
                sent++;
            }
        }
        log.info("[Push] Notificacion de unidad: {}/{} enviadas (unidad {}, tenant={})", sent, subscriptions.size(), propertyId, tenantId);
    }

    /** Aviso a los residentes ACTIVOS de una unidad (paquetes, visitas). Asincrono. */
    @Async
    public void sendToUnitResidents(UUID tenantId, UUID propertyId, String title, String message, String type, String url) {
        if (!webPushService.isConfigured() || propertyId == null) return;
        sendPushToProperty(tenantId, propertyId, title, message, type, url);
    }

    /**
     * Aviso a los residentes ACTIVOS de la comunidad (comunicados, asambleas). {@code residentType} vacio = todos; si no,
     * solo ese tipo de relacion (p. ej. "propietario"). No incluye porteria ni administracion. Asincrono.
     */
    @Async
    public void sendToCommunityResidents(UUID tenantId, String residentType, String title, String message, String type, String url) {
        if (!webPushService.isConfigured()) return;
        String filter = residentType == null ? "" : residentType.trim().toLowerCase(java.util.Locale.ROOT);
        List<PushSubscription> subscriptions = recipientPolicy.eligible(subscriptionRepository
                .findActiveForCommunityResidents(tenantId, ResidentStatus.ACTIVE, filter));
        if (subscriptions.isEmpty()) {
            log.debug("[Push] Sin suscripciones de residentes en tenant={}", tenantId);
            return;
        }
        Map<String, Object> payload = webPushService.buildPayload(title, message, type, url, null);
        int sent = 0;
        for (PushSubscription sub : subscriptions) {
            if (webPushService.sendPush(sub, payload)) sent++;
        }
        log.info("[Push] Aviso de comunidad ({}): {}/{} enviadas en tenant={}", type, sent, subscriptions.size(), tenantId);
    }

    /**
     * Sends a test push to a specific subscription.
     */
    public boolean sendTestPush(PushSubscription subscription) {
        Map<String, Object> payload = webPushService.buildPayload(
                "Notificación de prueba",
                "¡Las notificaciones push de Havita están funcionando correctamente!",
                "TEST",
                "/",
                null
        );
        return webPushService.sendPush(subscription, payload);
    }

    private String resolveUrlForType(String type) {
        if (type == null) return "/";
        return switch (type) {
            case "VISIT_ARRIVAL" -> "/visits";
            case "PACKAGE_RECEIVED" -> "/packages";
            case "RESERVATION_STATUS" -> "/reservations";
            case "PANIC_ALERT" -> "/alerts";
            case "GENERAL_ANNOUNCEMENT" -> "/community";
            default -> "/";
        };
    }
}
