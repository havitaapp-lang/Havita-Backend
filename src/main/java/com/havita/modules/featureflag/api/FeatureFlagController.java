package com.havita.modules.featureflag.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.featureflag.api.dto.CreateFeatureFlagRequest;
import com.havita.modules.featureflag.api.dto.FeatureFlagResponse;
import com.havita.modules.featureflag.api.dto.OverrideFeatureRequest;
import com.havita.modules.featureflag.application.FeatureFlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/feature-flags")
@RequiredArgsConstructor
@Tag(name = "Feature Flags & Plan Gating", description = "Catálogo de características, activación por plan SaaS y overrides por tenant")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar flags con estado efectivo para la copropiedad actual")
    public ResponseEntity<ApiResponse<List<FeatureFlagResponse>>> findAll() {
        return ResponseEntity.ok(ApiResponse.ok(featureFlagService.findAllFlagsForCurrentTenant()));
    }

    @GetMapping("/check/{flagKey}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT', 'SECURITY', 'STAFF')")
    @Operation(summary = "Verificar si un flag específico está habilitado")
    public ResponseEntity<ApiResponse<Boolean>> checkFlag(@PathVariable String flagKey) {
        return ResponseEntity.ok(ApiResponse.ok(featureFlagService.isFeatureEnabled(flagKey)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Crear nuevo Feature Flag en el catálogo global")
    public ResponseEntity<ApiResponse<FeatureFlagResponse>> create(
            @Valid @RequestBody CreateFeatureFlagRequest request) {
        FeatureFlagResponse response = featureFlagService.createFlag(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Feature Flag creado exitosamente"));
    }

    @PutMapping("/{id}/toggle")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Alternar switch global de un flag")
    public ResponseEntity<ApiResponse<FeatureFlagResponse>> toggleGlobal(
            @PathVariable UUID id,
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResponse.ok(featureFlagService.toggleGlobal(id, enabled), "Switch global actualizado"));
    }

    @PostMapping("/tenants/{tenantId}/flags/{flagId}/override")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Establecer override personalizado de una funcionalidad para un tenant")
    public ResponseEntity<ApiResponse<Void>> setOverride(
            @PathVariable UUID tenantId,
            @PathVariable UUID flagId,
            @Valid @RequestBody OverrideFeatureRequest request) {
        featureFlagService.setTenantOverride(tenantId, flagId, request);
        return ResponseEntity.ok(ApiResponse.ok(null, "Override aplicado exitosamente al tenant"));
    }
}
