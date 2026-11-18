package com.havita.modules.staff.infrastructure;

import com.havita.modules.staff.domain.AttendanceRecordStatus;

import com.havita.modules.staff.domain.AttendanceRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    List<AttendanceRecord> findByTenantId(UUID tenantId);

    Page<AttendanceRecord> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<AttendanceRecord> findByIdAndTenantId(UUID id, UUID tenantId);

    List<AttendanceRecord> findByTenantIdAndRecordDate(UUID tenantId, LocalDate recordDate);

    List<AttendanceRecord> findByTenantIdAndStaffMemberId(UUID tenantId, UUID staffMemberId);

    Page<AttendanceRecord> findByTenantIdAndStaffMemberId(UUID tenantId, UUID staffMemberId, Pageable pageable);

    Optional<AttendanceRecord> findByTenantIdAndStaffMemberIdAndRecordDate(UUID tenantId, UUID staffMemberId, LocalDate recordDate);

    long countByTenantIdAndRecordDateAndStatus(UUID tenantId, LocalDate recordDate, AttendanceRecordStatus status);
}
