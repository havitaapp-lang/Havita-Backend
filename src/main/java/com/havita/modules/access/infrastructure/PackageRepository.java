package com.havita.modules.access.infrastructure;

import com.havita.modules.access.domain.PackageStatus;

import com.havita.modules.access.domain.Package;
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
public interface PackageRepository extends JpaRepository<Package, UUID> {

    @EntityGraph(attributePaths = {"property", "securityGuard"})
    List<Package> findByTenantId(UUID tenantId);

    @EntityGraph(attributePaths = {"property", "securityGuard"})
    Page<Package> findByTenantId(UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"property", "securityGuard"})
    Optional<Package> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"property", "securityGuard"})
    List<Package> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);

    @EntityGraph(attributePaths = {"property", "securityGuard"})
    Page<Package> findByTenantIdAndStatus(UUID tenantId, PackageStatus status, Pageable pageable);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, PackageStatus status);

    @Query("SELECT p FROM Package p WHERE p.tenantId = :tenantId AND (" +
           "LOWER(p.recipientName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.trackingNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.company) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Package> searchPackages(UUID tenantId, String search, Pageable pageable);
}
