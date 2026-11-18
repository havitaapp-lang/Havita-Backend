package com.havita.modules.push.infrastructure;

import com.havita.modules.push.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    List<PushSubscription> findByUserIdAndTenantIdAndActiveTrue(UUID userId, UUID tenantId);

    List<PushSubscription> findByTenantIdAndActiveTrue(UUID tenantId);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    Optional<PushSubscription> findByUserIdAndTenantIdAndEndpoint(UUID userId, UUID tenantId, String endpoint);

    boolean existsByUserIdAndTenantIdAndActiveTrue(UUID userId, UUID tenantId);

    @Modifying
    @Query("UPDATE PushSubscription p SET p.active = false WHERE p.userId = :userId AND p.tenantId = :tenantId")
    void deactivateAllByUserIdAndTenantId(UUID userId, UUID tenantId);

    @Modifying
    @Query("UPDATE PushSubscription p SET p.active = false WHERE p.userId = :userId")
    void deactivateAllByUserId(UUID userId);

    /** P8-B: suscripciones activas de un usuario en todas sus comunidades (revocacion por perdida de acceso). */
    List<PushSubscription> findByUserIdAndActiveTrue(UUID userId);

    /**
     * P8-B (fuga D3): suscripciones activas de los residentes con relacion {@code status} con la unidad, en esa
     * comunidad. La unidad la decide el servidor (la de la notificacion), nunca el cliente.
     */
    @Query("SELECT s FROM PushSubscription s WHERE s.active = true AND s.tenantId = :tenantId AND s.userId IN ("
            + " SELECT r.user.id FROM Resident r WHERE r.tenantId = :tenantId AND r.property.id = :propertyId"
            + " AND r.status = :status AND r.user IS NOT NULL)")
    List<PushSubscription> findActiveForActiveResidentsOfProperty(@Param("tenantId") UUID tenantId,
                                                                  @Param("propertyId") UUID propertyId,
                                                                  @Param("status") com.havita.modules.resident.domain.ResidentStatus status);

    /**
     * Avisos de la comunidad (comunicados, asambleas): suscripciones activas de los residentes con relacion
     * {@code status} con alguna unidad de la comunidad. {@code type} vacio = todos; si no, solo ese tipo de relacion
     * (p. ej. "propietario", "arrendatario"; en minusculas). Nunca porteria ni administracion.
     */
    @Query("SELECT s FROM PushSubscription s WHERE s.active = true AND s.tenantId = :tenantId AND s.userId IN ("
            + " SELECT r.user.id FROM Resident r WHERE r.tenantId = :tenantId AND r.status = :status AND r.user IS NOT NULL"
            + " AND (:type = '' OR lower(r.type) = :type))")
    List<PushSubscription> findActiveForCommunityResidents(@Param("tenantId") UUID tenantId,
                                                           @Param("status") com.havita.modules.resident.domain.ResidentStatus status,
                                                           @Param("type") String type);

    long countByUserIdAndTenantIdAndActiveTrue(UUID userId, UUID tenantId);
}
