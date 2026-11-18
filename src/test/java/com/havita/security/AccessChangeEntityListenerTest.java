package com.havita.security;

import com.havita.modules.identity.domain.AccessChangedEvent;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.identity.infrastructure.AccessChangeEntityListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** SEC-9 (P2, revision de P1): que cambios de persistencia disparan la revocacion. */
class AccessChangeEntityListenerTest {

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AccessChangeEntityListener listener = new AccessChangeEntityListener(events);

    private User user(UserStatus status) {
        User u = User.builder().email("x@example.test").passwordHash("-").fullName("X").status(status).build();
        u.setId(UUID.randomUUID());
        return u;
    }

    @Test
    @DisplayName("borrar una cuenta ACTIVE publica la revocacion (antes no publicaba nada)")
    void removingActiveUserPublishes() {
        User u = user(UserStatus.ACTIVE);
        listener.onRemove(u);
        verify(events).publishEvent(new AccessChangedEvent(u.getId()));
    }

    @Test
    @DisplayName("actualizar una cuenta que sigue ACTIVE no publica; desactivarla si")
    void updatingUserPublishesOnlyWhenNoLongerActive() {
        listener.onChange(user(UserStatus.ACTIVE));
        verify(events, never()).publishEvent(any(Object.class));
        User inactive = user(UserStatus.INACTIVE);
        listener.onChange(inactive);
        verify(events).publishEvent(new AccessChangedEvent(inactive.getId()));
    }

    @Test
    @DisplayName("modificar o borrar una membresia publica la revocacion de su usuario")
    void membershipChangesPublish() {
        User u = user(UserStatus.ACTIVE);
        Membership m = Membership.builder().user(u).tenantId(UUID.randomUUID()).role(RoleType.STAFF).build();
        listener.onChange(m);
        listener.onRemove(m);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(new AccessChangedEvent(u.getId()));
    }
}
