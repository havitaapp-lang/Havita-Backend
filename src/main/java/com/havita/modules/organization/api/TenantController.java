package com.havita.modules.organization.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.organization.api.dto.CreateTenantRequest;
import com.havita.modules.organization.api.dto.TenantResponse;
import com.havita.modules.organization.api.dto.UpdateTenantRequest;
import com.havita.modules.organization.application.TenantService;
import com.havita.modules.organization.domain.TenantStatus;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Gestion de copropiedades / comunidades residenciales")
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'READ_ONLY') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Listar tenants", description = "Lista paginada de tenants con busqueda y filtro de estado")
    public ResponseEntity<ApiResponse<PageResponse<TenantResponse>>> findAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) TenantStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<TenantResponse> page = tenantService.findAll(search, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'READ_ONLY', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Obtener tenant por ID")
    public ResponseEntity<ApiResponse<TenantResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(tenantService.findById(id)));
    }

    @GetMapping("/by-slug/{slug}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'READ_ONLY', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Obtener tenant por slug")
    public ResponseEntity<ApiResponse<TenantResponse>> findBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.ok(tenantService.findBySlug(slug)));
    }

    @GetMapping("/by-organization/{organizationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'READ_ONLY') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Listar tenants de una organizacion")
    public ResponseEntity<ApiResponse<List<TenantResponse>>> findByOrganization(
            @PathVariable UUID organizationId) {
        return ResponseEntity.ok(ApiResponse.ok(tenantService.findByOrganization(organizationId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Crear tenant", description = "Crea una nueva copropiedad/comunidad")
    public ResponseEntity<ApiResponse<TenantResponse>> create(
            @Valid @RequestBody CreateTenantRequest request) {
        TenantResponse response = tenantService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Tenant creado exitosamente"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Actualizar tenant")
    public ResponseEntity<ApiResponse<TenantResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(tenantService.update(id, request), "Tenant actualizado exitosamente"));
    }

    @PutMapping("/{id}/features/{featureKey}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Activar o desactivar un modulo especifico para una copropiedad")
    public ResponseEntity<ApiResponse<TenantResponse>> toggleFeature(
            @PathVariable UUID id,
            @PathVariable String featureKey,
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResponse.ok(
                tenantService.toggleFeature(id, featureKey, enabled),
                "Modulo actualizado exitosamente"
        ));
    }

    @PostMapping("/{id}/change-plan")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Cambiar plan SaaS de una copropiedad")
    public ResponseEntity<ApiResponse<TenantResponse>> changePlan(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String planIdStr = body.get("planId");
        UUID planId = UUID.fromString(planIdStr);
        return ResponseEntity.ok(ApiResponse.ok(
                tenantService.changePlan(id, planId),
                "Plan actualizado exitosamente"
        ));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Suspender tenant")
    public ResponseEntity<ApiResponse<TenantResponse>> suspend(
            @PathVariable UUID id,
            @RequestParam String reason) {
        return ResponseEntity.ok(ApiResponse.ok(tenantService.suspendTenant(id, reason), "Tenant suspendido"));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Activar tenant")
    public ResponseEntity<ApiResponse<TenantResponse>> activate(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(tenantService.activateTenant(id), "Tenant activado"));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Cancelar tenant")
    public ResponseEntity<ApiResponse<TenantResponse>> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(tenantService.cancelTenant(id), "Tenant cancelado"));
    }
}
