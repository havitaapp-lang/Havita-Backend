package com.havita.security;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.organization.infrastructure.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SEC-9: decide en cada peticion el rol y el alcance a partir del estado actual en PostgreSQL. El JWT solo aporta la
 * identidad ({@code sub}); sus membresias no se usan para autorizar, asi que revocar, desactivar o degradar a alguien
 * surte efecto en su siguiente peticion. Sin cache entre peticiones: una consulta por indice
 * ({@code uq_user_tenant_membership}) que ya filtra usuario y membresias ACTIVE.
 *
 * Precedencia (igual que antes de SEC-9): una membresia global valida (sin comunidad) prevalece; si no, la membresia
 * de la comunidad pedida; sin comunidad pedida, la primera membresia de comunidad.
 */
@Component
@RequiredArgsConstructor
public class AuthorizationResolver {

    static final Set<RoleType> GLOBAL_ROLES = Set.of(RoleType.SUPER_ADMIN, RoleType.PLATFORM_ADMIN, RoleType.READ_ONLY);
    private static final List<RoleType> GLOBAL_PRIORITY = List.of(RoleType.SUPER_ADMIN, RoleType.PLATFORM_ADMIN, RoleType.READ_ONLY);

    private final MembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;

    /**
     * SEC-9 (P2, SEC-9-6): ninguna resolucion apunta a una comunidad inexistente. Las membresias de comunidad ya
     * llegan filtradas por {@code findEffectiveByUserId} (solo comunidades existentes); un rol global que pide una
     * comunidad inexistente en {@code X-Tenant-ID} no obtiene nada (la peticion queda anonima: 401), en lugar de
     * operar sobre ella y saltarse la comprobacion de suspension. Sin {@code X-Tenant-ID} el rol global sigue igual.
     */
    @Transactional(readOnly = true)
    public Optional<EffectiveAccess> resolve(UUID userId, UUID requestedTenantId) {
        if (userId == null) {
            return Optional.empty();
        }
        List<Membership> memberships = membershipRepository.findEffectiveByUserId(userId);

        Optional<Membership> global = memberships.stream()
                .filter(m -> m.getTenantId() == null && GLOBAL_ROLES.contains(m.getRole()))
                .min(Comparator.comparingInt(m -> GLOBAL_PRIORITY.indexOf(m.getRole())));
        if (global.isPresent()) {
            if (requestedTenantId != null && !tenantRepository.existsById(requestedTenantId)) {
                return Optional.empty();
            }
            return Optional.of(new EffectiveAccess(userId, global.get().getRole(), requestedTenantId, true));
        }
        if (requestedTenantId != null) {
            return memberships.stream()
                    .filter(m -> requestedTenantId.equals(m.getTenantId()))
                    .findFirst()
                    .map(m -> new EffectiveAccess(userId, m.getRole(), requestedTenantId, false));
        }
        return memberships.stream()
                .filter(m -> m.getTenantId() != null)
                .findFirst()
                .map(m -> new EffectiveAccess(userId, m.getRole(), m.getTenantId(), false));
    }
}
