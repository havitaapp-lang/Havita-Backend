package com.havita.modules.push.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.push.api.dto.PushSubscriptionRequest;
import com.havita.modules.push.api.dto.PushSubscriptionResponse;
import com.havita.modules.push.application.PushSubscriptionService;
import com.havita.modules.push.application.WebPushService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.havita.security.TenantContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/push-subscriptions")
@RequiredArgsConstructor
@Tag(name = "Push Notifications", description = "Gestión de suscripciones de notificaciones push del navegador")
public class PushSubscriptionController {

    private final PushSubscriptionService subscriptionService;
    private final WebPushService webPushService;

    /**
     * Returns the VAPID public key needed by the browser to subscribe to push.
     * This endpoint is public (added to security whitelist).
     */
    @GetMapping("/public-key")
    @Operation(summary = "Obtener la clave pública VAPID para suscripción push")
    public ResponseEntity<ApiResponse<Map<String, String>>> getPublicKey() {
        String publicKey = webPushService.getVapidPublicKey();
        if (publicKey == null || publicKey.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(
                    Map.of("publicKey", "", "configured", "false"),
                    "Notificaciones push no están configuradas en el servidor."
            ));
        }
        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("publicKey", publicKey, "configured", "true")
        ));
    }

    /**
     * Registers a push subscription for the authenticated user.
     */
    @PostMapping
    @Operation(summary = "Registrar suscripción push del navegador")
    public ResponseEntity<ApiResponse<PushSubscriptionResponse>> subscribe(
            @Valid @RequestBody PushSubscriptionRequest request,
            Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        PushSubscriptionResponse response = subscriptionService.subscribe(userId, tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Suscripción push registrada exitosamente."));
    }

    /**
     * Gets the push notification status for the authenticated user.
     */
    @GetMapping("/status")
    @Operation(summary = "Estado de las suscripciones push del usuario")
    public ResponseEntity<ApiResponse<PushSubscriptionService.PushStatusResponse>> getStatus(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return ResponseEntity.ok(ApiResponse.ok(subscriptionService.getStatus(userId, tenantId)));
    }

    /**
     * Lists active push subscriptions for the authenticated user.
     */
    @GetMapping
    @Operation(summary = "Listar suscripciones push activas del usuario")
    public ResponseEntity<ApiResponse<List<PushSubscriptionResponse>>> listSubscriptions(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return ResponseEntity.ok(ApiResponse.ok(subscriptionService.listSubscriptions(userId, tenantId)));
    }

    /**
     * Deactivates all push subscriptions for the authenticated user.
     */
    @DeleteMapping
    @Operation(summary = "Desactivar todas las suscripciones push del usuario")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        subscriptionService.unsubscribe(userId, tenantId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Suscripciones push desactivadas exitosamente."));
    }

    /**
     * P8-B: revoca la suscripcion de ESTE dispositivo (se llama al cerrar sesion). Solo actua sobre una suscripcion del
     * propio usuario en la comunidad actual; la respuesta es la misma exista o no.
     */
    @PostMapping("/unsubscribe")
    @Operation(summary = "Revocar la suscripción push de este dispositivo")
    public ResponseEntity<ApiResponse<Void>> unsubscribeCurrentDevice(
            @Valid @RequestBody com.havita.modules.push.api.dto.PushEndpointRequest request,
            Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        subscriptionService.unsubscribeByEndpoint(userId, tenantId, request.endpoint());
        return ResponseEntity.ok(ApiResponse.ok(null, "Notificaciones desactivadas en este dispositivo."));
    }

    /**
     * Sends a test push notification to the authenticated user's subscribed devices.
     */
    @PostMapping("/test")
    @Operation(summary = "Enviar notificación push de prueba")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendTest(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        int sent = subscriptionService.sendTest(userId, tenantId);
        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("sent", sent),
                sent > 0 ? "Notificación de prueba enviada a " + sent + " dispositivo(s)."
                        : "No hay dispositivos suscritos para recibir la notificación."
        ));
    }
}
