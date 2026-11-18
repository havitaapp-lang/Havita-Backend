package com.havita.modules.staff.infrastructure;

import com.havita.modules.staff.domain.StaffMemberStatus;

import com.havita.modules.staff.domain.StaffMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffMemberRepository extends JpaRepository<StaffMember, UUID> {

    List<StaffMember> findByTenantId(UUID tenantId);

    Page<StaffMember> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<StaffMember> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<StaffMember> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    boolean existsByTenantIdAndDocumentNumber(UUID tenantId, String documentNumber);

    Page<StaffMember> findByTenantIdAndArea(UUID tenantId, String area, Pageable pageable);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, StaffMemberStatus status);

    @Query("SELECT s FROM StaffMember s WHERE s.tenantId = :tenantId AND (" +
           "LOWER(s.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(s.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(s.documentNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(s.position) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<StaffMember> searchStaffMembers(UUID tenantId, String search, Pageable pageable);

    List<StaffMember> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
