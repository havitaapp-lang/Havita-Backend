package com.havita.modules.resident.api;

import com.havita.modules.resident.domain.ResidentStatus;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.resident.api.dto.CreatePetRequest;
import com.havita.modules.resident.api.dto.CreateResidentRequest;
import com.havita.modules.resident.api.dto.CreateVehicleRequest;
import com.havita.modules.resident.api.dto.PetResponse;
import com.havita.modules.resident.api.dto.ResidentResponse;
import com.havita.modules.resident.api.dto.UpdateResidentRequest;
import com.havita.modules.resident.api.dto.VehicleResponse;
import com.havita.modules.resident.application.ResidentService;
import com.havita.modules.resident.application.VehicleAndPetService;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/residents")
@RequiredArgsConstructor
@Tag(name = "Residents & Census", description = "Gestion del censo de residentes, vehiculos y mascotas")
public class ResidentController {

    private final ResidentService residentService;
    private final VehicleAndPetService vehicleAndPetService;

    // --- Residentes ---

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar censo de residentes", description = "Lista paginada del tenant. Filtros combinables: status (ausente = todos), type (Propietario, Arrendatario, Residente) y search (nombre, documento, correo, telefono o unidad). Orden por defecto: nombre e id.")
    public ResponseEntity<ApiResponse<PageResponse<ResidentResponse>>> findAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ResidentStatus status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String structureId,
            @PageableDefault(size = 20) Pageable pageable) {
        // Fase 2 / E4: structureId = UUID de una estructura de la comunidad, o "none" (unidades sin estructura).
        Page<ResidentResponse> page = residentService.findAll(search, status, type, structureId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF') or (hasRole('RESIDENT') and @accessValidator.canReadResident(#id))")
    @Operation(summary = "Obtener residente por ID")
    public ResponseEntity<ApiResponse<ResidentResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(residentService.findById(id)));
    }

    @GetMapping("/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Listar residentes de una propiedad")
    public ResponseEntity<ApiResponse<List<ResidentResponse>>> findByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(residentService.findByProperty(propertyId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Registrar residente en el censo")
    public ResponseEntity<ApiResponse<ResidentResponse>> create(
            @Valid @RequestBody CreateResidentRequest request) {
        ResidentResponse response = residentService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Residente registrado exitosamente"));
    }

    @PostMapping("/users/{userId}/properties")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Asignar múltiples propiedades a un usuario (Admin)")
    public ResponseEntity<ApiResponse<java.util.List<ResidentResponse>>> assignProperties(
            @PathVariable UUID userId,
            @Valid @RequestBody com.havita.modules.resident.api.dto.AssignPropertiesRequest request) {
        java.util.List<ResidentResponse> responses = residentService.assignProperties(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(responses, "Propiedades asignadas exitosamente"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Actualizar datos del residente")
    public ResponseEntity<ApiResponse<ResidentResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateResidentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(residentService.update(id, request), "Residente actualizado exitosamente"));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Eliminar residente del censo")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        residentService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Residente eliminado exitosamente"));
    }

    // --- Vehículos ---

    @GetMapping("/vehicles")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar vehiculos censados")
    public ResponseEntity<ApiResponse<PageResponse<VehicleResponse>>> findAllVehicles(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<VehicleResponse> page = vehicleAndPetService.findAllVehicles(pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/vehicles/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Listar vehiculos de una propiedad")
    public ResponseEntity<ApiResponse<List<VehicleResponse>>> findVehiclesByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(vehicleAndPetService.findVehiclesByProperty(propertyId)));
    }

    @PostMapping("/vehicles")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Registrar vehiculo")
    public ResponseEntity<ApiResponse<VehicleResponse>> createVehicle(
            @Valid @RequestBody CreateVehicleRequest request) {
        VehicleResponse response = vehicleAndPetService.createVehicle(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Vehiculo registrado exitosamente"));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/vehicles/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Eliminar vehiculo (Admin)")
    public ResponseEntity<ApiResponse<Void>> deleteVehicle(@PathVariable UUID id) {
        vehicleAndPetService.deleteVehicle(id, null);
        return ResponseEntity.ok(ApiResponse.ok(null, "Vehiculo eliminado exitosamente"));
    }

    // --- Mascotas ---

    @GetMapping("/pets")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF')")
    @Operation(summary = "Listar mascotas censadas")
    public ResponseEntity<ApiResponse<PageResponse<PetResponse>>> findAllPets(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PetResponse> page = vehicleAndPetService.findAllPets(pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/pets/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Listar mascotas de una propiedad")
    public ResponseEntity<ApiResponse<List<PetResponse>>> findPetsByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(vehicleAndPetService.findPetsByProperty(propertyId)));
    }

    @PostMapping("/pets")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Registrar mascota")
    public ResponseEntity<ApiResponse<PetResponse>> createPet(
            @Valid @RequestBody CreatePetRequest request) {
        PetResponse response = vehicleAndPetService.createPet(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Mascota registrada exitosamente"));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/pets/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Eliminar mascota (Admin)")
    public ResponseEntity<ApiResponse<Void>> deletePet(@PathVariable UUID id) {
        vehicleAndPetService.deletePet(id, null);
        return ResponseEntity.ok(ApiResponse.ok(null, "Mascota eliminada exitosamente"));
    }
}

