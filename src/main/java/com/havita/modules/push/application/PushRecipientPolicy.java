package com.havita.modules.push.application;

import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.push.domain.PushSubscription;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * P8-B: quien puede recibir un push de una comunidad. Se decide en el servidor, en el momento de enviar, con el estado
 * vigente en PostgreSQL (nunca con datos del cliente).
 *
 * <ul>
 *   <li>La cuenta debe estar ACTIVE y tener una membresia ACTIVE en ESA comunidad
 *       ({@link MembershipRepository#findEffectiveByUserId}, la misma regla que SEC-9).</li>
 *   <li>Las suscripciones de quien ya no cumple se DESACTIVAN al detectarlo (defensa en profundidad frente a una
 *       revocacion que no llego a ejecutarse, p. ej. un cierre de sesion sin conexion).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushRecipientPolicy {

    private final MembershipRepository membershipRepository;
    private final PushSubscriptionRepository subscriptionRepository;

    /** ¿El usuario tiene hoy acceso efectivo a la comunidad? */
    public boolean hasActiveAccess(UUID userId, UUID tenantId) {
        if (userId == null || tenantId == null) return false;
        return membershipRepository.findEffectiveByUserId(userId).stream()
                .anyMatch(m -> tenantId.equals(m.getTenantId()));
    }

    /**
     * Filtra suscripciones activas: conserva las de usuarios con acceso efectivo a su comunidad y desactiva el resto.
     * Una consulta de membresias por usuario distinto.
     */
    public List<PushSubscription> eligible(List<PushSubscription> subscriptions) {
        if (subscriptions.isEmpty()) return subscriptions;
        Map<UUID, Set<UUID>> tenantsByUser = subscriptions.stream()
                .map(PushSubscription::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(Function.identity(), u -> membershipRepository.findEffectiveByUserId(u).stream()
                        .map(m -> m.getTenantId())
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet())));
        List<PushSubscription> keep = new java.util.ArrayList<>();
        for (PushSubscription s : subscriptions) {
            Set<UUID> tenants = tenantsByUser.getOrDefault(s.getUserId(), Set.of());
            if (tenants.contains(s.getTenantId())) {
                keep.add(s);
            } else {
                s.deactivate();
                subscriptionRepository.save(s);
                log.info("[Push] Suscripcion id={} desactivada: el usuario ya no tiene acceso a la comunidad", s.getId());
            }
        }
        return keep;
    }
}
