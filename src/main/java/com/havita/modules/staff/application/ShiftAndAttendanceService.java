package com.havita.modules.staff.application;
import com.havita.modules.staff.domain.ShiftStatus;
import com.havita.modules.staff.domain.AttendanceRecordStatus;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.staff.api.dto.AttendanceResponse;
import com.havita.modules.staff.api.dto.CheckInRequest;
import com.havita.modules.staff.api.dto.CreateShiftRequest;
import com.havita.modules.staff.api.dto.ShiftResponse;
import com.havita.modules.staff.api.dto.StaffMemberResponse;
import com.havita.modules.staff.domain.AttendanceRecord;
import com.havita.modules.staff.domain.Shift;
import com.havita.modules.staff.domain.StaffMember;
import com.havita.modules.staff.infrastructure.AttendanceRecordRepository;
import com.havita.modules.staff.infrastructure.ShiftRepository;
import com.havita.modules.staff.infrastructure.StaffMemberRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftAndAttendanceService {

    private final ShiftRepository shiftRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final UserRepository userRepository;

    // --- Turnos (Shifts) ---

    @Transactional(readOnly = true)
    public Page<ShiftResponse> findAllShifts(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return shiftRepository.findByTenantId(tenantId, pageable).map(this::toShiftResponse);
    }

    @Transactional(readOnly = true)
    public List<ShiftResponse> findShiftsByDate(LocalDate date) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return shiftRepository.findByTenantIdAndShiftDate(tenantId, date)
                .stream()
                .map(this::toShiftResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ShiftResponse> findShiftsByStaff(UUID staffMemberId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID actualStaffId = resolveStaffMemberId(tenantId, staffMemberId);
        return shiftRepository.findByTenantIdAndStaffMemberId(tenantId, actualStaffId)
                .stream()
                .map(this::toShiftResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ShiftResponse> findMyShifts() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID currentUserId = getCurrentUserId();
        UUID actualStaffId = resolveStaffMemberId(tenantId, currentUserId);
        return shiftRepository.findByTenantIdAndStaffMemberId(tenantId, actualStaffId)
                .stream()
                .map(this::toShiftResponse)
                .toList();
    }

    @Transactional
    public ShiftResponse createShift(CreateShiftRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        StaffMember staff = staffMemberRepository.findByIdAndTenantId(request.staffMemberId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffMember", request.staffMemberId()));

        Shift shift = Shift.builder()
                .staffMember(staff)
                .shiftDate(request.shiftDate())
                .shiftName(request.shiftName().trim())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .gate(request.gate())
                .shiftType(request.shiftType() != null ? request.shiftType() : "Normal")
                .status(ShiftStatus.SCHEDULED)
                .notes(request.notes())
                .build();
        shift.setTenantId(tenantId);

        shift = shiftRepository.save(shift);
        log.info("Turno programado para el personal {} el día {}: {}", staff.getId(), shift.getShiftDate(), shift.getShiftName());
        return toShiftResponse(shift);
    }

    @Transactional
    public ShiftResponse cancelShift(UUID shiftId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift", shiftId));
        shift.setStatus(ShiftStatus.CANCELLED);
        shift = shiftRepository.save(shift);
        log.info("Turno {} cancelado", shiftId);
        return toShiftResponse(shift);
    }

    @Transactional
    public ShiftResponse reassignShift(UUID shiftId, UUID newStaffMemberId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift", shiftId));
        StaffMember newStaff = staffMemberRepository.findByIdAndTenantId(newStaffMemberId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffMember", newStaffMemberId));
        shift.setStaffMember(newStaff);
        shift = shiftRepository.save(shift);
        log.info("Turno {} reasignado al personal {}", shiftId, newStaff.getId());
        return toShiftResponse(shift);
    }

    @Transactional
    public void deleteShift(UUID shiftId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift", shiftId));
        shiftRepository.delete(shift);
        log.info("Turno {} eliminado", shiftId);
    }

    // --- Asistencia y Marcación (Attendance) ---

    @Transactional(readOnly = true)
    public Page<AttendanceResponse> findAllAttendance(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return attendanceRecordRepository.findByTenantId(tenantId, pageable).map(this::toAttendanceResponse);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> findAttendanceByDate(LocalDate date) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return attendanceRecordRepository.findByTenantIdAndRecordDate(tenantId, date)
                .stream()
                .map(this::toAttendanceResponse)
                .toList();
    }

    @Transactional
    public AttendanceResponse checkIn(CheckInRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        LocalDate today = LocalDate.now();

        UUID staffId = resolveStaffMemberId(tenantId, request.staffMemberId());

        StaffMember staff = staffMemberRepository.findByIdAndTenantId(staffId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffMember", request.staffMemberId()));

        Optional<AttendanceRecord> existing = attendanceRecordRepository
                .findByTenantIdAndStaffMemberIdAndRecordDate(tenantId, staffId, today);
        if (existing.isPresent() && existing.get().getCheckIn() != null) {
            throw new BusinessRuleConflictException(
                    "ALREADY_CHECKED_IN",
                    "El colaborador " + staff.getFullName() + " ya tiene una marcación de entrada registrada hoy.");
        }

        Shift shift = null;
        if (request.shiftId() != null) {
            shift = shiftRepository.findByIdAndTenantId(request.shiftId(), tenantId).orElse(null);
        }

        AttendanceRecord record = existing.orElseGet(() -> AttendanceRecord.builder()
                .staffMember(staff)
                .shift(null)
                .recordDate(today)
                .build());

        record.setTenantId(tenantId);
        record.setShift(shift);
        record.setCheckIn(OffsetDateTime.now());
        record.setScheduledCheckIn(shift != null ? shift.getStartTime() : null);
        record.setScheduledCheckOut(shift != null ? shift.getEndTime() : null);
        record.setGate(request.gate() != null ? request.gate() : (shift != null ? shift.getGate() : staff.getAssignedGate()));
        record.setStatus(AttendanceRecordStatus.PRESENT);
        record.setObservations(request.observations());

        record = attendanceRecordRepository.save(record);

        if (shift != null) {
            shift.setStatus(ShiftStatus.IN_PROGRESS);
            shiftRepository.save(shift);
        }

        log.info("Entrada registrada para el personal {}: {}", staff.getId(), record.getCheckIn());
        return toAttendanceResponse(record);
    }

    @Transactional
    public AttendanceResponse checkOut(UUID staffMemberId, String observations) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        LocalDate today = LocalDate.now();

        UUID staffId = resolveStaffMemberId(tenantId, staffMemberId);

        AttendanceRecord record = attendanceRecordRepository
                .findByTenantIdAndStaffMemberIdAndRecordDate(tenantId, staffId, today)
                .orElseThrow(() -> new BusinessRuleConflictException(
                        "NO_CHECKIN_FOUND",
                        "No se encontró marcación de entrada para el colaborador en la fecha de hoy."));

        record.setCheckOut(OffsetDateTime.now());
        if (observations != null && !observations.isBlank()) {
            record.setObservations((record.getObservations() != null ? record.getObservations() + " | " : "") + observations);
        }

        record = attendanceRecordRepository.save(record);

        if (record.getShift() != null) {
            record.getShift().setStatus(ShiftStatus.COMPLETED);
            shiftRepository.save(record.getShift());
        }

        log.info("Salida registrada para el personal {}: {}", record.getStaffMember().getId(), record.getCheckOut());
        return toAttendanceResponse(record);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> findAttendanceByStaff(UUID staffMemberId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID actualStaffId = resolveStaffMemberId(tenantId, staffMemberId);
        return attendanceRecordRepository.findByTenantIdAndStaffMemberId(tenantId, actualStaffId)
                .stream()
                .map(this::toAttendanceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<AttendanceResponse> findAttendanceByStaff(UUID staffMemberId, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID actualStaffId = resolveStaffMemberId(tenantId, staffMemberId);
        return attendanceRecordRepository.findByTenantIdAndStaffMemberId(tenantId, actualStaffId, pageable)
                .map(this::toAttendanceResponse);
    }

    @Transactional(readOnly = true)
    public Page<AttendanceResponse> findMyAttendance(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID currentUserId = getCurrentUserId();
        UUID actualStaffId = resolveStaffMemberId(tenantId, currentUserId);
        return attendanceRecordRepository.findByTenantIdAndStaffMemberId(tenantId, actualStaffId, pageable)
                .map(this::toAttendanceResponse);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> findMyAttendance() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID currentUserId = getCurrentUserId();
        UUID actualStaffId = resolveStaffMemberId(tenantId, currentUserId);
        return attendanceRecordRepository.findByTenantIdAndStaffMemberId(tenantId, actualStaffId)
                .stream()
                .map(this::toAttendanceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public StaffMemberResponse findCurrentStaffMember() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID currentUserId = getCurrentUserId();
        UUID actualStaffId = resolveStaffMemberId(tenantId, currentUserId);
        StaffMember staff = staffMemberRepository.findByIdAndTenantId(actualStaffId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffMember", "UserId: " + currentUserId));
        return toStaffMemberResponse(staff);
    }

    public UUID resolveStaffMemberId(UUID tenantId, UUID idOrUserId) {
        if (idOrUserId == null) {
            return null;
        }
        if (staffMemberRepository.findByIdAndTenantId(idOrUserId, tenantId).isPresent()) {
            return idOrUserId;
        }
        // SEC-9 (auditoria UNVERIFIED): solo lectura. Antes, si el id era de una cuenta sin ficha, se vinculaba (y se
        // guardaba) la ficha de la comunidad con el mismo correo o el mismo NOMBRE: una cuenta STAFF podia apropiarse de
        // la ficha de otra persona cambiando su nombre de perfil, y se vinculaban cuentas de otras comunidades. Vincular
        // una cuenta a una ficha solo ocurre al crear el personal (acceso nuevo o userId validado, SEC-9-7).
        return staffMemberRepository.findByTenantIdAndUserId(tenantId, idOrUserId)
                .map(StaffMember::getId)
                .orElse(idOrUserId);
    }

    private UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new BusinessRuleConflictException("UNAUTHORIZED", "Usuario no autenticado.");
        }
        try {
            return UUID.fromString(auth.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleConflictException("INVALID_USER_ID", "ID de usuario inválido en sesión.");
        }
    }

    private StaffMemberResponse toStaffMemberResponse(StaffMember staff) {
        return new StaffMemberResponse(
                staff.getId(),
                staff.getTenantId(),
                staff.getUser() != null ? staff.getUser().getId() : null,
                staff.getFirstName(),
                staff.getLastName(),
                staff.getFullName(),
                staff.getDocumentType(),
                staff.getDocumentNumber(),
                staff.getPhone(),
                staff.getEmail(),
                staff.getPosition(),
                staff.getArea(),
                staff.getContractType(),
                staff.getCompany(),
                staff.getStartDate(),
                staff.getStatus(),
                staff.getAssignedGate(),
                staff.getAvatarUrl(),
                staff.getObservations(),
                staff.getCreatedAt(),
                staff.getUpdatedAt()
        );
    }

    private ShiftResponse toShiftResponse(Shift shift) {
        return new ShiftResponse(
                shift.getId(),
                shift.getTenantId(),
                shift.getStaffMember().getId(),
                shift.getStaffMember().getFullName(),
                shift.getStaffMember().getPosition(),
                shift.getStaffMember().getArea(),
                shift.getShiftDate(),
                shift.getShiftName(),
                shift.getStartTime(),
                shift.getEndTime(),
                shift.getGate(),
                shift.getShiftType(),
                shift.getStatus(),
                shift.getNotes(),
                shift.getCreatedAt(),
                shift.getUpdatedAt()
        );
    }

    private AttendanceResponse toAttendanceResponse(AttendanceRecord a) {
        return new AttendanceResponse(
                a.getId(),
                a.getTenantId(),
                a.getStaffMember().getId(),
                a.getStaffMember().getFullName(),
                a.getStaffMember().getPosition(),
                a.getShift() != null ? a.getShift().getId() : null,
                a.getRecordDate(),
                a.getScheduledCheckIn(),
                a.getCheckIn(),
                a.getScheduledCheckOut(),
                a.getCheckOut(),
                a.getGate(),
                a.getStatus(),
                a.getObservations(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
