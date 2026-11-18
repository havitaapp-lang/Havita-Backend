package com.havita.modules.intercom.application;

import com.havita.modules.intercom.domain.IntercomCall;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import com.havita.modules.push.application.PushRecipientPolicy;
import com.havita.modules.push.application.WebPushService;
import com.havita.modules.push.domain.PushSubscription;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Urgency;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * P8-B: Web Push de la citofonia para que la llamada llegue con la app en segundo plano, cerrada o el movil bloqueado.
 * Complementa al SSE (que solo funciona con la app abierta); la entrega no depende de el.
 *
 * <p><b>Destinatarios</b> (decididos en el servidor con el estado vigente; nunca con datos del cliente): solo las
 * suscripciones ACTIVAS del usuario LLAMADO en ESA comunidad, si su relacion con la unidad de la llamada sigue ACTIVA y
 * conserva acceso efectivo a la comunidad. Todos sus dispositivos.
 *
 * <p><b>Contenido</b>: texto fijo, sin nombres, unidades ni datos personales ("Havita" / "Llamada de portería" /
 * "Toca para abrir y contestar"); el id de la llamada (que sin sesion no permite nada) y un nonce de recibo. Nunca
 * tokens de RealtimeKit ni credenciales: el audio solo se obtiene al ACEPTAR, que el servidor solo admite en RINGING.
 *
 * <p><b>Entrega</b>: aviso de timbre con TTL = segundos de timbre restantes (≤ 45), {@code Urgency: high} y un
 * {@code Topic} por llamada. Los avisos de fin (cancelada, perdida, rechazada o contestada en otro dispositivo) usan el
 * mismo Topic, de modo que el servicio de push SUSTITUYE un aviso de timbre aun no entregado, y solo se envian a los
 * dispositivos que recibieron el de timbre. Una entrega por (llamada, suscripcion, tipo); un reintento ante un error
 * transitorio. Los eventos de una misma llamada se procesan en orden (bloqueo por llamada).
 */
@Slf4j
@Component
public class IntercomPushNotifier {

    /** TTL de los avisos de fin: lo bastante para sustituir un aviso pendiente en un dispositivo sin conexion breve. */
    static final int END_TTL_SECONDS = 120;
    /** Por debajo de este TTL no merece la pena avisar del timbre: la llamada expira enseguida. */
    static final int MIN_RINGING_TTL_SECONDS = 3;
    static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    private final IntercomCallRepository calls;
    private final PushSubscriptionRepository subscriptions;
    private final WebPushService webPush;
    private final PushRecipientPolicy recipientPolicy;
    private final IntercomPushDeliveryRegistry registry;
    private final TransactionTemplate tx;
    private final Map<UUID, ReentrantLock> callLocks = new ConcurrentHashMap<>();
    private Duration retryDelay = RETRY_DELAY;

