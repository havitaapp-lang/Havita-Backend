package com.havita.modules.staff.api;

import com.havita.common.api.ApiResponse;
import jakarta.validation.constraints.Size;
import com.havita.common.api.PageResponse;
import jakarta.validation.constraints.Size;
import com.havita.modules.staff.api.dto.AttendanceResponse;
import jakarta.validation.constraints.Size;
import com.havita.modules.staff.api.dto.CheckInRequest;
import jakarta.validation.constraints.Size;
import com.havita.modules.staff.api.dto.CreateShiftRequest;
import jakarta.validation.constraints.Size;
import com.havita.modules.staff.api.dto.CreateStaffMemberRequest;
import jakarta.validation.constraints.Size;
import com.havita.modules.staff.api.dto.ShiftResponse;
import jakarta.validation.constraints.Size;
import com.havita.modules.staff.api.dto.StaffMemberResponse;
import jakarta.validation.constraints.Size;
import com.havita.modules.staff.application.ShiftAndAttendanceService;
import jakarta.validation.constraints.Size;
import com.havita.modules.staff.application.StaffService;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import jakarta.validation.constraints.Size;
import org.springframework.data.web.PageableDefault;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import jakarta.validation.constraints.Size;
import java.util.List;
import jakarta.validation.constraints.Size;
import java.util.UUID;

import jakarta.validation.constraints.Size;
@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
@Tag(name = "Staff & Shifts", description = "Gestión de personal operativo, programación de turnos y reloj control")
public class StaffController {

    private final StaffService staffService;
    private final ShiftAndAttendanceService shiftAndAttendanceService;

    // --- Personal (Staff Members) ---

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Listar colaboradores del personal")
    public ResponseEntity<ApiResponse<PageResponse<StaffMemberResponse>>> findAll(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<StaffMemberResponse> page = staffService.findAll(search, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Obtener perfil de colaborador autenticado")
    public ResponseEntity<ApiResponse<StaffMemberResponse>> findMe() {
        return ResponseEntity.ok(ApiResponse.ok(shiftAndAttendanceService.findCurrentStaffMember()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Obtener colaborador por ID")
    public ResponseEntity<ApiResponse<StaffMemberResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(staffService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Registrar nuevo colaborador")
    public ResponseEntity<ApiResponse<StaffMemberResponse>> create(
            @Valid @RequestBody CreateStaffMemberRequest request) {
        StaffMemberResponse response = staffService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Colaborador registrado exitosamente"));
    }

    // --- Turnos (Shifts) ---

    @GetMapping("/shifts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar turnos programados")
    public ResponseEntity<ApiResponse<PageResponse<ShiftResponse>>> findAllShifts(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ShiftResponse> page = shiftAndAttendanceService.findAllShifts(pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/shifts/by-date")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Consultar turnos de una fecha")
    public ResponseEntity<ApiResponse<List<ShiftResponse>>> findShiftsByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(shiftAndAttendanceService.findShiftsByDate(date)));
    }

    @GetMapping("/shifts/my-shifts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Consultar turnos del colaborador autenticado")
    public ResponseEntity<ApiResponse<List<ShiftResponse>>> findMyShifts() {
        return ResponseEntity.ok(ApiResponse.ok(shiftAndAttendanceService.findMyShifts()));
    }

    @GetMapping("/shifts/by-staff/{staffMemberId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Consultar turnos de un colaborador")
    public ResponseEntity<ApiResponse<List<ShiftResponse>>> findShiftsByStaff(@PathVariable UUID staffMemberId) {
        return ResponseEntity.ok(ApiResponse.ok(shiftAndAttendanceService.findShiftsByStaff(staffMemberId)));
    }

    @PostMapping("/shifts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Programar nuevo turno")
    public ResponseEntity<ApiResponse<ShiftResponse>> createShift(
            @Valid @RequestBody CreateShiftRequest request) {
        ShiftResponse response = shiftAndAttendanceService.createShift(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Turno programado exitosamente"));
    }

    @PatchMapping("/shifts/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Cancelar turno programado")
    public ResponseEntity<ApiResponse<ShiftResponse>> cancelShift(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(shiftAndAttendanceService.cancelShift(id), "Turno cancelado exitosamente"));
    }

    @PatchMapping("/shifts/{id}/reassign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Reasignar guarda a un turno")
    public ResponseEntity<ApiResponse<ShiftResponse>> reassignShift(
            @PathVariable UUID id,
            @RequestParam UUID newStaffMemberId) {
        return ResponseEntity.ok(ApiResponse.ok(shiftAndAttendanceService.reassignShift(id, newStaffMemberId), "Turno reasignado exitosamente"));
    }

    @DeleteMapping("/shifts/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Eliminar turno programado")
    public ResponseEntity<ApiResponse<Void>> deleteShift(@PathVariable UUID id) {
        shiftAndAttendanceService.deleteShift(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Turno eliminado exitosamente"));
    }

    // --- Asistencia y Reloj Control (Attendance) ---

    @GetMapping("/attendance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Listar registros de asistencia")
    public ResponseEntity<ApiResponse<PageResponse<AttendanceResponse>>> findAllAttendance(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AttendanceResponse> page = shiftAndAttendanceService.findAllAttendance(pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/attendance/by-date")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Consultar asistencias de una fecha")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> findAttendanceByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(shiftAndAttendanceService.findAttendanceByDate(date)));
    }

    @GetMapping("/attendance/by-staff/{staffMemberId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Consultar asistencias de un colaborador")
    public ResponseEntity<ApiResponse<PageResponse<AttendanceResponse>>> findAttendanceByStaff(
            @PathVariable UUID staffMemberId,
            @PageableDefault(size = 20, sort = "recordDate") Pageable pageable) {
        Page<AttendanceResponse> page = shiftAndAttendanceService.findAttendanceByStaff(staffMemberId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/attendance/my-attendance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Consultar asistencias del colaborador autenticado")
    public ResponseEntity<ApiResponse<PageResponse<AttendanceResponse>>> findMyAttendance(
            @PageableDefault(size = 20, sort = "recordDate") Pageable pageable) {
        Page<AttendanceResponse> page = shiftAndAttendanceService.findMyAttendance(pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @PostMapping("/attendance/check-in")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Marcar entrada de turno")
    public ResponseEntity<ApiResponse<AttendanceResponse>> checkIn(
            @Valid @RequestBody CheckInRequest request) {
        AttendanceResponse response = shiftAndAttendanceService.checkIn(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Entrada marcada exitosamente"));
    }

    @PostMapping("/attendance/{staffMemberId}/check-out")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Marcar salida de turno")
    public ResponseEntity<ApiResponse<AttendanceResponse>> checkOut(
            @PathVariable UUID staffMemberId,
            @RequestParam(required = false) @Size(max = 500, message = "Las observaciones no pueden exceder 500 caracteres") String observations) {
        return ResponseEntity.ok(ApiResponse.ok(shiftAndAttendanceService.checkOut(staffMemberId, observations), "Salida marcada exitosamente"));
    }
}

