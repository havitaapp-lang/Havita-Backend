package com.havita.modules.push.infrastructure;

import com.havita.modules.push.application.PushNotificationService;
import com.havita.modules.realtime.domain.RealtimeNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for notification-related events and triggers push notification delivery.
 * Uses @TransactionalEventListener to ensure the notification has been committed
 * before attempting push delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushNotificationEventListener {

    private final PushNotificationService pushNotificationService;

    /**
     * Triggered after a RealtimeNotification is persisted and committed.
     * Dispatches the push notification asynchronously.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            pushNotificationService.sendPushForNotification(event.notification());
        } catch (Exception e) {
            log.error("[PushListener] Error procesando evento de notificación: {}", e.getMessage());
            // Push failures must never break the business flow
        }
    }

    /**
     * Application event fired when a RealtimeNotification is created.
     */
    public record NotificationCreatedEvent(RealtimeNotification notification) {}
}
