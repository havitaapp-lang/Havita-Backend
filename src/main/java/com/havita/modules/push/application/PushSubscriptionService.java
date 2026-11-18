package com.havita.modules.push.application;

import com.havita.modules.push.api.dto.PushSubscriptionRequest;
import com.havita.modules.push.api.dto.PushSubscriptionResponse;
import com.havita.modules.push.domain.PushSubscription;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import com.havita.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages CRUD operations for push subscriptions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

    private final PushSubscriptionRepository subscriptionRepository;
    private final PushNotificationService pushNotificationService;
    private final WebPushService webPushService;

    /**
     * Registers or updates a push subscription (idempotent on endpoint).
     */
    @Transactional
    public PushSubscriptionResponse subscribe(UUID userId, UUID tenantId, PushSubscriptionRequest request) {
        String endpoint = request.endpoint();
        String p256dh = request.keys().p256dh();
        String auth = request.keys().auth();

        // Check if this endpoint already exists
        Optional<PushSubscription> existing = subscriptionRepository.findByEndpoint(endpoint);

        // P8-B: tenant_id no es actualizable (TenantScopedEntity). Si el mismo endpoint llega desde otra comunidad, antes
        // se cambiaba solo en memoria y la fila seguia en la comunidad anterior. Ahora se elimina y se crea de nuevo.
        if (existing.isPresent() && !tenantId.equals(existing.get().getTenantId())) {
            subscriptionRepository.delete(existing.get());
            subscriptionRepository.flush();
            existing = Optional.empty();
            log.info("[PushSub] Endpoint registrado de nuevo desde otra comunidad: suscripcion anterior eliminada");
        }

        PushSubscription subscription;
        if (existing.isPresent()) {
            // Update existing subscription (re-activate if deactivated, update keys)
            subscription = existing.get();
            if (!userId.equals(subscription.getUserId())) {
                // El navegador es el mismo dispositivo y ahora pertenece a otra sesion: deja de recibir lo del anterior.
                log.info("[PushSub] Suscripcion id={} reasignada a otro usuario en el mismo dispositivo", subscription.getId());
            }
            subscription.setUserId(userId);
            subscription.setP256dh(p256dh);
            subscription.setAuth(auth);
            subscription.setDeviceName(request.deviceName());
            subscription.setActive(true);
            log.info("[PushSub] Suscripción actualizada para userId={}", userId);
        } else {
            // Create new subscription
            subscription = PushSubscription.builder()
                    .userId(userId)
                    .endpoint(endpoint)
                    .p256dh(p256dh)
                    .auth(auth)
                    .deviceName(request.deviceName())
                    .active(true)
                    .build();
            subscription.setTenantId(tenantId);
            log.info("[PushSub] Nueva suscripción creada para userId={}", userId);
        }

        subscription = subscriptionRepository.save(subscription);
        return toResponse(subscription);
    }

    /**
     * Deactivates all push subscriptions for a user in a specific tenant.
     */
    @Transactional
    public void unsubscribe(UUID userId, UUID tenantId) {
        subscriptionRepository.deactivateAllByUserIdAndTenantId(userId, tenantId);
        log.info("[PushSub] Todas las suscripciones desactivadas para userId={} en tenant={}", userId, tenantId);
    }

    /**
     * Deactivates a specific subscription by endpoint.
     */
    /**
     * P8-B: revoca SOLO la suscripcion de este dispositivo (cierre de sesion). Solo afecta a una suscripcion del propio
     * usuario en la comunidad actual; un endpoint ajeno o desconocido no hace nada (sin revelar si existe).
     */
    @Transactional
    public void unsubscribeByEndpoint(UUID userId, UUID tenantId, String endpoint) {
        Optional<PushSubscription> sub = subscriptionRepository
                .findByUserIdAndTenantIdAndEndpoint(userId, tenantId, endpoint);
        sub.ifPresent(s -> {
            s.deactivate();
            subscriptionRepository.save(s);
            log.info("[PushSub] Suscripción desactivada por endpoint para userId={}", userId);
        });
    }

    /**
     * Gets the push subscription status for a user.
     */
    @Transactional(readOnly = true)
    public PushStatusResponse getStatus(UUID userId, UUID tenantId) {
        boolean hasActive = subscriptionRepository.existsByUserIdAndTenantIdAndActiveTrue(userId, tenantId);
        long count = subscriptionRepository.countByUserIdAndTenantIdAndActiveTrue(userId, tenantId);
        boolean pushConfigured = webPushService.isConfigured();
        return new PushStatusResponse(pushConfigured, hasActive, count);
    }

    /**
     * Lists active subscriptions for a user.
     */
    @Transactional(readOnly = true)
    public List<PushSubscriptionResponse> listSubscriptions(UUID userId, UUID tenantId) {
        return subscriptionRepository.findByUserIdAndTenantIdAndActiveTrue(userId, tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Sends a test push to all active subscriptions of a user.
     */
    @Transactional(readOnly = true)
    public int sendTest(UUID userId, UUID tenantId) {
        List<PushSubscription> subs = subscriptionRepository.findByUserIdAndTenantIdAndActiveTrue(userId, tenantId);
        int sent = 0;
        for (PushSubscription sub : subs) {
            if (pushNotificationService.sendTestPush(sub)) {
                sent++;
            }
        }
        return sent;
    }

    private PushSubscriptionResponse toResponse(PushSubscription sub) {
        return new PushSubscriptionResponse(
                sub.getId(),
                sub.getDeviceName(),
                sub.getActive(),
                sub.getCreatedAt(),
                sub.getLastUsedAt()
        );
    }

    public record PushStatusResponse(boolean pushConfigured, boolean subscribed, long deviceCount) {}
}
