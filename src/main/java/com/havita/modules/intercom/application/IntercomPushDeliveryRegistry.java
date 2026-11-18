package com.havita.modules.intercom.application;

import com.havita.modules.push.application.WebPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P8-B: entregas de push de la citofonia, EN MEMORIA (como los tokens de llamada de {@code IntercomService}).
 *
 * <ul>
 *   <li><b>Deduplicacion:</b> una sola entrega por (llamada, suscripcion, tipo). Un evento repetido no reenvia.</li>
 *   <li><b>Recibos de diagnostico:</b> cada entrega lleva un nonce aleatorio de 128 bits. El service worker lo devuelve al
 *       recibir, mostrar y pulsar el aviso. Solo se guarda el HASH del nonce; un recibo solo fija la PRIMERA marca de
 *       tiempo de su etapa y no devuelve datos. No se guarda el endpoint, ni direcciones IP, ni contenido.</li>
 *   <li><b>Retencion:</b> 2 h y como maximo {@value #MAX_ENTRIES} entregas; se purga cada minuto.</li>
 * </ul>
 * No hay migracion de base de datos: los datos son operativos y de diagnostico, y los recibos tambien quedan en el
 * registro de la aplicacion (sin datos sensibles).
 */
@Slf4j
@Component
public class IntercomPushDeliveryRegistry {

    public enum Stage { RECEIVED, DISPLAYED, CLICKED }

    static final int MAX_ENTRIES = 20_000;
    static final Duration RETENTION = Duration.ofHours(2);

    private final Map<String, Delivery> byKey = new ConcurrentHashMap<>();
    private final Map<String, Delivery> byNonceHash = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private Clock clock = Clock.systemUTC();

    /** Una entrega: inmutable salvo el resultado del envio y las marcas de los recibos. */
    public static final class Delivery {
        private final UUID callId;
        private final UUID tenantId;
        private final UUID subscriptionId;
        private final IntercomPushEvent.Kind kind;
        private final String nonce; // solo en memoria hasta el envio; nunca se registra
        private final String nonceHash;
        private final Instant createdAt;
        private volatile Instant sentAt;
        private volatile WebPushService.Outcome outcome;
        private volatile int httpStatus;
        private volatile int attempts;
        private volatile Instant receivedAt;
        private volatile Instant displayedAt;
        private volatile Instant clickedAt;

        Delivery(UUID callId, UUID tenantId, UUID subscriptionId, IntercomPushEvent.Kind kind, String nonce, String nonceHash, Instant createdAt) {
            this.callId = callId;
            this.tenantId = tenantId;
            this.subscriptionId = subscriptionId;
            this.kind = kind;
            this.nonce = nonce;
            this.nonceHash = nonceHash;
            this.createdAt = createdAt;
        }

        public UUID callId() { return callId; }
        public UUID tenantId() { return tenantId; }
        public UUID subscriptionId() { return subscriptionId; }
        public IntercomPushEvent.Kind kind() { return kind; }
        public String nonce() { return nonce; }
        public Instant sentAt() { return sentAt; }
        public WebPushService.Outcome outcome() { return outcome; }
        public int httpStatus() { return httpStatus; }
        public int attempts() { return attempts; }
        public Instant receivedAt() { return receivedAt; }
        public Instant displayedAt() { return displayedAt; }
        public Instant clickedAt() { return clickedAt; }
    }

    private static String key(UUID callId, UUID subscriptionId, IntercomPushEvent.Kind kind) {
        return callId + ":" + subscriptionId + ":" + kind;
    }

    /**
     * Reserva la entrega (llamada, suscripcion, tipo). Vacio si ya existia: el envio duplicado se omite.
     */
    public Optional<Delivery> reserve(UUID callId, UUID tenantId, UUID subscriptionId, IntercomPushEvent.Kind kind) {
        if (byKey.size() >= MAX_ENTRIES) purge();
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Delivery d = new Delivery(callId, tenantId, subscriptionId, kind, nonce, hash(nonce), clock.instant());
        Delivery previous = byKey.putIfAbsent(key(callId, subscriptionId, kind), d);
        if (previous != null) {
            return Optional.empty();
        }
        byNonceHash.put(d.nonceHash, d);
        return Optional.of(d);
    }

    public void markSent(Delivery d, WebPushService.PushResult result) {
        d.attempts++;
        d.outcome = result.outcome();
        d.httpStatus = result.httpStatus();
        if (result.outcome() == WebPushService.Outcome.SENT) {
            d.sentAt = clock.instant();
        }
    }

    /** Suscripciones a las que se ENVIO (2xx) el aviso de timbre de esta llamada. */
    public List<UUID> subscriptionsNotifiedOfRinging(UUID callId) {
        return byKey.values().stream()
                .filter(d -> d.callId.equals(callId) && d.kind == IntercomPushEvent.Kind.RINGING
                        && d.outcome == WebPushService.Outcome.SENT)
                .map(Delivery::subscriptionId)
                .distinct()
                .toList();
    }

    /** Diagnostico y pruebas: entregas de una llamada. */
    public List<Delivery> deliveriesFor(UUID callId) {
        return byKey.values().stream().filter(d -> d.callId.equals(callId)).toList();
    }

    /**
     * Recibo del service worker. Devuelve si se registro (solo para pruebas: el endpoint responde igual siempre).
     * Un nonce desconocido, caducado o mal formado no hace nada.
     */
    public boolean recordReceipt(String nonce, Stage stage) {
        if (nonce == null || stage == null || !nonce.matches("[A-Za-z0-9_-]{22}")) return false;
        Delivery d = byNonceHash.get(hash(nonce));
        if (d == null || d.createdAt.plus(RETENTION).isBefore(clock.instant())) return false;
        Instant now = clock.instant();
        boolean first;
        synchronized (d) {
            first = switch (stage) {
                case RECEIVED -> d.receivedAt == null && (d.receivedAt = now) != null;
                case DISPLAYED -> d.displayedAt == null && (d.displayedAt = now) != null;
                case CLICKED -> d.clickedAt == null && (d.clickedAt = now) != null;
            };
        }
        if (first) {
            long afterSentMs = d.sentAt != null ? Duration.between(d.sentAt, now).toMillis() : -1;
            log.info("[IntercomPush] recibo call={} sub={} kind={} stage={} afterSentMs={}",
                    d.callId, d.subscriptionId, d.kind, stage, afterSentMs);
        }
        return first;
    }

    @Scheduled(fixedDelay = 60_000)
    public void purge() {
        Instant cutoff = clock.instant().minus(RETENTION);
        byKey.values().removeIf(d -> d.createdAt.isBefore(cutoff));
        byNonceHash.values().removeIf(d -> d.createdAt.isBefore(cutoff));
        if (byKey.size() >= MAX_ENTRIES) {
            // Tope duro: se descartan las mas antiguas.
            Collection<Delivery> all = byKey.values();
            all.stream().sorted(java.util.Comparator.comparing(dd -> dd.createdAt))
                    .limit(byKey.size() - MAX_ENTRIES / 2)
                    .toList()
                    .forEach(dd -> {
                        byKey.remove(key(dd.callId, dd.subscriptionId, dd.kind));
                        byNonceHash.remove(dd.nonceHash);
                    });
        }
    }

    /** Solo pruebas. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    static String hash(String nonce) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
