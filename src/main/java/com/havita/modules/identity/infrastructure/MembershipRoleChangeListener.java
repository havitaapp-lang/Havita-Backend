package com.havita.modules.identity.infrastructure;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipRoleChangedEvent;
import com.havita.modules.identity.domain.MembershipRoleChangedEvent.Kind;
import com.havita.modules.identity.domain.RoleType;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.action.spi.BeforeTransactionCompletionProcess;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Cierre SEC-9 (R3): detecta en Hibernate cualquier asignacion, cambio o retiro del rol de una membresia, sea cual sea
 * el controlador o servicio que la haga, con el valor anterior real (estado cargado de la base, no el que diga el
 * cliente). El evento se publica antes de completar la transaccion (igual que Envers) para que la auditoria se guarde
 * en la misma transaccion: si el cambio se revierte, no queda auditoria; si la auditoria falla, el cambio no se aplica.
 * Las actualizaciones masivas por JPQL no pasan por aqui; hoy no existe ninguna sobre memberships.
 */
@Component
public class MembershipRoleChangeListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private final EntityManagerFactory entityManagerFactory;
    private final ApplicationEventPublisher events;

    public MembershipRoleChangeListener(EntityManagerFactory entityManagerFactory, ApplicationEventPublisher events) {
        this.entityManagerFactory = entityManagerFactory;
        this.events = events;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        if (event.getEntity() instanceof Membership m) {
            schedule(event.getSession(), new MembershipRoleChangedEvent(Kind.ASSIGNED, m.getId(), userId(m),
                    null, m.getTenantId(), null, m.getRole()));
        }
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        if (!(event.getEntity() instanceof Membership m)) {
            return;
        }
        Object[] old = event.getOldState();
        RoleType previousRole = old == null ? null : (RoleType) old[index(event.getPersister(), "role")];
        UUID previousTenant = old == null ? null : (UUID) old[index(event.getPersister(), "tenantId")];
        // Sin estado anterior (no deberia ocurrir con entidades gestionadas) se audita igualmente, sin rol previo.
        if (old != null && previousRole == m.getRole() && Objects.equals(previousTenant, m.getTenantId())) {
            return; // cambio de estado u otro campo: no es un cambio de rol
        }
        schedule(event.getSession(), new MembershipRoleChangedEvent(Kind.CHANGED, m.getId(), userId(m),
                previousTenant, m.getTenantId(), previousRole, m.getRole()));
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        if (event.getEntity() instanceof Membership m) {
            schedule(event.getSession(), new MembershipRoleChangedEvent(Kind.REMOVED, m.getId(), userId(m),
                    m.getTenantId(), m.getTenantId(), m.getRole(), null));
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void schedule(EventSource session, MembershipRoleChangedEvent change) {
        session.getActionQueue().registerProcess((BeforeTransactionCompletionProcess) s -> {
            events.publishEvent(change);
            s.flush(); // escribe la auditoria creada por los listeners antes del commit
        });
    }

    private static UUID userId(Membership m) {
        return m.getUser() != null ? m.getUser().getId() : null;
    }

    private static int index(EntityPersister persister, String property) {
        String[] names = persister.getPropertyNames();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(property)) {
                return i;
            }
        }
        throw new IllegalStateException("Propiedad no mapeada en Membership: " + property);
    }
}
