package com.havita.modules.access.api;
import com.havita.modules.access.domain.VisitorStatus;

import com.havita.modules.access.domain.PackageStatus;
import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.access.api.dto.CreatePackageRequest;
import com.havita.modules.access.api.dto.CreateVisitorRequest;
import com.havita.modules.access.api.dto.PackageResponse;
import com.havita.modules.access.api.dto.VisitorResponse;
import com.havita.modules.access.application.PackageService;
import com.havita.modules.access.application.VisitorService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/access")
@RequiredArgsConstructor
@Tag(name = "Access Control & Gatehouse", description = "Control de accesos de visitas y correspondencia / paquetería")
public class AccessController {

    private final VisitorService visitorService;
    private final PackageService packageService;

    // --- Control de Visitas ---

    @GetMapping("/visitors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar visitas y accesos", description = "Lista paginada de visitas con busqueda y filtro de estado")
    public ResponseEntity<ApiResponse<PageResponse<VisitorResponse>>> findAllVisitors(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) VisitorStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<VisitorResponse> page = visitorService.findAll(search, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/visitors/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Obtener visita por ID")
    public ResponseEntity<ApiResponse<VisitorResponse>> findVisitorById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(visitorService.findById(id)));
    }

    @GetMapping("/visitors/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar visitas asociadas a una propiedad")
    public ResponseEntity<ApiResponse<List<VisitorResponse>>> findVisitorsByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(visitorService.findByProperty(propertyId)));
    }

    @PostMapping("/visitors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Registrar o pre-autorizar visita")
    public ResponseEntity<ApiResponse<VisitorResponse>> createVisitor(
            @Valid @RequestBody CreateVisitorRequest request,
            @RequestParam(required = false) Boolean isPreAuthorized,
            Authentication authentication) {
        boolean isResident = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_RESIDENT"));
        boolean preAuth = isPreAuthorized != null ? isPreAuthorized : isResident;
        VisitorResponse response = visitorService.create(request, preAuth);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Visita registrada exitosamente"));
    }

    /** Administracion y porteria editan los datos de un visitante de su comunidad (otra comunidad: 404). */
    @PutMapping("/visitors/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Editar datos del visitante (administración y portería)")
    public ResponseEntity<ApiResponse<VisitorResponse>> updateVisitor(
            @PathVariable UUID id,
            @Valid @RequestBody com.havita.modules.access.api.dto.UpdateVisitorRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(visitorService.updateAsStaff(id, request), "Visita actualizada"));
    }

    @PostMapping("/visitors/{id}/entry")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Marcar ingreso efectivo de visitante")
    public ResponseEntity<ApiResponse<VisitorResponse>> registerEntry(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(visitorService.registerEntry(id), "Ingreso registrado"));
    }

    @PostMapping("/visitors/{id}/exit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Marcar salida de visitante")
    public ResponseEntity<ApiResponse<VisitorResponse>> registerExit(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(visitorService.registerExit(id), "Salida registrada"));
    }

    @PostMapping("/visitors/{id}/authorize")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Autorizar visita pendiente")
    public ResponseEntity<ApiResponse<VisitorResponse>> authorizeVisitor(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(visitorService.authorize(id), "Visita autorizada"));
    }

    @DeleteMapping("/visitors/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Cancelar o eliminar visita")
    public ResponseEntity<ApiResponse<Void>> deleteVisitor(@PathVariable UUID id) {
        visitorService.deleteVisitor(id, null);
        return ResponseEntity.ok(ApiResponse.ok(null, "Visita cancelada exitosamente"));
    }

    // --- Paquetería y Correspondencia ---

    @GetMapping("/packages")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar paqueteria y correspondencia")
    public ResponseEntity<ApiResponse<PageResponse<PackageResponse>>> findAllPackages(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) PackageStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PackageResponse> page = packageService.findAll(search, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/packages/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Obtener paquete por ID")
    public ResponseEntity<ApiResponse<PackageResponse>> findPackageById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(packageService.findById(id)));
    }

    @GetMapping("/packages/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Listar paquetes de una propiedad")
    public ResponseEntity<ApiResponse<List<PackageResponse>>> findPackagesByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(packageService.findByProperty(propertyId)));
    }

    @PostMapping("/packages")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Recibir paquete en porteria")
    public ResponseEntity<ApiResponse<PackageResponse>> receivePackage(
            @Valid @RequestBody CreatePackageRequest request,
            Authentication authentication) {
        UUID guardUserId = null;
        try {
            guardUserId = UUID.fromString(authentication.getName());
        } catch (Exception ignored) {}

        PackageResponse response = packageService.receivePackage(request, guardUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Paquete recibido exitosamente"));
    }

    @PostMapping("/packages/{id}/notify")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Notificar recepcion de paquete al residente")
    public ResponseEntity<ApiResponse<PackageResponse>> notifyPackage(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(packageService.notifyPackage(id), "Paquete notificado"));
    }

    @PostMapping("/packages/{id}/deliver")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Entregar paquete a residente")
    public ResponseEntity<ApiResponse<PackageResponse>> deliverPackage(
            @PathVariable UUID id,
            @RequestParam(required = false) String deliveredTo) {
        return ResponseEntity.ok(ApiResponse.ok(packageService.deliverPackage(id, deliveredTo), "Paquete entregado"));
    }

    /**
     * El residente confirma que ya retiro SU paquete en porteria. Solo paquetes de unidades con las que hoy tiene
     * relacion ACTIVA (si no, 404); un paquete ya entregado responde 409. Queda registrado quien confirmo.
     */
    @PostMapping("/packages/{id}/confirm-pickup")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Residente: confirmar que retiro su paquete")
    public ResponseEntity<ApiResponse<PackageResponse>> confirmPickup(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(packageService.confirmPickupByResident(id), "Entrega confirmada"));
    }

    @DeleteMapping("/packages/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY')")
    @Operation(summary = "Eliminar o cancelar paquete")
    public ResponseEntity<ApiResponse<Void>> deletePackage(@PathVariable UUID id) {
        packageService.deletePackage(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Paquete eliminado exitosamente"));
    }
}



