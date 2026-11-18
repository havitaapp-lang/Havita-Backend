package com.havita.modules.identity.infrastructure;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findByUserId(UUID userId);
    Optional<Membership> findByUserIdAndTenantId(UUID userId, UUID tenantId);
    List<Membership> findByTenantId(UUID tenantId);
    List<Membership> findByTenantIdAndRole(UUID tenantId, RoleType role);
    long countByTenantId(UUID tenantId);

    /**
     * SEC-9: membresias que hoy conceden permisos: membresia ACTIVE de un usuario ACTIVE. Una sola consulta por el
     * indice (user_id, tenant_id); un usuario inactivo devuelve vacio.
     *
     * SEC-9 (P2, SEC-9-6): una membresia de comunidad solo cuenta si la comunidad existe. El esquema generado por
     * Hibernate no tiene clave foranea memberships.tenant_id -> tenants, asi que una membresia huerfana (comunidad
     * inexistente) es posible; se ignora sin modificarla ni borrarla.
     */
    @Query("select m from Membership m join m.user u where u.id = :userId"
            + " and m.status = com.havita.modules.identity.domain.MembershipStatus.ACTIVE"
            + " and u.status = com.havita.modules.identity.domain.UserStatus.ACTIVE"
            + " and (m.tenantId is null or exists (select t.id from Tenant t where t.id = m.tenantId))")
    List<Membership> findEffectiveByUserId(@Param("userId") UUID userId);

    /**
     * SEC-9 (P1): segunda comprobacion dentro de la transaccion de una operacion critica. Bloquea (FOR SHARE) la
     * membresia y la cuenta que conceden el permiso: una revocacion concurrente (UPDATE o DELETE de esas filas) espera
     * a que termine la operacion, y si se confirmo antes, la fila ya no cumple el filtro y la operacion se rechaza.
     */
    @Query(value = "select cast(m.id as varchar) from memberships m join users u on u.id = m.user_id"
            + " where m.user_id = :userId and m.status = 'ACTIVE' and u.status = 'ACTIVE'"
            + " and ((m.tenant_id = :tenantId and m.role in (:communityRoles)"
            + " and exists (select 1 from tenants t where t.id = m.tenant_id))"
            + " or (m.tenant_id is null and m.role in (:globalRoles)))"
            + " for share", nativeQuery = true)
    List<String> lockAuthorizingMembership(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId,
                                           @Param("communityRoles") java.util.Collection<String> communityRoles,
                                           @Param("globalRoles") java.util.Collection<String> globalRoles);

    /** Variante sin comunidad: solo una membresia global (sin comunidad) concede el permiso. */
    @Query(value = "select cast(m.id as varchar) from memberships m join users u on u.id = m.user_id"
            + " where m.user_id = :userId and m.status = 'ACTIVE' and u.status = 'ACTIVE'"
            + " and m.tenant_id is null and m.role in (:globalRoles)"
            + " for share", nativeQuery = true)
    List<String> lockGlobalMembership(@Param("userId") UUID userId, @Param("globalRoles") java.util.Collection<String> globalRoles);

    /** Limita la espera por bloqueos de la transaccion actual (SET LOCAL). */
    @Query(value = "select set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("timeout") String timeout);
}
