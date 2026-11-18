package com.havita.modules.reservation.api;

import com.havita.modules.reservation.domain.ReservationStatus;

import jakarta.validation.constraints.Size;
import com.havita.common.api.ApiResponse;
import jakarta.validation.constraints.Size;
import com.havita.common.api.PageResponse;
import jakarta.validation.constraints.Size;
import com.havita.modules.reservation.api.dto.CreateReservationRequest;
import jakarta.validation.constraints.Size;
import com.havita.modules.reservation.api.dto.ReservationResponse;
import jakarta.validation.constraints.Size;
import com.havita.modules.reservation.application.ReservationService;
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
import org.springframework.security.core.Authentication;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
@Tag(name = "Reservations & Amenities", description = "Gestión de reservas de zonas comunes y amenidades")
public class ReservationController {

    private final ReservationService reservationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar todas las reservas")
    public ResponseEntity<ApiResponse<PageResponse<ReservationResponse>>> findAll(
            @RequestParam(required = false) ReservationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ReservationResponse> page = reservationService.findAll(status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Obtener reserva por ID")
    public ResponseEntity<ApiResponse<ReservationResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(reservationService.findById(id)));
    }

    @GetMapping("/by-zone-and-date")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Consultar reservas de una zona común en una fecha específica")
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> findByZoneAndDate(
            @RequestParam UUID zoneId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(reservationService.findByZoneAndDate(zoneId, date)));
    }

    @GetMapping("/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Listar reservas de una propiedad")
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> findByProperty(
            @PathVariable UUID propertyId,
            Authentication authentication) {
        boolean isResident = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_RESIDENT"));
        if (isResident) {
            UUID userId = null;
            try {
                userId = UUID.fromString(authentication.getName());
            } catch (Exception ignored) {}
            if (userId != null) {
                return ResponseEntity.ok(ApiResponse.ok(reservationService.findAccessibleReservations(propertyId, userId)));
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(reservationService.findByProperty(propertyId)));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Listar reservas creadas por el usuario autenticado")
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> findMy(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(ApiResponse.ok(reservationService.findMyReservations(userId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Solicitar reserva de zona común")
    public ResponseEntity<ApiResponse<ReservationResponse>> create(
            @Valid @RequestBody CreateReservationRequest request,
            Authentication authentication) {
        UUID userId = null;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (Exception ignored) {}

        ReservationResponse response = reservationService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Reserva solicitada exitosamente"));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Confirmar y aprobar reserva")
    public ResponseEntity<ApiResponse<ReservationResponse>> confirm(
            @PathVariable UUID id,
            @RequestParam(required = false) @Size(max = 500, message = "Las notas no pueden exceder 500 caracteres") String notes) {
        return ResponseEntity.ok(ApiResponse.ok(reservationService.confirm(id, notes), "Reserva confirmada exitosamente"));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Cancelar reserva")
    public ResponseEntity<ApiResponse<ReservationResponse>> cancel(
            @PathVariable UUID id,
            @RequestParam(required = false) @Size(max = 500, message = "Las notas no pueden exceder 500 caracteres") String notes) {
        return ResponseEntity.ok(ApiResponse.ok(reservationService.cancel(id, notes), "Reserva cancelada exitosamente"));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Rechazar reserva")
    public ResponseEntity<ApiResponse<ReservationResponse>> reject(
            @PathVariable UUID id,
            @RequestParam(required = false) @Size(max = 500, message = "Las notas no pueden exceder 500 caracteres") String notes) {
        return ResponseEntity.ok(ApiResponse.ok(reservationService.reject(id, notes), "Reserva rechazada exitosamente"));
    }
}


