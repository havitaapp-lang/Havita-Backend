package com.havita.modules.pqrs.infrastructure;

import com.havita.modules.pqrs.domain.PqrsRequestStatus;

import com.havita.modules.pqrs.domain.PqrsRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;

@Repository
public interface PqrsRequestRepository extends JpaRepository<PqrsRequest, UUID> {

    @EntityGraph(attributePaths = {"property", "resident", "assignedToUser"})
    List<PqrsRequest> findByTenantId(UUID tenantId);

    @EntityGraph(attributePaths = {"property", "resident", "assignedToUser"})
    Page<PqrsRequest> findByTenantId(UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"property", "resident", "assignedToUser"})
    Optional<PqrsRequest> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"property", "resident", "assignedToUser"})
    List<PqrsRequest> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);

    @EntityGraph(attributePaths = {"property", "resident", "assignedToUser"})
    Page<PqrsRequest> findByTenantIdAndStatus(UUID tenantId, PqrsRequestStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"property", "resident", "assignedToUser"})
    Page<PqrsRequest> findByTenantIdAndCategory(UUID tenantId, String category, Pageable pageable);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, PqrsRequestStatus status);

    @EntityGraph(attributePaths = {"property", "resident", "assignedToUser"})
    @Query("SELECT p FROM PqrsRequest p WHERE p.tenantId = :tenantId AND (" +
           "LOWER(p.title) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<PqrsRequest> searchPqrs(UUID tenantId, String search, Pageable pageable);
}
