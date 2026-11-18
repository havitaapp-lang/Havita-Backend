package com.havita.modules.access.infrastructure;

import com.havita.modules.access.domain.VisitorStatus;

import com.havita.modules.access.domain.Visitor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;

@Repository
public interface VisitorRepository extends JpaRepository<Visitor, UUID> {

    @EntityGraph(attributePaths = {"property", "resident"})
    List<Visitor> findByTenantId(UUID tenantId);

    @EntityGraph(attributePaths = {"property", "resident"})
    Page<Visitor> findByTenantId(UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"property", "resident"})
    Optional<Visitor> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("SELECT v FROM Visitor v LEFT JOIN v.resident r WHERE v.id = :id AND v.tenantId = :tenantId AND (v.createdByUserId = :userId OR (r IS NOT NULL AND r.user.id = :userId))")
    Optional<Visitor> findAccessibleVisitorById(UUID id, UUID tenantId, UUID userId);

    @EntityGraph(attributePaths = {"property", "resident"})
    Optional<Visitor> findByTenantIdAndQrCode(UUID tenantId, String qrCode);
    boolean existsByQrCode(String qrCode);

    @EntityGraph(attributePaths = {"property", "resident"})
    Optional<Visitor> findByQrCode(String qrCode);

    @EntityGraph(attributePaths = {"property", "resident"})
    List<Visitor> findByTenantIdAndGroupId(UUID tenantId, String groupId);

    @EntityGraph(attributePaths = {"property", "resident"})
    List<Visitor> findByGroupId(String groupId);

    @EntityGraph(attributePaths = {"property", "resident"})
    List<Visitor> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);

    @Query("SELECT v FROM Visitor v LEFT JOIN v.resident r WHERE v.tenantId = :tenantId AND v.property.id = :propertyId AND (v.createdByUserId = :userId OR (r IS NOT NULL AND r.user.id = :userId))")
    List<Visitor> findAccessibleVisitors(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId, @org.springframework.data.repository.query.Param("propertyId") UUID propertyId, @org.springframework.data.repository.query.Param("userId") UUID userId);

    @EntityGraph(attributePaths = {"property", "resident"})
    Page<Visitor> findByTenantIdAndStatus(UUID tenantId, VisitorStatus status, Pageable pageable);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, VisitorStatus status);

    @Query("SELECT v FROM Visitor v WHERE v.tenantId = :tenantId AND (" +
           "LOWER(v.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(v.documentId) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(v.vehiclePlate) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Visitor> searchVisitors(UUID tenantId, String search, Pageable pageable);

    @Query("SELECT COUNT(v) FROM Visitor v WHERE v.tenantId = :tenantId AND v.enteredAt >= :startOfDay")
    long countTodayEntries(UUID tenantId, OffsetDateTime startOfDay);

    @Query("SELECT v FROM Visitor v WHERE v.status IN (com.havita.modules.access.domain.VisitorStatus.AUTHORIZED, com.havita.modules.access.domain.VisitorStatus.PENDING) AND ((v.expectedAt IS NOT NULL AND v.expectedAt <= :threshold) OR (v.expectedAt IS NULL AND v.createdAt <= :threshold))")
    List<Visitor> findVisitsToExpire(@org.springframework.data.repository.query.Param("threshold") OffsetDateTime threshold);
}


