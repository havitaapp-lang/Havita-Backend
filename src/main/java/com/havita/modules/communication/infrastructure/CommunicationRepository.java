package com.havita.modules.communication.infrastructure;

import com.havita.modules.communication.domain.CommunicationStatus;

import com.havita.modules.communication.domain.Communication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommunicationRepository extends JpaRepository<Communication, UUID> {

    List<Communication> findByTenantId(UUID tenantId);

    Page<Communication> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Communication> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<Communication> findByTenantIdAndStatus(UUID tenantId, CommunicationStatus status, Pageable pageable);

    @Query("SELECT c FROM Communication c WHERE c.tenantId = :tenantId AND " +
           "c.status = com.havita.modules.communication.domain.CommunicationStatus.PUBLISHED AND (c.audience = 'Todos' OR c.audience = :audience)")
    Page<Communication> findPublishedForAudience(UUID tenantId, String audience, Pageable pageable);

    @Query("SELECT c FROM Communication c WHERE c.tenantId = :tenantId AND (" +
           "LOWER(c.title) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(c.content) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Communication> searchCommunications(UUID tenantId, String search, Pageable pageable);

    long countByTenantId(UUID tenantId);
}
