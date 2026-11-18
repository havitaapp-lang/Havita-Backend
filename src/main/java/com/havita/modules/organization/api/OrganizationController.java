package com.havita.modules.organization.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.organization.api.dto.CreateOrganizationRequest;
import com.havita.modules.organization.api.dto.OrganizationResponse;
import com.havita.modules.organization.api.dto.UpdateOrganizationRequest;
import com.havita.modules.organization.application.OrganizationService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "Gestion de organizaciones corporativas")
public class OrganizationController {

    private final OrganizationService organizationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'READ_ONLY') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Listar organizaciones", description = "Lista paginada de organizaciones con busqueda opcional")
    public ResponseEntity<ApiResponse<PageResponse<OrganizationResponse>>> findAll(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<OrganizationResponse> page = organizationService.findAll(search, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'READ_ONLY') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Obtener organizacion por ID")
    public ResponseEntity<ApiResponse<OrganizationResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(organizationService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Crear organizacion", description = "Crea una nueva organizacion corporativa")
    public ResponseEntity<ApiResponse<OrganizationResponse>> create(
            @Valid @RequestBody CreateOrganizationRequest request) {
        OrganizationResponse response = organizationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Organizacion creada exitosamente"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Actualizar organizacion")
    public ResponseEntity<ApiResponse<OrganizationResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrganizationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(organizationService.update(id, request), "Organizacion actualizada exitosamente"));
    }
}
