package com.havita.modules.audit.infrastructure;

import com.havita.modules.audit.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByTenantId(UUID tenantId, Pageable pageable);

    Page<AuditEvent> findByAction(String action, Pageable pageable);

    @Query("SELECT a FROM AuditEvent a WHERE " +
           "(:tenantId IS NULL OR a.tenantId = :tenantId) AND (" +
           "LOWER(a.actorName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(a.actorEmail) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(a.action) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(a.resource) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<AuditEvent> searchAuditEvents(UUID tenantId, String search, Pageable pageable);
}
