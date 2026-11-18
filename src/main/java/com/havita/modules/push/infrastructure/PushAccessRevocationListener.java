package com.havita.modules.push.infrastructure;

import com.havita.modules.identity.domain.AccessChangedEvent;
import com.havita.modules.identity.domain.UserSessionsRevokedEvent;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.push.domain.PushSubscription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P8-B: revoca las suscripciones push cuando el usuario pierde el acceso.
 *
 * <ul>
 *   <li>{@link AccessChangedEvent} (SEC-9): membresia modificada o eliminada, o cuenta que deja de estar ACTIVE. Se
 *       desactivan las suscripciones de las comunidades en las que ya no tiene membresia efectiva; las de otras
 *       comunidades en las que conserva acceso siguen.</li>
 *   <li>{@link UserSessionsRevokedEvent}: cierre de sesion en el servidor (revoca todos sus refresh tokens): se desactivan
 *       todas. El dispositivo que vuelva a iniciar sesion se vuelve a registrar.</li>
 * </ul>
 * Se ejecuta tras el commit y en su propia transaccion, como {@code AccessRevocationService}. Ademas, el envio vuelve a
 * comprobar el acceso ({@code PushRecipientPolicy}) por si esta revocacion no llegara a ejecutarse.
 */
@Slf4j
@Component
public class PushAccessRevocationListener {

    private final PushSubscriptionRepository subscriptions;
    private final MembershipRepository memberships;
    private final TransactionTemplate newTransaction;

    public PushAccessRevocationListener(PushSubscriptionRepository subscriptions, MembershipRepository memberships,
                                        PlatformTransactionManager txManager) {
        this.subscriptions = subscriptions;
        this.memberships = memberships;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccessChanged(AccessChangedEvent event) {
        try {
            Integer revoked = newTransaction.execute(status -> {
                Set<java.util.UUID> tenants = memberships.findEffectiveByUserId(event.userId()).stream()
                        .map(m -> m.getTenantId()).filter(Objects::nonNull).collect(Collectors.toSet());
                int n = 0;
                for (PushSubscription s : subscriptions.findByUserIdAndActiveTrue(event.userId())) {
                    if (!tenants.contains(s.getTenantId())) {
                        s.deactivate();
                        subscriptions.save(s);
                        n++;
                    }
                }
                return n;
            });
            if (revoked != null && revoked > 0) {
                log.info("[Push] {} suscripcion(es) del usuario {} desactivadas por perdida de acceso", revoked, event.userId());
            }
        } catch (Exception e) {
            // Nunca rompe el flujo de negocio; el envio vuelve a filtrar por acceso vigente.
            log.warn("[Push] No se pudieron revocar suscripciones del usuario {}: {}", event.userId(), e.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSessionsRevoked(UserSessionsRevokedEvent event) {
        try {
            newTransaction.executeWithoutResult(status -> subscriptions.deactivateAllByUserId(event.userId()));
            log.info("[Push] Suscripciones del usuario {} desactivadas: sesiones revocadas", event.userId());
        } catch (Exception e) {
            log.warn("[Push] No se pudieron desactivar las suscripciones del usuario {}: {}", event.userId(), e.getClass().getSimpleName());
        }
    }
}
