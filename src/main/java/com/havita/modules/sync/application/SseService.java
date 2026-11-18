package com.havita.modules.sync.application;

import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.sync.application.SseAccessResolver.SubscriberAccess;
import com.havita.modules.sync.domain.SyncAudience;
import com.havita.modules.sync.domain.SyncEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Conexiones SSE por comunidad y entrega de eventos segun su {@link SyncAudience}.
 *
 * Cada conexion guarda en memoria su rol y sus unidades activas, leidos de PostgreSQL al conectarse, de modo que
 * decidir si un evento le corresponde no consulta la base de datos. Ese permiso se vuelve a leer, como mucho una
 * vez por conexion, cuando ha caducado ({@code havita.sse.revalidate-seconds}) o cuando un evento de cambio de
 * acceso marca la comunidad como pendiente de revalidar. Si la membresia ya no esta ACTIVE se cierra la conexion.
 */
@Service
public class SseService {
    private static final Logger log = LoggerFactory.getLogger(SseService.class);
    private static final long TIMEOUT_MS = 1800000L;

    /** Eventos tras los que pueden haber cambiado membresias o relaciones con unidades de la comunidad. */
    private static final Set<String> ACCESS_CHANGE_EVENTS = Set.of(
            "RESIDENT_ACCESS_REVOKED", "TENANT_RELATION_ENDED", "RESIDENT_RELATION_ENDED",
            "RESIDENT_RELATION_REACTIVATED", "RESIDENT_REGISTERED", "OWNER_PROPERTY_ASSIGNED",
            "PROPERTY_OWNER_TRANSFERRED", "OWNER_ACCESS_CHANGED", "PROPERTY_OCCUPANCY_CHANGED");

    private final SseAccessResolver accessResolver;
    private volatile Duration revalidateAfter;

    // Map: tenantId -> conexiones abiertas de esa comunidad
    private final Map<UUID, CopyOnWriteArrayList<Subscriber>> tenantSubscribers = new ConcurrentHashMap<>();

    public SseService(SseAccessResolver accessResolver,
                      @Value("${havita.sse.revalidate-seconds:60}") long revalidateSeconds) {
        this.accessResolver = accessResolver;
        this.revalidateAfter = Duration.ofSeconds(revalidateSeconds);
    }

    private static final class Subscriber {
        final UUID tenantId;
        final UUID userId;
        final SseEmitter emitter;
        volatile SubscriberAccess access;
        volatile Instant validatedAt;
        volatile boolean stale;

        Subscriber(UUID tenantId, UUID userId, SseEmitter emitter, SubscriberAccess access, Instant validatedAt) {
            this.tenantId = tenantId;
            this.userId = userId;
            this.emitter = emitter;
            this.access = access;
            this.validatedAt = validatedAt;
        }
    }

    /** Abre la conexion si la membresia sigue ACTIVE en PostgreSQL; en caso contrario devuelve vacio. */
    public Optional<SseEmitter> subscribe(UUID tenantId, UUID userId) {
        Optional<SubscriberAccess> access = accessResolver.resolve(tenantId, userId);
        if (access.isEmpty()) {
            return Optional.empty();
        }
        // Timeout 30 minutes
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Subscriber subscriber = new Subscriber(tenantId, userId, emitter, access.get(), Instant.now());
        tenantSubscribers.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>()).add(subscriber);

        emitter.onCompletion(() -> removeSubscriber(subscriber));
        emitter.onTimeout(() -> removeSubscriber(subscriber));
        emitter.onError((e) -> removeSubscriber(subscriber));

        try {
            // Send initial connection event
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of("message", "Connected successfully")));
        } catch (IOException e) {
            removeSubscriber(subscriber);
        }

        return Optional.of(emitter);
    }

    public void publishEvent(SyncEvent event) {
        UUID tenantId = event.tenantId();
        List<Subscriber> subscribers = tenantSubscribers.get(tenantId);

        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        int delivered = 0;
        for (Subscriber subscriber : subscribers) {
            if (!ensureAuthorized(subscriber) || !isRecipient(event.audience(), subscriber)) {
                continue;
            }
            try {
                subscriber.emitter.send(SseEmitter.event()
                        .name(event.type())
                        .data(event));
                delivered++;
            } catch (IOException e) {
                removeSubscriber(subscriber);
            }
        }
        log.info("Published event {} to {}/{} clients in tenant {}", event.type(), delivered, subscribers.size(), tenantId);

        // Se entrega primero (el usuario revocado necesita el evento para cerrar sesion) y luego se revalida.
        if (ACCESS_CHANGE_EVENTS.contains(event.type())) {
            subscribers.forEach(s -> s.stale = true);
        }
    }

    /**
     * SEC-9 (P1): vuelve a comprobar ya, contra PostgreSQL, todas las conexiones abiertas de un usuario, sin esperar al
     * siguiente evento ni al plazo de revalidacion. Se cierran las que perdieron el permiso; las de otras comunidades en
     * las que conserva acceso siguen abiertas (con sus permisos actualizados).
     */
    public void revalidateUser(UUID userId) {
        tenantSubscribers.values().forEach(subscribers -> subscribers.stream()
                .filter(s -> s.userId.equals(userId))
                .forEach(s -> {
                    s.stale = true;
                    ensureAuthorized(s);
                }));
    }

    /** Conexiones abiertas de una comunidad (observabilidad y pruebas). */
    public int subscriberCount(UUID tenantId) {
        List<Subscriber> subscribers = tenantSubscribers.get(tenantId);
        return subscribers == null ? 0 : subscribers.size();
    }

    /** Solo para pruebas: plazo tras el que se vuelven a leer los permisos de una conexion. */
    public void setRevalidateAfter(Duration revalidateAfter) {
        this.revalidateAfter = revalidateAfter;
    }

    private boolean ensureAuthorized(Subscriber subscriber) {
        if (!subscriber.stale && Instant.now().isBefore(subscriber.validatedAt.plus(revalidateAfter))) {
            return true;
        }
        Optional<SubscriberAccess> access = accessResolver.resolve(subscriber.tenantId, subscriber.userId);
        if (access.isEmpty()) {
            log.info("Closing SSE connection of user {} in tenant {}: membership is no longer active",
                    subscriber.userId, subscriber.tenantId);
            removeSubscriber(subscriber);
            try {
                subscriber.emitter.complete();
            } catch (RuntimeException ignored) {
                // La conexion ya estaba cerrada.
            }
            return false;
        }
        subscriber.access = access.get();
        subscriber.validatedAt = Instant.now();
        subscriber.stale = false;
        return true;
    }

    private static boolean isRecipient(SyncAudience audience, Subscriber subscriber) {
        SubscriberAccess access = subscriber.access;
        return switch (audience.scope()) {
            case TENANT -> true;
            case STAFF -> access.isStaff();
            case UNIT -> access.isStaff()
                    || (access.role() == RoleType.RESIDENT
                        && audience.propertyId() != null
                        && access.activeUnits().contains(audience.propertyId())
                        && (audience.userIds().isEmpty() || audience.userIds().contains(subscriber.userId)));
            case RESIDENTS -> access.role() == RoleType.RESIDENT;
            case USERS -> audience.userIds().contains(subscriber.userId);
        };
    }

    private void removeSubscriber(Subscriber subscriber) {
        CopyOnWriteArrayList<Subscriber> subscribers = tenantSubscribers.get(subscriber.tenantId);
        if (subscribers != null) {
            subscribers.remove(subscriber);
        }
    }
}
