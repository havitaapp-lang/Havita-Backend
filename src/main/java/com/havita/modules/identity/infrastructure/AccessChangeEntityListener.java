package com.havita.modules.identity.infrastructure;

import com.havita.modules.identity.domain.AccessChangedEvent;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.domain.UserStatus;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * SEC-9 (P1): detecta en la capa de persistencia cualquier cambio que pueda reducir permisos, venga del controlador
 * que venga: membresia modificada o eliminada, o cuenta que deja de estar ACTIVE. Solo publica el evento; la decision
 * (revocar sesiones, cerrar SSE) se toma tras el commit con el estado final.
 */
@Component
public class AccessChangeEntityListener {

    private final ApplicationEventPublisher events;

    public AccessChangeEntityListener(ApplicationEventPublisher events) {
        this.events = events;
    }

    @PostUpdate
    public void onChange(Object entity) {
        if (entity instanceof Membership m && m.getUser() != null) {
            events.publishEvent(new AccessChangedEvent(m.getUser().getId()));
        } else if (entity instanceof User u && u.getStatus() != UserStatus.ACTIVE) {
            events.publishEvent(new AccessChangedEvent(u.getId()));
        }
    }

    /**
     * SEC-9 (P2, revision de P1): una cuenta eliminada pierde todo acceso aunque su estado en memoria siga ACTIVE.
     * Antes {@code @PostRemove} compartia la condicion de {@code @PostUpdate} y no publicaba nada al borrar una cuenta
     * ACTIVE: sus conexiones SSE seguian abiertas hasta la revalidacion periodica (60 s).
     */
    @PostRemove
    public void onRemove(Object entity) {
        if (entity instanceof Membership m && m.getUser() != null) {
            events.publishEvent(new AccessChangedEvent(m.getUser().getId()));
        } else if (entity instanceof User u && u.getId() != null) {
            events.publishEvent(new AccessChangedEvent(u.getId()));
        }
    }
}
