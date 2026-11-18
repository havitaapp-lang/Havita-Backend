package com.havita.modules.staff.infrastructure;

import com.havita.modules.staff.domain.ShiftStatus;

import com.havita.modules.staff.domain.Shift;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    List<Shift> findByTenantId(UUID tenantId);

    Page<Shift> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Shift> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Shift> findByTenantIdAndShiftDate(UUID tenantId, LocalDate shiftDate);

    List<Shift> findByTenantIdAndStaffMemberId(UUID tenantId, UUID staffMemberId);

    List<Shift> findByTenantIdAndStaffMemberIdAndShiftDate(UUID tenantId, UUID staffMemberId, LocalDate shiftDate);

    long countByTenantIdAndShiftDate(UUID tenantId, LocalDate shiftDate);

    long countByTenantIdAndShiftDateAndStatus(UUID tenantId, LocalDate shiftDate, ShiftStatus status);
}
