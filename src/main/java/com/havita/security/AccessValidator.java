package com.havita.security;

import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component("accessValidator")
@RequiredArgsConstructor
public class AccessValidator {

    private final ResidentRepository residentRepository;
    private final MembershipRepository membershipRepository;

    public boolean canViewPrivateActivity(UUID propertyId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null || auth.getPrincipal().equals("anonymousUser")) {
            return false;
        }

        if (auth.getAuthorities().stream().anyMatch(a ->
            a.getAuthority().equals("ROLE_SUPER_ADMIN") ||
            a.getAuthority().equals("ROLE_COMMUNITY_ADMIN") ||
            a.getAuthority().equals("ROLE_SECURITY") ||
            a.getAuthority().equals("ROLE_STAFF"))) {
            return true;
        }

        UUID userId = UUID.fromString((String) auth.getPrincipal());
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        List<Resident> allResidentsOfProperty = residentRepository.findByTenantIdAndPropertyId(tenantId, propertyId);

        Resident currentUserRel = allResidentsOfProperty.stream()
                .filter(r -> r.getUser() != null && r.getUser().getId().equals(userId))
                .findFirst()
                .orElse(null);

        if (currentUserRel == null) return false;
        if (!currentUserRel.getType().equalsIgnoreCase("Propietario")) return true;

        boolean hasArrendatarios = allResidentsOfProperty.stream()
                .anyMatch(r -> r.getType().equalsIgnoreCase("Arrendatario"));

