package com.havita.modules.realtime.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.realtime.api.dto.NotificationResponse;
import com.havita.modules.realtime.api.dto.PanicAlertRequest;
import com.havita.modules.realtime.api.dto.PanicAlertResponse;
import com.havita.modules.realtime.api.dto.ResolvePanicAlertRequest;
import com.havita.modules.realtime.api.dto.SendNotificationRequest;
import com.havita.modules.realtime.application.RealtimeService;
import org.springframework.security.access.AccessDeniedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/realtime")
@RequiredArgsConstructor
@Tag(name = "Realtime & Security Alerts", description = "Notificaciones instantáneas, chat de portería y botón de pánico SOS")
public class RealtimeController {

    private final RealtimeService realtimeService;
    private final com.havita.security.ResidentUnitResolver unitResolver;

    // --- NOTIFICATIONS ---

    @GetMapping("/notifications")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'RESIDENT')")
    @Operation(summary = "Listar notificaciones del usuario o inmueble")
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> findNotifications(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<NotificationResponse> page = realtimeService.findNotifications(propertyId, userId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @PostMapping("/notifications")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Enviar notificación push")
    public ResponseEntity<ApiResponse<NotificationResponse>> sendNotification(
            @Valid @RequestBody SendNotificationRequest request) {
        NotificationResponse response = realtimeService.sendNotification(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Notificación enviada exitosamente"));
    }

    @PutMapping("/notifications/{id}/read")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'RESIDENT')")
    @Operation(summary = "Marcar notificación como leída")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(realtimeService.markNotificationAsRead(id)));
    }

    // --- PANIC ALERTS ---

    /**
     * Residente: crear alerta de emergencia. Fase 2 / E1: la unidad es la seleccionada (X-Property-Id), validada contra
     * las relaciones ACTIVE del usuario en PostgreSQL; nunca la "primera" residencia. Con una sola unidad no hace falta
     * cabecera; con varias y sin seleccion, 409 UNIT_SELECTION_REQUIRED; ajena o revocada, 409 UNIT_NOT_AVAILABLE.
     * Ninguna alerta se crea con una ubicacion supuesta.
     */
    @PostMapping("/panic")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Crear alerta de emergencia (botón SOS)")
    public ResponseEntity<ApiResponse<PanicAlertResponse>> createAlert(
            @Valid @RequestBody PanicAlertRequest request,
            Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        com.havita.security.ResidentUnitResolver.ResolvedUnit unit = unitResolver.resolveCurrent();
        PanicAlertResponse response = realtimeService.triggerPanicAlert(
                request.alertType(), request.notes(), userId, unit.propertyId(), unit.residentId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Alerta de emergencia enviada."));
    }

    /** Portería/Admin: listar alertas activas */
    @GetMapping("/panic/active")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Listar alertas activas")
    public ResponseEntity<ApiResponse<List<PanicAlertResponse>>> getActive() {
        return ResponseEntity.ok(ApiResponse.ok(realtimeService.getActiveAlerts()));
    }

    /** Admin: historial paginado de todas las alertas */
    @GetMapping("/panic")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Historial paginado de alertas")
    public ResponseEntity<ApiResponse<Page<PanicAlertResponse>>> getAll(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(realtimeService.getAllAlertsPaginated(pageable)));
    }

    /** Portería/Admin: detalle de una alerta */
    @GetMapping("/panic/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Detalle de una alerta")
    public ResponseEntity<ApiResponse<PanicAlertResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(realtimeService.getAlertById(id)));
    }

    /** Portería/Admin: tomar una alerta (ACKNOWLEDGED) */
    @PostMapping("/panic/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Tomar una alerta de emergencia")
    public ResponseEntity<ApiResponse<PanicAlertResponse>> acknowledge(
            @PathVariable UUID id,
            Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(
                realtimeService.acknowledgeAlert(id, userId), "Alerta tomada."));
    }

    /** Portería/Admin: resolver una alerta */
    @PostMapping("/panic/{id}/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Resolver una alerta de emergencia")
    public ResponseEntity<ApiResponse<PanicAlertResponse>> resolve(
            @PathVariable UUID id,
            @Valid @RequestBody ResolvePanicAlertRequest request,
            Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(
                realtimeService.resolveAlert(id, userId, request.finalStatus(), request.resolutionNotes()),
                "Alerta resuelta."));
    }

    /** Residente: historial de sus propias alertas */
    @GetMapping("/panic/my")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Mis alertas de emergencia")
    public ResponseEntity<ApiResponse<List<PanicAlertResponse>>> getMyAlerts(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        // Fase 2 / E1: las alertas de todas sus unidades ACTIVE (antes solo las de la "primera" residencia).
        java.util.List<UUID> residentIds = unitResolver.currentUnits().stream()
                .map(com.havita.security.ResidentUnitResolver.UnitOption::residentId).toList();
        return ResponseEntity.ok(ApiResponse.ok(realtimeService.getMyAlerts(residentIds)));
    }
}