    public IntercomPushNotifier(IntercomCallRepository calls, PushSubscriptionRepository subscriptions,
                                WebPushService webPush, PushRecipientPolicy recipientPolicy,
                                IntercomPushDeliveryRegistry registry, PlatformTransactionManager txManager) {
        this.calls = calls;
        this.subscriptions = subscriptions;
        this.webPush = webPush;
        this.recipientPolicy = recipientPolicy;
        this.registry = registry;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Datos de la llamada leidos con el estado vigente (en una transaccion propia). */
    record CallSnapshot(UUID callId, UUID tenantId, IntercomCallStatus status, UUID recipientUserId,
                        boolean recipientRelationActive, OffsetDateTime ringingAt) {}

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCallEvent(IntercomPushEvent event) {
        try {
            dispatch(event);
        } catch (Exception e) {
            // El push nunca afecta a la llamada.
            log.warn("[IntercomPush] Error despachando {} de la llamada {}: {}", event.kind(), event.callId(), e.getClass().getSimpleName());
        }
    }

    void dispatch(IntercomPushEvent event) {
        if (!webPush.isConfigured()) {
            log.debug("[IntercomPush] Web Push no configurado: sin aviso para la llamada {}", event.callId());
            return;
        }
        ReentrantLock lock = callLocks.computeIfAbsent(event.callId(), id -> new ReentrantLock());
        lock.lock();
        try {
            if (event.kind() == IntercomPushEvent.Kind.RINGING) {
                dispatchRinging(event);
            } else {
                dispatchEnd(event);
            }
        } finally {
            lock.unlock();
            if (event.kind().isEnd()) {
                callLocks.remove(event.callId(), lock);
            }
        }
    }

    private Optional<CallSnapshot> snapshot(UUID callId, UUID tenantId) {
        return Optional.ofNullable(tx.execute(status -> calls.findByIdAndTenantId(callId, tenantId).map(c -> {
            Resident r = c.getRecipientResident();
            UUID recipientUserId = c.getRecipientUser() != null ? c.getRecipientUser().getId() : null;
            boolean relationActive = r != null && r.getStatus() == ResidentStatus.ACTIVE && r.getUser() != null
                    && Objects.equals(r.getUser().getId(), recipientUserId)
                    && r.getProperty() != null && c.getProperty() != null
                    && Objects.equals(r.getProperty().getId(), c.getProperty().getId());
            return new CallSnapshot(c.getId(), c.getTenantId(), c.getStatus(), recipientUserId, relationActive, c.getRingingAt());
        }).orElse(null)));
    }

    private int remainingRingSeconds(CallSnapshot s) {
        if (s.ringingAt() == null) return 0;
        long elapsed = Duration.between(s.ringingAt(), OffsetDateTime.now()).getSeconds();
        return (int) Math.max(0, IntercomService.RING_TIMEOUT_SECONDS - elapsed);
    }

    private void dispatchRinging(IntercomPushEvent event) {
        CallSnapshot s = snapshot(event.callId(), event.tenantId()).orElse(null);
        if (s == null || s.status() != IntercomCallStatus.RINGING || s.recipientUserId() == null) return;
        if (!s.recipientRelationActive() || !recipientPolicy.hasActiveAccess(s.recipientUserId(), s.tenantId())) {
            log.info("[IntercomPush] Llamada {}: el destinatario ya no esta autorizado; sin aviso", s.callId());
            return;
        }
        List<PushSubscription> targets = recipientPolicy.eligible(
                subscriptions.findByUserIdAndTenantIdAndActiveTrue(s.recipientUserId(), s.tenantId()));
        if (targets.isEmpty()) {
            log.info("[IntercomPush] Llamada {}: el residente no tiene dispositivos suscritos (solo SSE)", s.callId());
            return;
        }
        int sent = 0;
        for (PushSubscription sub : targets) {
            Optional<IntercomPushDeliveryRegistry.Delivery> reserved = registry.reserve(s.callId(), s.tenantId(), sub.getId(), IntercomPushEvent.Kind.RINGING);
            if (reserved.isEmpty()) continue; // duplicado
            IntercomPushDeliveryRegistry.Delivery d = reserved.get();
            int ttl = remainingRingSeconds(s);
            if (ttl < MIN_RINGING_TTL_SECONDS) break;
            WebPushService.PushResult r = webPush.send(sub, payload(s, IntercomPushEvent.Kind.RINGING, d.nonce()), options(ttl, s.callId()));
            registry.markSent(d, r);
            if (r.retryable()) {
                sleep(retryDelay);
                // Se reintenta solo si la llamada sigue timbrando (estado vigente) y queda tiempo.
                CallSnapshot again = snapshot(s.callId(), s.tenantId()).orElse(null);
                int ttl2 = again != null ? remainingRingSeconds(again) : 0;
                if (again != null && again.status() == IntercomCallStatus.RINGING && ttl2 >= MIN_RINGING_TTL_SECONDS) {
                    r = webPush.send(sub, payload(again, IntercomPushEvent.Kind.RINGING, d.nonce()), options(ttl2, s.callId()));
                    registry.markSent(d, r);
                }
            }
            if (r.outcome() == WebPushService.Outcome.SENT) sent++;
            log.info("[IntercomPush] timbre call={} sub={} servicio={} resultado={} http={} ttl={}s",
                    s.callId(), sub.getId(), WebPushService.serviceHost(sub), r.outcome(), r.httpStatus(), ttl);
        }
        log.info("[IntercomPush] Llamada {}: aviso de timbre enviado a {}/{} dispositivo(s)", s.callId(), sent, targets.size());
    }

    private void dispatchEnd(IntercomPushEvent event) {
        List<UUID> notified = registry.subscriptionsNotifiedOfRinging(event.callId());
        if (notified.isEmpty()) return; // ningun dispositivo tiene un aviso de timbre que sustituir
        CallSnapshot s = snapshot(event.callId(), event.tenantId()).orElse(null);
        if (s == null || s.recipientUserId() == null) return;
        for (PushSubscription sub : subscriptions.findAllById(notified)) {
            // Solo suscripciones vigentes del mismo residente y comunidad.
            if (!Boolean.TRUE.equals(sub.getActive()) || !s.recipientUserId().equals(sub.getUserId())
                    || !s.tenantId().equals(sub.getTenantId())) continue;
            if (event.excludeEndpoint() != null && event.excludeEndpoint().equals(sub.getEndpoint())) continue;
            Optional<IntercomPushDeliveryRegistry.Delivery> reserved = registry.reserve(s.callId(), s.tenantId(), sub.getId(), event.kind());
            if (reserved.isEmpty()) continue;
            IntercomPushDeliveryRegistry.Delivery d = reserved.get();
            WebPushService.PushResult r = webPush.send(sub, payload(s, event.kind(), d.nonce()), options(END_TTL_SECONDS, s.callId()));
            registry.markSent(d, r);
            log.info("[IntercomPush] fin call={} kind={} sub={} resultado={} http={}",
                    s.callId(), event.kind(), sub.getId(), r.outcome(), r.httpStatus());
        }
    }

    /** Carga del aviso. Texto fijo, sin datos personales. */
    static Map<String, Object> payload(CallSnapshot s, IntercomPushEvent.Kind kind, String receiptNonce) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("v", 1);
        p.put("type", "INTERCOM_CALL");
        p.put("kind", kind.name());
        p.put("callId", s.callId().toString());
        p.put("title", "Havita");
        p.put("body", switch (kind) {
            case RINGING -> "Llamada de portería";
            case CANCELLED -> "Llamada de portería cancelada";
            case MISSED -> "Llamada de portería perdida";
            case REJECTED -> "Llamada de portería rechazada";
            case ANSWERED -> "Llamada de portería contestada en otro dispositivo";
        });
        if (kind == IntercomPushEvent.Kind.RINGING) {
            p.put("detail", "Toca para abrir y contestar");
            p.put("url", "/resident?intercomCall=" + s.callId());
            if (s.ringingAt() != null) {
                // El SW no ofrece contestar un aviso que llega despues de que la llamada haya expirado.
                p.put("expiresAt", s.ringingAt().toInstant().plusSeconds(IntercomService.RING_TIMEOUT_SECONDS).toEpochMilli());
            }
        } else {
            p.put("url", "/resident");
        }
        p.put("tag", "intercom-" + s.callId());
        p.put("receipt", receiptNonce);
        return p;
    }

    static WebPushService.PushOptions options(int ttlSeconds, UUID callId) {
        return new WebPushService.PushOptions(ttlSeconds, Urgency.HIGH, topic(callId));
    }

    /** Topic de 32 caracteres base64url derivado del id de la llamada (RFC 8030 §5.4). */
    static String topic(UUID callId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(callId.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Solo pruebas. */
    void setRetryDelay(Duration d) {
        this.retryDelay = d;
    }
}
