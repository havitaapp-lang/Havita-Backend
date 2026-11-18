package com.havita.modules.intercom.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.intercom.api.dto.InitiateIntercomCallRequest;
import com.havita.modules.intercom.api.dto.IntercomCallResponse;
import com.havita.modules.intercom.api.dto.IntercomResidentOptionResponse;
import com.havita.modules.intercom.api.dto.IntercomUnitDirectoryResponse;
import com.havita.modules.intercom.application.IntercomService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/intercom")
@RequiredArgsConstructor
@Tag(name = "Intercom (Citofonía Digital)", description = "Endpoints para citofonía exclusivamente por audio con Cloudflare RealtimeKit")
public class IntercomController {

    private final IntercomService intercomService;
    private final com.havita.modules.intercom.application.IntercomPushDeliveryRegistry pushDeliveryRegistry;

    /** P8-B: recibos por cliente (detras de un proxy, getRemoteAddr es el del proxy: el limite pasa a ser global). */
    private final com.havita.common.api.SlidingWindowRateLimiter receiptLimiter =
            new com.havita.common.api.SlidingWindowRateLimiter(300, java.time.Duration.ofMinutes(1), 10_000, java.time.Clock.systemUTC());

    @GetMapping({"/units", "/directory/units"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Buscar unidades disponibles para citofonía")
    public ResponseEntity<ApiResponse<Page<IntercomUnitDirectoryResponse>>> searchUnits(
            @RequestParam(required = false) String query,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(intercomService.searchUnits(query, pageable)));
    }

    @GetMapping({"/units/{propertyId}/residents", "/directory/units/{propertyId}/residents"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Consultar residentes autorizados de una unidad")
    public ResponseEntity<ApiResponse<List<IntercomResidentOptionResponse>>> getUnitResidents(
            @PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(intercomService.getUnitResidents(propertyId)));
    }

    @PostMapping("/calls")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Iniciar llamada de citofonía desde portería")
    public ResponseEntity<ApiResponse<IntercomCallResponse>> initiateCall(
            @Valid @RequestBody InitiateIntercomCallRequest request,
            Authentication auth) {
        UUID callerUserId = UUID.fromString(auth.getName());
        IntercomCallResponse response = intercomService.initiateCall(request, callerUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Llamada iniciada. Timbrando al residente..."));
    }

    @GetMapping("/calls/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'RESIDENT')")
    @Operation(summary = "Consultar estado y detalle de una llamada autorizada")
    public ResponseEntity<ApiResponse<IntercomCallResponse>> getCallDetail(
            @PathVariable UUID id,
            Authentication auth) {
        UUID currentUserId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(intercomService.getCallDetail(id, currentUserId)));
    }

    /**
     * P8-B: la llamada que timbra AHORA para el residente autenticado (o {@code data: null}). Sin token de audio.
     * Recupera una llamada cuyo evento SSE se perdio (app abierta tarde, aviso push pulsado, reconexion).
     */
    @GetMapping("/calls/incoming")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Residente: llamada entrante en curso (recuperación)")
    public ResponseEntity<ApiResponse<IntercomCallResponse>> incomingCall(Authentication auth) {
        UUID currentUserId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(intercomService.findIncomingCall(currentUserId).orElse(null)));
    }

    @PostMapping("/calls/{id}/accept")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Residente: Contestar llamada entrante")
    public ResponseEntity<ApiResponse<IntercomCallResponse>> acceptCall(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) com.havita.modules.intercom.api.dto.IntercomDeviceRequest device,
            Authentication auth) {
        UUID recipientUserId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(
                intercomService.acceptCall(id, recipientUserId, device != null ? device.pushEndpoint() : null),
                "Llamada aceptada. Conectando audio..."));
    }

    @PostMapping("/calls/{id}/prepare")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Residente: preparar la conexión mientras timbra (P8-A3; desactivado por defecto)")
    public ResponseEntity<ApiResponse<IntercomCallResponse>> prepareCall(
            @PathVariable UUID id,
            Authentication auth) {
        UUID recipientUserId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(intercomService.prepareCall(id, recipientUserId)));
    }

    @PostMapping("/calls/{id}/connected")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'RESIDENT')")
    @Operation(summary = "Registrar momento en que el audio WebRTC quedó establecido")
    public ResponseEntity<ApiResponse<IntercomCallResponse>> markConnected(
            @PathVariable UUID id,
            Authentication auth) {
        UUID currentUserId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(
                intercomService.markConnected(id, currentUserId), "Audio conectado exitosamente."));
    }

    @PostMapping("/calls/{id}/reject")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Residente: Rechazar llamada entrante")
    public ResponseEntity<ApiResponse<IntercomCallResponse>> rejectCall(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) com.havita.modules.intercom.api.dto.IntercomDeviceRequest device,
            Authentication auth) {
        UUID recipientUserId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(
                intercomService.rejectCall(id, recipientUserId, device != null ? device.pushEndpoint() : null),
                "Llamada rechazada."));
    }

    /**
     * P8-B: recibo de diagnostico del service worker (recibido, mostrado, pulsado). PUBLICO porque el service worker no
     * tiene sesion; lo autoriza el nonce de 128 bits de un solo uso que viajo cifrado en el aviso. Responde siempre 204
     * (no revela si el nonce existe), no devuelve datos y esta limitado por cliente.
     */
    @PostMapping("/push-receipts")
    @Operation(summary = "Recibo de diagnóstico de un aviso push de citofonía")
    public ResponseEntity<Void> pushReceipt(
            @Valid @RequestBody(required = false) com.havita.modules.intercom.api.dto.PushReceiptRequest receipt,
            jakarta.servlet.http.HttpServletRequest request) {
        if (receipt != null && receiptLimiter.tryAcquire(String.valueOf(request.getRemoteAddr()))) {
            com.havita.modules.intercom.application.IntercomPushDeliveryRegistry.Stage stage = null;
            try {
                stage = receipt.s() != null
                        ? com.havita.modules.intercom.application.IntercomPushDeliveryRegistry.Stage.valueOf(receipt.s())
                        : null;
            } catch (IllegalArgumentException ignored) {
                // etapa desconocida: se ignora
            }
            pushDeliveryRegistry.recordReceipt(receipt.r(), stage);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/calls/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Portería: Cancelar llamada saliente antes de contestar")
    public ResponseEntity<ApiResponse<IntercomCallResponse>> cancelCall(
            @PathVariable UUID id,
            Authentication auth) {
        UUID callerUserId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(
                intercomService.cancelCall(id, callerUserId), "Llamada cancelada."));
    }

    @PostMapping("/calls/{id}/end")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'RESIDENT')")
    @Operation(summary = "Finalizar/Colgar llamada activa")
    public ResponseEntity<ApiResponse<IntercomCallResponse>> endCall(
            @PathVariable UUID id,
            Authentication auth) {
        UUID currentUserId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok(
                intercomService.endCall(id, currentUserId), "Llamada finalizada."));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Consultar historial de llamadas de citofonía")
    public ResponseEntity<ApiResponse<Page<IntercomCallResponse>>> getHistory(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(intercomService.getCallHistory(pageable)));
    }
}
