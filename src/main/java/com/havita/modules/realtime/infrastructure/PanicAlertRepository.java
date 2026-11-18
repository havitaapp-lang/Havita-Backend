package com.havita.modules.realtime.infrastructure;

import com.havita.modules.realtime.domain.PanicAlertStatus;

import com.havita.modules.realtime.domain.PanicAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PanicAlertRepository extends JpaRepository<PanicAlert, UUID> {

    List<PanicAlert> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Page<PanicAlert> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    List<PanicAlert> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, PanicAlertStatus status);

    List<PanicAlert> findByTenantIdAndStatusInOrderByCreatedAtDesc(UUID tenantId, List<PanicAlertStatus> statuses);

    Optional<PanicAlert> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, PanicAlertStatus status);

    @org.springframework.data.jpa.repository.Query("SELECT p FROM PanicAlert p WHERE p.tenantId = :tenantId AND p.resident.id = :residentId ORDER BY p.createdAt DESC")
    List<PanicAlert> findByTenantIdAndResidentIdOrderByCreatedAtDesc(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("residentId") UUID residentId);

    /** Fase 2 / E1: alertas del residente en todas sus unidades ACTIVE. */
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PanicAlert p WHERE p.tenantId = :tenantId AND p.resident.id IN :residentIds ORDER BY p.createdAt DESC")
    List<PanicAlert> findByTenantIdAndResidentIdInOrderByCreatedAtDesc(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("residentIds") java.util.Collection<UUID> residentIds);
}
