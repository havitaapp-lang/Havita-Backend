package com.havita.modules.realtime.infrastructure;

import com.havita.modules.realtime.domain.RealtimeNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RealtimeNotificationRepository extends JpaRepository<RealtimeNotification, UUID> {

    List<RealtimeNotification> findByTenantId(UUID tenantId);

    Page<RealtimeNotification> findByTenantId(UUID tenantId, Pageable pageable);

    Page<RealtimeNotification> findByTenantIdAndUserId(UUID tenantId, UUID userId, Pageable pageable);

    Page<RealtimeNotification> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId, Pageable pageable);

    Optional<RealtimeNotification> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantIdAndUserIdAndIsReadFalse(UUID tenantId, UUID userId);

    long countByTenantIdAndPropertyIdAndIsReadFalse(UUID tenantId, UUID propertyId);
}
