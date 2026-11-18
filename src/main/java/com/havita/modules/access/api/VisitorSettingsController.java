package com.havita.modules.access.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.access.api.dto.UpdateVisitorSettingsRequest;
import com.havita.modules.access.api.dto.VisitorSettingsResponse;
import com.havita.modules.access.application.VisitorSettingsService;
import com.havita.security.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/access/visitor-settings")
@RequiredArgsConstructor
@Tag(name = "Visitor Settings", description = "Configuración de horarios de visitas por copropiedad")
public class VisitorSettingsController {

    private final VisitorSettingsService visitorSettingsService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Obtener configuración de horarios de visitas",
               description = "Devuelve el modo y los horarios configurados para el tenant autenticado.")
    public ResponseEntity<ApiResponse<VisitorSettingsResponse>> getSettings() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return ResponseEntity.ok(ApiResponse.ok(visitorSettingsService.getSettings(tenantId)));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Actualizar configuración de horarios de visitas",
               description = "Actualiza el modo y los horarios del tenant autenticado. Solo admins.")
    public ResponseEntity<ApiResponse<VisitorSettingsResponse>> updateSettings(
            @Valid @RequestBody UpdateVisitorSettingsRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return ResponseEntity.ok(ApiResponse.ok(
                visitorSettingsService.updateSettings(tenantId, request),
                "Configuración de horarios actualizada exitosamente"
        ));
    }
}
