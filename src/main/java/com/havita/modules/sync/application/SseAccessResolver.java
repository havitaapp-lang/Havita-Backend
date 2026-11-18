package com.havita.modules.sync.application;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Permisos vigentes de una conexion SSE, leidos de PostgreSQL (no del JWT): rol de la membresia ACTIVE en la
 * comunidad y unidades con relacion ACTIVE. Vacio si la membresia no existe o no esta ACTIVE.
 * Se ejecuta en una transaccion propia porque tambien se invoca desde listeners AFTER_COMMIT.
 */
@Component
@RequiredArgsConstructor
public class SseAccessResolver {

    private static final Set<RoleType> GLOBAL_ROLES = EnumSet.of(RoleType.SUPER_ADMIN, RoleType.PLATFORM_ADMIN);
    static final Set<RoleType> STAFF_ROLES = EnumSet.of(
            RoleType.SUPER_ADMIN, RoleType.PLATFORM_ADMIN, RoleType.COMMUNITY_ADMIN, RoleType.SECURITY, RoleType.STAFF);

    private final MembershipRepository membershipRepository;
    private final ResidentRepository residentRepository;

    public record SubscriberAccess(RoleType role, Set<UUID> activeUnits) {
        public boolean isStaff() {
            return STAFF_ROLES.contains(role);
        }
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<SubscriberAccess> resolve(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            return Optional.empty();
        }
        // SEC-9: solo membresias ACTIVE de una cuenta ACTIVE (misma regla que la autorizacion de cada peticion).
        List<Membership> memberships = membershipRepository.findEffectiveByUserId(userId);
        Optional<Membership> global = memberships.stream()
                // SEC-9: solo una membresia global (sin comunidad) concede alcance global.
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE && m.getTenantId() == null && GLOBAL_ROLES.contains(m.getRole()))
                .findFirst();
        if (global.isPresent()) {
            return Optional.of(new SubscriberAccess(global.get().getRole(), Set.of()));
        }
        Optional<Membership> local = memberships.stream()
                .filter(m -> tenantId.equals(m.getTenantId()) && m.getStatus() == MembershipStatus.ACTIVE)
                .findFirst();
        if (local.isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> units = residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getStatus() == ResidentStatus.ACTIVE && r.getProperty() != null)
                .map(r -> r.getProperty().getId())
                .collect(Collectors.toUnmodifiableSet());
        // Un RESIDENT sin ninguna unidad activa ya no pertenece a la comunidad (el login lo rechaza): se cierra.
        if (local.get().getRole() == RoleType.RESIDENT && units.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SubscriberAccess(local.get().getRole(), units));
    }
}
