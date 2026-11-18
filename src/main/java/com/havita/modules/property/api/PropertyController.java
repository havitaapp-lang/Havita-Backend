package com.havita.modules.property.api;

import com.havita.modules.property.domain.PropertyStatus;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.property.api.dto.CreateCommonZoneRequest;
import com.havita.modules.property.api.dto.CreatePropertyRequest;
import com.havita.modules.property.api.dto.CreateStructureRequest;
import com.havita.modules.property.api.dto.CommonZoneResponse;
import com.havita.modules.property.api.dto.PropertyResponse;
import com.havita.modules.property.api.dto.StructureResponse;
import com.havita.modules.property.api.dto.UpdateCommonZoneRequest;
import com.havita.modules.property.api.dto.UpdatePropertyRequest;
import com.havita.modules.property.application.CommonZoneService;
import com.havita.modules.property.application.PropertyService;
import com.havita.modules.property.application.StructureService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import com.havita.modules.property.application.PropertyTransferService;
import com.havita.modules.property.api.dto.TransferOwnershipRequest;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties")
@RequiredArgsConstructor
@Tag(name = "Properties & Zones", description = "Gestion de inmuebles, torres/bloques y zonas comunes")
public class PropertyController {
    @GetMapping("/{propertyId}/history")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Obtener el historial de propietarios y arrendatarios de una propiedad")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> getPropertyHistory(
            @PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(propertyService.getPropertyHistory(propertyId)));
    }

    @PostMapping("/{propertyId}/transfer-owner")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Transferir propiedad a nuevo dueño")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> transferOwnership(
            @PathVariable UUID propertyId,
            @AuthenticationPrincipal String userIdStr,
            @RequestBody @Valid TransferOwnershipRequest request) {
        UUID adminUserId = UUID.fromString(userIdStr);
        java.util.Map<String, Object> result = propertyTransferService.transferOwnership(propertyId, request, adminUserId);
        return ResponseEntity.ok(ApiResponse.ok(result, "Propiedad transferida exitosamente"));
    }


    
    private final PropertyService propertyService;
    private final PropertyTransferService propertyTransferService;

    private final StructureService structureService;
    private final CommonZoneService commonZoneService;
    private final com.havita.modules.property.application.BulkPropertyService bulkPropertyService;

    // --- Generacion Masiva ---

    @PostMapping("/bulk/generate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Generacion masiva de estructuras y propiedades")
    public ResponseEntity<ApiResponse<com.havita.modules.property.api.dto.BulkPropertyGenerationResponse>> generateBulk(
            @Valid @RequestBody com.havita.modules.property.api.dto.BulkPropertyGenerationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(bulkPropertyService.generateBulk(request), "Propiedades generadas exitosamente"));
    }

    @PostMapping(value = "/bulk/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Importacion masiva de propiedades via archivo")
    public ResponseEntity<ApiResponse<com.havita.modules.property.api.dto.BulkPropertyGenerationResponse>> importBulk(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(bulkPropertyService.importBulk(file), "Propiedades importadas exitosamente"));
    }

    // --- Inmuebles (Properties) ---

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar inmuebles", description = "Lista paginada de inmuebles de la copropiedad actual")
    public ResponseEntity<ApiResponse<PageResponse<PropertyResponse>>> findAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) PropertyStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PropertyResponse> page = propertyService.findAll(search, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#id))")
    @Operation(summary = "Obtener inmueble por ID")
    public ResponseEntity<ApiResponse<PropertyResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(propertyService.findById(id)));
    }

    @GetMapping("/by-structure/{structureId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar inmuebles por estructura")
    public ResponseEntity<ApiResponse<List<PropertyResponse>>> findByStructure(@PathVariable UUID structureId) {
        return ResponseEntity.ok(ApiResponse.ok(propertyService.findByStructure(structureId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Crear inmueble")
    public ResponseEntity<ApiResponse<PropertyResponse>> create(
            @Valid @RequestBody CreatePropertyRequest request) {
        PropertyResponse response = propertyService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Inmueble creado exitosamente"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Actualizar inmueble")
    public ResponseEntity<ApiResponse<PropertyResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePropertyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(propertyService.update(id, request), "Inmueble actualizado exitosamente"));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Eliminar inmueble")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        propertyService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Inmueble eliminado exitosamente"));
    }

    @GetMapping("/coefficients/total")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Obtener suma total de coeficientes de la copropiedad")
    public ResponseEntity<ApiResponse<BigDecimal>> getTotalCoefficient() {
        return ResponseEntity.ok(ApiResponse.ok(propertyService.getTotalCoefficient()));
    }

    // --- Estructuras (Torres / Bloques) ---

    @GetMapping("/structures")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Listar estructuras de la copropiedad")
    public ResponseEntity<ApiResponse<List<StructureResponse>>> findAllStructures() {
        return ResponseEntity.ok(ApiResponse.ok(structureService.findAll()));
    }

    @PostMapping("/structures")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Crear estructura (torre/bloque)")
    public ResponseEntity<ApiResponse<StructureResponse>> createStructure(
            @Valid @RequestBody CreateStructureRequest request) {
        StructureResponse response = structureService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Estructura creada exitosamente"));
    }

    @DeleteMapping("/structures/by-name/{name}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Eliminar estructura por nombre")
    public ResponseEntity<ApiResponse<Void>> deleteStructure(@PathVariable String name) {
        structureService.deleteByName(name);
        return ResponseEntity.ok(ApiResponse.ok(null, "Estructura eliminada exitosamente"));
    }

    // --- Zonas Comunes (Common Zones) ---

    @GetMapping("/zones")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Listar zonas comunes de la copropiedad")
    public ResponseEntity<ApiResponse<List<CommonZoneResponse>>> findAllZones() {
        return ResponseEntity.ok(ApiResponse.ok(commonZoneService.findAll()));
    }

    @PostMapping("/zones")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Crear zona comun")
    public ResponseEntity<ApiResponse<CommonZoneResponse>> createZone(
            @Valid @RequestBody CreateCommonZoneRequest request) {
        CommonZoneResponse response = commonZoneService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Zona comun creada exitosamente"));
    }

    @PutMapping("/zones/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Actualizar zona comun")
    public ResponseEntity<ApiResponse<CommonZoneResponse>> updateZone(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCommonZoneRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(commonZoneService.update(id, request), "Zona comun actualizada exitosamente"));
    }
}
