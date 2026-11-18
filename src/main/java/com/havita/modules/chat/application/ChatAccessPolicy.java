package com.havita.modules.chat.application;

import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.security.AccessValidator;
import com.havita.security.ResidentUnitResolver;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fase 3 — autorizacion del chat, siempre con el estado vigente en PostgreSQL (nunca con datos del cliente).
 *
 * <ul>
 *   <li><b>Porteria</b>: membresia SECURITY ACTIVE de una cuenta ACTIVE en la comunidad de la peticion. En las
 *       escrituras se bloquea esa membresia (SEC-9, {@link AccessValidator#lockAuthorization}) antes de tocar la
 *       conversacion: una revocacion concurrente espera o hace fallar la operacion.</li>
 *   <li><b>Residente</b>: membresia RESIDENT efectiva en la comunidad y relacion ACTIVE con la unidad de la conversacion
 *       ({@link ResidentUnitResolver#activeUnits}, la misma regla que el resto del portal del residente). Si pierde la
 *       unidad deja de ver ese hilo; la porteria no puede escribirle ni se le notifica.</li>
 * </ul>
 * La comunidad sale de {@code TenantContextHolder} (validada por el filtro de seguridad) y todas las consultas filtran
 * por ella: un id de otra comunidad responde 404.
 */
@Component
@RequiredArgsConstructor
public class ChatAccessPolicy {

    /**
     * Roles globales admitidos por la comprobacion con bloqueo de SEC-9. La comprobacion exige ademas el rol de comunidad
     * (SECURITY o RESIDENT), y los controladores ya lo exigen con @PreAuthorize.
     */
    private static final Set<RoleType> GLOBAL = Set.of(RoleType.SUPER_ADMIN);

    private final AccessValidator accessValidator;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final ResidentUnitResolver unitResolver;
    private final EntityManager em;

    public UUID currentUserId() {
        UUID id = accessValidator.currentUserId();
        if (id == null) throw new AccessDeniedException("Sin usuario autenticado.");
        return id;
    }

    public User currentUser() {
        return users.findById(currentUserId()).orElseThrow(() -> new AccessDeniedException("Cuenta inexistente."));
    }

    // ── Porteria ─────────────────────────────────────────────────────────────

    /** Escrituras de porteria: bloquea la membresia SECURITY (debe ser lo primero de la transaccion). */
    public void lockStaff() {
        accessValidator.lockAuthorization(Set.of(RoleType.SECURITY), GLOBAL);
    }

    /** Lecturas de porteria. */
    public void requireStaff(UUID userId, UUID tenantId) {
        if (!isActiveStaff(userId, tenantId)) {
            throw new AccessDeniedException("No tienes acceso de portería en esta comunidad.");
        }
    }

    public boolean isActiveStaff(UUID userId, UUID tenantId) {
        return hasEffectiveRole(userId, tenantId, RoleType.SECURITY);
    }

    /** Porteros con acceso vigente en la comunidad (destinatarios de la bandeja compartida). */
    public List<UUID> activeStaffIds(UUID tenantId) {
        return em.createQuery("select distinct u.id from Membership m join m.user u where m.tenantId = :t"
                        + " and m.role = :role and m.status = :ms and u.status = :us", UUID.class)
                .setParameter("t", tenantId).setParameter("role", RoleType.SECURITY)
                .setParameter("ms", MembershipStatus.ACTIVE).setParameter("us", UserStatus.ACTIVE)
                .getResultList();
    }

    /**
     * Lectura de la bandeja: porteria (opera) o administracion de la comunidad (solo consulta: no toma, responde,
     * transfiere ni cierra).
     */
    public void requireInboxReader(UUID userId, UUID tenantId) {
        if (!isActiveStaff(userId, tenantId) && !hasEffectiveRole(userId, tenantId, RoleType.COMMUNITY_ADMIN)) {
            throw new AccessDeniedException("No tienes acceso a la bandeja de chat de esta comunidad.");
        }
    }

    /** Destinatarios del tiempo real de la bandeja: porteria y administracion activas de la comunidad. */
    public List<UUID> inboxReaderIds(UUID tenantId) {
        return em.createQuery("select distinct u.id from Membership m join m.user u where m.tenantId = :t"
                        + " and m.role in :roles and m.status = :ms and u.status = :us", UUID.class)
                .setParameter("t", tenantId).setParameter("roles", List.of(RoleType.SECURITY, RoleType.COMMUNITY_ADMIN))
                .setParameter("ms", MembershipStatus.ACTIVE).setParameter("us", UserStatus.ACTIVE)
                .getResultList();
    }

    /** Porteros activos con nombre (destinos de transferencia), ordenados por nombre. */
    public List<User> activeStaff(UUID tenantId) {
        return em.createQuery("select distinct u from Membership m join m.user u where m.tenantId = :t"
                        + " and m.role = :role and m.status = :ms and u.status = :us order by u.fullName", User.class)
                .setParameter("t", tenantId).setParameter("role", RoleType.SECURITY)
                .setParameter("ms", MembershipStatus.ACTIVE).setParameter("us", UserStatus.ACTIVE)
                .getResultList();
    }

    // ── Residente ────────────────────────────────────────────────────────────

    /** Escrituras del residente: bloquea la membresia RESIDENT. */
    public void lockResident() {
        accessValidator.lockAuthorization(Set.of(RoleType.RESIDENT), GLOBAL);
    }

    public void requireResident(UUID userId, UUID tenantId) {
        if (!hasEffectiveRole(userId, tenantId, RoleType.RESIDENT)) {
            throw new AccessDeniedException("No tienes acceso de residente en esta comunidad.");
        }
    }

    /** Unidades con relacion ACTIVE del residente en la comunidad (vacio si no tiene). */
    public Set<UUID> activeUnitIds(User user, UUID tenantId) {
        return unitResolver.activeUnits(user.getId(), user.getEmail(), tenantId).stream()
                .map(ResidentUnitResolver.UnitOption::propertyId)
                .collect(Collectors.toSet());
    }

    /**
     * ¿El residente puede recibir y ver mensajes de ese hilo hoy? Cuenta ACTIVE, membresia RESIDENT efectiva en la
     * comunidad y relacion ACTIVE con la unidad.
     */
    public boolean residentCanUse(User resident, UUID tenantId, UUID propertyId) {
        if (resident == null || resident.getStatus() != UserStatus.ACTIVE) return false;
        if (!hasEffectiveRole(resident.getId(), tenantId, RoleType.RESIDENT)) return false;
        return activeUnitIds(resident, tenantId).contains(propertyId);
    }

    /** Unidad de la peticion del residente (cabecera X-Property-Id; solo elige entre SUS unidades activas). */
    public UUID currentResidentUnit() {
        return unitResolver.resolveCurrent().propertyId();
    }

    private boolean hasEffectiveRole(UUID userId, UUID tenantId, RoleType role) {
        if (userId == null || tenantId == null) return false;
        return memberships.findEffectiveByUserId(userId).stream()
                .anyMatch(m -> tenantId.equals(m.getTenantId()) && m.getRole() == role);
    }
}
