package com.havita.modules.assembly.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.assembly.application.ProxyLimitPolicy;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fase 2 / P5-D — limite de representacion de la comunidad actual (PREPARADO, NO ACTIVADO). Solo existe con
 * {@code havita.assembly-proxies.enabled=true}. La comunidad es siempre la de la peticion (X-Tenant-ID validado por el
 * filtro); nunca se acepta del cuerpo.
 */
@RestController
@RequestMapping("/api/v1/assemblies/proxy-settings")
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class ProxySettingsController {

    public record UpdateProxySettingsRequest(@Min(value = 1, message = "El límite debe ser un entero positivo") Integer maxUnitsPerProxy) {}

    private final ProxyLimitPolicy policy;
    private final com.havita.modules.assembly.application.ProxyDocumentReconciler reconciler;

    public ProxySettingsController(ProxyLimitPolicy policy,
                                   com.havita.modules.assembly.application.ProxyDocumentReconciler reconciler) {
        this.policy = policy;
        this.reconciler = reconciler;
    }

    @GetMapping("/document-reconciliation")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Conciliacion (solo lectura) de documentos de poderes: huerfanos y ausentes; no borra nada")
    public ResponseEntity<ApiResponse<com.havita.modules.assembly.application.ProxyDocumentReconciler.Report>> reconciliation() {
        return ResponseEntity.ok(ApiResponse.ok(reconciler.reportForCurrentCommunity()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Limite de unidades por apoderado de la comunidad (y el global de respaldo)")
    public ResponseEntity<ApiResponse<ProxyLimitPolicy.Setting>> current() {
        return ResponseEntity.ok(ApiResponse.ok(policy.current()));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Historial de cambios del limite de la comunidad (solo lectura, paginado)")
    public ResponseEntity<ApiResponse<ProxyLimitPolicy.HistoryPage>> history(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(policy.history(page, size)));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Fijar el limite de unidades por apoderado de la comunidad (vacio = sin limite)")
    public ResponseEntity<ApiResponse<ProxyLimitPolicy.Setting>> update(@Valid @RequestBody UpdateProxySettingsRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(policy.update(body.maxUnitsPerProxy())));
    }
}