        return !hasArrendatarios;
    }

    /**
     * Un residente solo puede consultar datos de una unidad si, en la comunidad actual, su membresia esta
     * ACTIVE y tiene una relacion ACTIVE con esa unidad. Se comprueba contra la base de datos y no contra
     * el JWT, para que una membresia o relacion revocada deje de dar acceso de inmediato. Unidades
     * inexistentes o de otra comunidad devuelven false igual que las ajenas, sin revelar si existen.
     */
    public boolean isActiveResidentOfProperty(UUID propertyId) {
        UUID userId = currentUserId();
        UUID tenantId = TenantContextHolder.getTenantId();
        if (userId == null || tenantId == null || propertyId == null) {
            return false;
        }
        return activePropertyIds().contains(propertyId);
    }

    /**
     * Unidades en las que el usuario autenticado tiene hoy una relacion ACTIVE dentro de la comunidad actual,
     * con su membresia ACTIVE. Vacio si falta autenticacion, tenant o membresia activa.
     */
    public Set<UUID> activePropertyIds() {
        UUID userId = currentUserId();
        UUID tenantId = TenantContextHolder.getTenantId();
        if (userId == null || tenantId == null) {
            return Set.of();
        }
        boolean activeMembership = membershipRepository.findByUserIdAndTenantId(userId, tenantId)
                .map(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElse(false);
        if (!activeMembership) {
            return Set.of();
        }
        return residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getStatus() == ResidentStatus.ACTIVE && r.getProperty() != null)
                .map(r -> r.getProperty().getId())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * true si la peticion se autentico con el rol RESIDENT. El filtro JWT asigna un unico rol, el de la
     * membresia de la comunidad actual, asi que un RESIDENT no tiene a la vez un rol administrativo.
     */
    public boolean isResidentCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_RESIDENT".equals(a.getAuthority()));
    }

    /** Igual que {@link #isActiveResidentOfProperty} para la unidad a la que pertenece un registro del censo. */
    public boolean canReadResident(UUID residentId) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (residentId == null || tenantId == null) {
            return false;
        }
        return residentRepository.findByIdAndTenantId(residentId, tenantId)
                .map(r -> isActiveResidentOfProperty(r.getProperty().getId()))
                .orElse(false);
    }

    /** Id del usuario autenticado (subject del JWT), o null si no hay una autenticacion valida. */
    /**
     * SEC-8: administracion de la comunidad actual comprobada contra la base, no solo con el rol del JWT (que puede
     * seguir vigente horas despues de degradar o desactivar a alguien): membresia ACTIVE como COMMUNITY_ADMIN de
     * esta comunidad, o SUPER_ADMIN ACTIVE global (sin comunidad) o de esta comunidad.
     */
    public boolean isCommunityAdministrator() {
        UUID userId = currentUserId();
        UUID tenantId = TenantContextHolder.getTenantId();
        if (userId == null || tenantId == null) {
            return false;
        }
        // SEC-9: el filtro ya resolvio contra PostgreSQL los permisos de esta peticion; se reutilizan sin repetir la consulta.
        var resolved = AuthorizationScope.current().filter(x -> userId.equals(x.userId()));
        if (resolved.isPresent()) {
            EffectiveAccess x = resolved.get();
            return (x.role() == RoleType.COMMUNITY_ADMIN && tenantId.equals(x.tenantId()))
                    || (x.role() == RoleType.SUPER_ADMIN && (x.global() || tenantId.equals(x.tenantId())));
        }
        return membershipRepository.findByUserId(userId).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .anyMatch(m -> (m.getRole() == RoleType.COMMUNITY_ADMIN && tenantId.equals(m.getTenantId()))
                        || (m.getRole() == RoleType.SUPER_ADMIN && (m.getTenantId() == null || tenantId.equals(m.getTenantId()))));
    }

    /**
     * SEC-9 (P1): segunda comprobacion de la administracion de la comunidad DENTRO de la transaccion de una operacion
     * critica, bloqueando (FOR SHARE) la membresia que la concede. Debe ser lo primero que haga la transaccion, para
     * que el orden de bloqueos sea siempre: membresia del llamante, despues filas de negocio.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockCommunityAdministrator() {
        lockAuthorization(Set.of(RoleType.COMMUNITY_ADMIN, RoleType.SUPER_ADMIN), Set.of(RoleType.SUPER_ADMIN));
    }

    /**
     * Como {@link #lockCommunityAdministrator()} con los roles de comunidad indicados (ademas de la administracion) y,
     * fuera de la comunidad, los roles globales indicados. Rechaza con 403 si el permiso ya no existe al bloquear.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockAuthorization(Set<RoleType> communityRoles, Set<RoleType> globalRoles) {
        UUID userId = currentUserId();
        UUID tenantId = TenantContextHolder.getTenantId();
        if (userId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Sin usuario autenticado.");
        }
        membershipRepository.setLocalLockTimeout(LOCK_TIMEOUT);
        List<String> rows = tenantId == null
                ? membershipRepository.lockGlobalMembership(userId, names(globalRoles))
                : membershipRepository.lockAuthorizingMembership(userId, tenantId, names(communityRoles), names(globalRoles));
        if (rows.isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException("Tus permisos cambiaron; la operacion no se realizo.");
        }
    }

    /** Operaciones exclusivas de la administracion global (sin comunidad). */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockGlobalAuthorization(Set<RoleType> globalRoles) {
        UUID userId = currentUserId();
        if (userId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Sin usuario autenticado.");
        }
        membershipRepository.setLocalLockTimeout(LOCK_TIMEOUT);
        if (membershipRepository.lockGlobalMembership(userId, names(globalRoles)).isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException("Tus permisos cambiaron; la operacion no se realizo.");
        }
    }

    /** Espera maxima por un bloqueo de permisos: evita transacciones colgadas ante una revocacion larga. */
    private static final String LOCK_TIMEOUT = "5s";

    private static List<String> names(Set<RoleType> roles) {
        // IN () vacio no es SQL valido: un valor imposible deja la rama sin coincidencias.
        return roles.isEmpty() ? List.of("__NONE__") : roles.stream().map(Enum::name).toList();
    }

    public void requireCommunityAdministrator() {
        if (!isCommunityAdministrator()) {
            throw new org.springframework.security.access.AccessDeniedException("Solo la administracion de la comunidad puede realizar esta operacion.");
        }
    }

    public UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String principal)) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
