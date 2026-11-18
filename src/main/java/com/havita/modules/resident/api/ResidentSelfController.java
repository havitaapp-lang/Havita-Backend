package com.havita.modules.resident.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.api.dto.CreatePetRequest;
import com.havita.modules.resident.api.dto.CreateVehicleRequest;
import com.havita.modules.resident.api.dto.PetResponse;
import com.havita.modules.resident.api.dto.VehicleResponse;
import com.havita.modules.resident.application.VehicleAndPetService;
import com.havita.modules.resident.infrastructure.ResidentInvitationRepository;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.domain.Resident;
import com.havita.security.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import com.havita.modules.resident.application.ResidentService;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints del propio residente autenticado.
 *
 * SEGURIDAD IDOR:
 * - Todos los metodos estan restringidos a hasRole('RESIDENT').
 * - El propertyId NUNCA es aceptado como parametro del cliente: se resuelve
 *   directamente desde propertyId en la base de datos.
 * - Cualquier intento de acceder a recurso ajeno resulta en 404 (no 403)
 *   para no revelar la existencia del recurso (security by obscurity).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/resident/me")
@PreAuthorize("hasRole('RESIDENT')")
@RequiredArgsConstructor
@Tag(name = "Resident Self-Service", description = "Portal del residente: gestion propia de vehiculos, mascotas y onboarding")
public class ResidentSelfController {
    private final com.havita.modules.resident.application.ResidentService residentService;

    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.servlet.http.HttpServletRequest request;

    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final VehicleAndPetService vehicleAndPetService;
    private final com.havita.modules.resident.infrastructure.ResidentRepository residentRepository;
    private final com.havita.modules.access.application.VisitorService visitorService;
    private final com.havita.modules.resident.application.ResidentInvitationService invitationService;
    private final ResidentInvitationRepository residentInvitationRepository;
    private final com.havita.security.ResidentUnitResolver unitResolver;
    private final com.havita.modules.organization.infrastructure.TenantRepository tenantRepository;

    // Helper: resuelve el usuario autenticado y la unidad con la que opera (Fase 2 / E1: ResidentUnitResolver).
    private record ResidentContext(User user, UUID propertyId, UUID residentId) {}

    /**
     * Fase 2 / E1: la unidad sale SOLO del resolvedor central. X-Property-Id elige entre las unidades ACTIVE del
     * usuario; una unidad ajena, revocada o inexistente se rechaza (409 UNIT_NOT_AVAILABLE) y nunca se sustituye por
     * otra; con varias unidades y sin seleccion, 409 UNIT_SELECTION_REQUIRED; cabecera mal formada, 400.
     */
    private ResidentContext getAuthenticatedContext(String userIdStr) {
        UUID userId = UUID.fromString(userIdStr);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        // Se reutiliza el usuario ya cargado (sin una segunda consulta para el correo).
        com.havita.security.ResidentUnitResolver.ResolvedUnit unit = unitResolver.resolve(user.getId(), user.getEmail(),
                TenantContextHolder.getRequiredTenantId(),
                com.havita.security.ResidentUnitResolver.parseUnitId(request.getHeader(com.havita.security.ResidentUnitResolver.HEADER)));
        return new ResidentContext(user, unit.propertyId(), unit.residentId());
    }


    @GetMapping("/properties")
    @Operation(summary = "Obtener todas mis propiedades en esta copropiedad",
            description = "Una entrada por unidad ACTIVE, en orden estable (estructura, unidad). No requiere unidad seleccionada.")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyProperties(
            @AuthenticationPrincipal String userIdStr) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        String tenantName = tenantRepository.findById(tenantId).map(com.havita.modules.organization.domain.Tenant::getName).orElse(null);
        List<Map<String, Object>> properties = unitResolver.currentUnits().stream()
                .map(u -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    // Campos anteriores (sin cambios de nombre ni de significado; "type" = relacion principal).
                    info.put("propertyId", u.propertyId());
                    info.put("number", u.number());
                    info.put("name", u.name());
                    info.put("type", u.primaryRelation());
                    info.put("status", u.propertyStatus());
                    info.put("path", u.legacyPath());
                    // Fase 2 / E1 (aditivos).
                    info.put("label", u.label());
                    info.put("tenantId", u.tenantId());
                    info.put("tenantName", tenantName);
                    info.put("propertyType", u.propertyType());
                    info.put("level", u.level());
                    info.put("structureId", u.structureId());
                    info.put("structureName", u.structureName());
                    info.put("structureType", u.structureType());
                    info.put("relations", u.relations());
                    info.put("isMainContact", u.mainContact());
                    info.put("available", true);
                    return info;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(properties));
    }


    // ─── Informacion de mi propiedad ─────────────────────────────────────────────

    @GetMapping("/property-info")
    @Operation(summary = "Obtener informacion de mi propiedad")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyPropertyInfo(
            @AuthenticationPrincipal String userIdStr) {

        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        Property property = propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("propertyId", property.getId());
        info.put("number", property.getNumber());
        info.put("name", property.getName());
        info.put("status", property.getStatus());
        info.put("hasCompletedOnboarding", user.getHasCompletedOnboarding());

        return ResponseEntity.ok(ApiResponse.ok(info));
    }

    // ─── Mis Vehiculos ────────────────────────────────────────────────────────────

    @GetMapping("/vehicles")
    @Operation(summary = "Listar mis vehiculos")
    public ResponseEntity<ApiResponse<List<VehicleResponse>>> getMyVehicles(
            @AuthenticationPrincipal String userIdStr) {
        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();
        return ResponseEntity.ok(ApiResponse.ok(vehicleAndPetService.findMyVehiclesByProperty(propertyId, UUID.fromString(userIdStr))));
    }

    @PostMapping("/vehicles")
    @Operation(summary = "Registrar mi vehiculo")
    public ResponseEntity<ApiResponse<VehicleResponse>> addMyVehicle(
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody ResidentCreateVehicleRequest request) {

        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();
        
        // Buscar el residentId del usuario actual para la propiedad actual
        UUID residentId = context.residentId(); // relacion ACTIVE del usuario con esta unidad

        // IDOR prevention: propertyId viene del JWT, nunca del cliente
        CreateVehicleRequest secureRequest = new CreateVehicleRequest(
                propertyId, residentId,
                request.plate(), request.type(), request.brand(),
                request.model(), request.color(), request.parkingSpot()
        );

        VehicleResponse response = vehicleAndPetService.createVehicle(secureRequest);
        log.info("Residente {} (Resident ID {}) registro vehiculo {} en propiedad {}", userIdStr, residentId, request.plate(), propertyId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Vehiculo registrado exitosamente"));
    }

    @PutMapping("/vehicles/{vehicleId}")
    @Operation(summary = "Actualizar mi vehiculo")
    public ResponseEntity<ApiResponse<VehicleResponse>> updateMyVehicle(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID vehicleId,
            @Valid @RequestBody ResidentCreateVehicleRequest request) {

        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();
        
        UUID residentId = context.residentId(); // relacion ACTIVE del usuario con esta unidad

        CreateVehicleRequest secureRequest = new CreateVehicleRequest(
                propertyId, residentId,
                request.plate(), request.type(), request.brand(),
                request.model(), request.color(), request.parkingSpot()
        );

        VehicleResponse response = vehicleAndPetService.updateMyVehicle(vehicleId, propertyId, UUID.fromString(userIdStr), secureRequest);
        log.info("Residente {} actualizo vehiculo {} en propiedad {}", userIdStr, vehicleId, propertyId);
        return ResponseEntity.ok(ApiResponse.ok(response, "Vehiculo actualizado exitosamente"));
    }

    @DeleteMapping("/vehicles/{vehicleId}")
    @Operation(summary = "Eliminar mi vehiculo")
    public ResponseEntity<ApiResponse<Void>> deleteMyVehicle(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID vehicleId) {

        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();
        // IDOR: deleteMyVehicle verifica que vehicleId pertenece a user.propertyId Y al residente
        vehicleAndPetService.deleteMyVehicle(vehicleId, propertyId, UUID.fromString(userIdStr));
        log.info("Residente {} elimino vehiculo {} de propiedad {}", userIdStr, vehicleId, propertyId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Vehiculo eliminado exitosamente"));
    }

    // ─── Invitaciones ─────────────────────────────────────────────

    
    
    @DeleteMapping("/people/{personId}")
    @Transactional
    @Operation(summary = "Terminar contrato/estadía de un residente/arrendatario")
    public ResponseEntity<ApiResponse<Void>> terminatePerson(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID personId) {
        ResidentContext context = getAuthenticatedContext(userIdStr);
        UUID propertyId = context.propertyId();
        residentService.terminateResident(personId, propertyId, UUID.fromString(userIdStr));
        return ResponseEntity.ok(ApiResponse.ok(null, "Residente terminado exitosamente"));
    }

    @PatchMapping("/people/{personId}/reactivate")
    @Transactional
    @Operation(summary = "Reactivar acceso a un residente/arrendatario previamente revocado")
    public ResponseEntity<ApiResponse<Void>> reactivatePerson(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID personId) {
        ResidentContext context = getAuthenticatedContext(userIdStr);
        UUID propertyId = context.propertyId();
        residentService.reactivateResident(personId, propertyId, UUID.fromString(userIdStr));
        return ResponseEntity.ok(ApiResponse.ok(null, "Acceso reactivado exitosamente"));
    }

@GetMapping("/people")
    @Operation(summary = "Listar personas de mi propiedad (residentes e invitaciones)")
    @Transactional(readOnly = true)
    
    

    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyPeople(
            @AuthenticationPrincipal String userIdStr) {
        ResidentContext context = getAuthenticatedContext(userIdStr);
        UUID propertyId = context.propertyId();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID currentUserId = UUID.fromString(userIdStr);

        // Identificar el rol del usuario autenticado en esta unidad específica
        List<Resident> myResidents = residentRepository.findByUserIdAndTenantId(currentUserId, tenantId);
        Resident myRel = myResidents.stream()
                .filter(r -> r.getProperty().getId().equals(propertyId) && r.getStatus() == ResidentStatus.ACTIVE)
                .findFirst()
                .orElse(null);
        String myRole = myRel != null ? myRel.getType() : "Residente";
        UUID myResidentId = myRel != null ? myRel.getId() : null;

        List<Map<String, Object>> result = new java.util.ArrayList<>();

        // 1. Obtener residentes activos
        residentRepository.findByPropertyIdAndStatus(propertyId, ResidentStatus.ACTIVE)
            .forEach(r -> {
                Map<String, Object> info = new LinkedHashMap<>();
                boolean isSelf = r.getId().equals(myResidentId);
                boolean canRemove = false;

                if (!isSelf && myRel != null) {
                    if (myRole.equalsIgnoreCase("Propietario")) {
                        if (r.getType().equalsIgnoreCase("Arrendatario")) {
                            canRemove = true;
                        } else if (r.getType().equalsIgnoreCase("Residente")) {
                            // Propietario solo remueve a sus propios residentes, no a los del arrendatario
                            boolean isUnderTenant = r.getParentResident() != null && r.getParentResident().getType().equalsIgnoreCase("Arrendatario");
                            canRemove = !isUnderTenant;
                        }
                    } else if (myRole.equalsIgnoreCase("Arrendatario")) {
                        // Arrendatario solo puede revocar a residentes creados por él
                        boolean grantedByMe = (r.getParentResident() != null && r.getParentResident().getId().equals(myResidentId))
                                || (r.getGrantedBy() != null && r.getGrantedBy().getId().equals(currentUserId));
                        canRemove = r.getType().equalsIgnoreCase("Residente") && grantedByMe;
                    }
                }

                info.put("id", r.getId());
                String resolvedName = (r.getUser() != null && r.getUser().getFullName() != null && !r.getUser().getFullName().isBlank())
                        ? r.getUser().getFullName()
                        : (r.getName() != null ? r.getName() : "Usuario");
                info.put("name", resolvedName);
                info.put("email", r.getEmail() != null ? r.getEmail() : (r.getUser() != null ? r.getUser().getEmail() : ""));
                info.put("type", r.getType());
                info.put("status", r.getStatus()); // ACTIVE
                info.put("isPending", false);
                info.put("isSelf", isSelf);
                info.put("canRemove", canRemove);
                info.put("parentResidentId", r.getParentResident() != null ? r.getParentResident().getId() : null);
                info.put("parentName", r.getParentResident() != null ? r.getParentResident().getName() : null);
                info.put("parentType", r.getParentResident() != null ? r.getParentResident().getType() : null);
                result.add(info);
            });

        // 1b. Obtener residentes inactivos (historial para reactivación)
        residentRepository.findByPropertyIdAndStatus(propertyId, ResidentStatus.INACTIVE)
            .forEach(r -> {
                Map<String, Object> info = new LinkedHashMap<>();
                boolean canReactivate = myRole.equalsIgnoreCase("Propietario");

                info.put("id", r.getId());
                String resolvedInactiveName = (r.getUser() != null && r.getUser().getFullName() != null && !r.getUser().getFullName().isBlank())
                        ? r.getUser().getFullName()
                        : (r.getName() != null ? r.getName() : "Usuario");
                info.put("name", resolvedInactiveName);
                info.put("email", r.getEmail() != null ? r.getEmail() : (r.getUser() != null ? r.getUser().getEmail() : ""));
                info.put("type", r.getType());
                info.put("status", r.getStatus()); // INACTIVE
                info.put("isPending", false);
                info.put("isSelf", false);
                info.put("canRemove", false);
                info.put("canReactivate", canReactivate);
                info.put("endedAt", r.getEndedAt() != null ? r.getEndedAt().toString() : null);
                info.put("endReason", r.getEndReason());
                info.put("parentResidentId", r.getParentResident() != null ? r.getParentResident().getId() : null);
                info.put("parentName", r.getParentResident() != null ? r.getParentResident().getName() : null);
                info.put("parentType", r.getParentResident() != null ? r.getParentResident().getType() : null);
                result.add(info);
            });

        // 2. Invitaciones pendientes de hogar. SEC-7: nunca se devuelve el token (solo existe al emitirlo). Las OWNER de
        //    la administracion y los traspasos no se listan aqui; el correo del invitado solo lo ve quien puede gestionarla.
        residentInvitationRepository.findByTenantIdAndPropertyId(tenantId, propertyId).stream()
            .filter(i -> i.getStatus() == com.havita.modules.resident.domain.InvitationStatus.PENDING)
            .filter(com.havita.modules.resident.application.ResidentInvitationService::isHouseholdInvitation)
            .forEach(i -> {
                Map<String, Object> info = new LinkedHashMap<>();
                boolean canCancel = myRel != null
                        && com.havita.modules.resident.application.ResidentInvitationService.canCancelFromHome(i, currentUserId, myRole);
                boolean isMine = currentUserId.equals(i.getInviterUserId());

                info.put("id", i.getId());
                info.put("name", i.getInviteeName() != null && !i.getInviteeName().isBlank() ? i.getInviteeName() : "Invitado");
                info.put("email", (canCancel || isMine) && i.getInviteeEmail() != null ? i.getInviteeEmail() : "");
                info.put("type", i.getInvitationType() != null ? i.getInvitationType() : "Residente");
                info.put("status", i.getStatus());
                info.put("expiresAt", i.getExpiresAt());
                info.put("isPending", true);
                info.put("canRemove", canCancel);
                info.put("canRegenerate", isMine);
                result.add(info);
            });

        return ResponseEntity.ok(ApiResponse.ok(result));
    }


    @PostMapping("/invitations")
    @Transactional
    @Operation(summary = "Invitar a un residente a mi propiedad (Fase 6)")
    public ResponseEntity<ApiResponse<com.havita.modules.resident.api.dto.ResidentInvitationResponse>> inviteResident(
            @AuthenticationPrincipal String userIdStr,
            @RequestBody Map<String, String> payload) {
        String type = payload.get("type"); // "Arrendatario" o "Residente"
        String email = payload.get("email");
        String name = payload.get("name");
        String phone = payload.get("phone");
        String propertyIdStr = payload.get("propertyId");
        
        // Fase 2 / E1: la unidad del cuerpo (o, si falta, la seleccionada) pasa por el resolvedor: debe ser una relacion
        // ACTIVE del usuario. Antes bastaba cualquier relacion, tambien una revocada, y un id mal formado daba 500.
        UUID targetPropertyId = unitResolver.resolveCurrent(
                com.havita.security.ResidentUnitResolver.parseUnitId(propertyIdStr)).propertyId();
        
        return ResponseEntity.ok(ApiResponse.ok(
            invitationService.generateHouseholdInvitation(targetPropertyId, email, name, phone, type)
        ));
    }

    @DeleteMapping("/invitations/{invitationId}")
    @Transactional
    @Operation(summary = "Cancelar una invitación")
    public ResponseEntity<ApiResponse<Void>> cancelInvitation(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID invitationId) {
        ResidentContext context = getAuthenticatedContext(userIdStr);
        UUID propertyId = context.propertyId();
        
        invitationService.cancelInvitation(invitationId, propertyId, UUID.fromString(userIdStr));
        return ResponseEntity.ok(ApiResponse.ok(null, "Invitación cancelada exitosamente"));
    }

    @PostMapping("/invitations/{invitationId}/regenerate")
    @Transactional
    @Operation(summary = "Generar un nuevo enlace para una invitación pendiente que emití (revoca el anterior)")
    public ResponseEntity<ApiResponse<com.havita.modules.resident.api.dto.ResidentInvitationResponse>> regenerateInvitation(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID invitationId) {
        ResidentContext context = getAuthenticatedContext(userIdStr);
        return ResponseEntity.ok(ApiResponse.ok(
                invitationService.regenerateHouseholdInvitation(invitationId, context.propertyId(), UUID.fromString(userIdStr)),
                "Nuevo enlace de invitación generado"));
    }

    // ─── Mis Mascotas ─────────────────────────────────────────────────────────────

    @GetMapping("/pets")
    @Operation(summary = "Listar mis mascotas")
    public ResponseEntity<ApiResponse<List<PetResponse>>> getMyPets(
            @AuthenticationPrincipal String userIdStr) {
        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();
        return ResponseEntity.ok(ApiResponse.ok(vehicleAndPetService.findMyPetsByProperty(propertyId, UUID.fromString(userIdStr))));
    }

    @PostMapping("/pets")
    @Operation(summary = "Registrar mi mascota")
    public ResponseEntity<ApiResponse<PetResponse>> addMyPet(
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody ResidentCreatePetRequest request) {

        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();

        // Buscar el residentId del usuario actual para la propiedad actual
        UUID residentId = context.residentId(); // relacion ACTIVE del usuario con esta unidad

        // IDOR prevention: propertyId viene del JWT, nunca del cliente
        CreatePetRequest secureRequest = new CreatePetRequest(
                propertyId, residentId,
                request.name(), request.type(), request.breed(),
                request.color(), request.isDangerousBreed(), null, null
        );

        PetResponse response = vehicleAndPetService.createPet(secureRequest);
        log.info("Residente {} (Resident ID {}) registro mascota {} en propiedad {}", userIdStr, residentId, request.name(), propertyId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Mascota registrada exitosamente"));
    }

    @PutMapping("/pets/{petId}")
    @Operation(summary = "Actualizar mi mascota")
    public ResponseEntity<ApiResponse<PetResponse>> updateMyPet(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID petId,
            @Valid @RequestBody ResidentCreatePetRequest request) {

        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();

        UUID residentId = context.residentId(); // relacion ACTIVE del usuario con esta unidad

        CreatePetRequest secureRequest = new CreatePetRequest(
                propertyId, residentId,
                request.name(), request.type(), request.breed(),
                request.color(), request.isDangerousBreed(), null, null
        );

        PetResponse response = vehicleAndPetService.updateMyPet(petId, propertyId, UUID.fromString(userIdStr), secureRequest);
        log.info("Residente {} actualizo mascota {} en propiedad {}", userIdStr, petId, propertyId);
        return ResponseEntity.ok(ApiResponse.ok(response, "Mascota actualizada exitosamente"));
    }

    @DeleteMapping("/pets/{petId}")
    @Operation(summary = "Eliminar mi mascota")
    public ResponseEntity<ApiResponse<Void>> deleteMyPet(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID petId) {

        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();
        // IDOR: deleteMyPet verifica que petId pertenece a user.propertyId Y al residente
        vehicleAndPetService.deleteMyPet(petId, propertyId, UUID.fromString(userIdStr));
        log.info("Residente {} elimino mascota {} de propiedad {}", userIdStr, petId, propertyId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Mascota eliminada exitosamente"));
    }

    // ─── Mis Visitas ──────────────────────────────────────────────────────────────

    @GetMapping("/visitors")
    @Operation(summary = "Listar mis visitas pre-autorizadas")
    public ResponseEntity<ApiResponse<List<com.havita.modules.access.api.dto.VisitorResponse>>> getMyVisitors(
            @AuthenticationPrincipal String userIdStr) {
        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();
        return ResponseEntity.ok(ApiResponse.ok(visitorService.findAccessibleVisitors(propertyId, UUID.fromString(userIdStr))));
    }

    @PostMapping("/visitors")
    @Operation(summary = "Pre-autorizar visitante para mi propiedad")
    public ResponseEntity<ApiResponse<com.havita.modules.access.api.dto.VisitorResponse>> addMyVisitor(
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody ResidentCreateVisitorRequest request) {

        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();

        java.time.OffsetDateTime parsedExpectedAt = parseFlexibleDateTime(request.expectedAt());

        // IDOR prevention: propertyId resuelto del usuario autenticado
        com.havita.modules.access.api.dto.CreateVisitorRequest secureRequest = 
                new com.havita.modules.access.api.dto.CreateVisitorRequest(
                        propertyId,
                        null,
                        request.name(),
                        request.documentId(),
                        parsedExpectedAt,
                        request.vehiclePlate(),
                        request.observations(),
                        request.groupId(),
                        request.groupName(),
                        request.visitPurpose()
                );

        com.havita.modules.access.api.dto.VisitorResponse response = 
                visitorService.create(secureRequest, true); // true = pre-autorizado por residente

        log.info("Residente {} pre-autorizo visita {} para su propiedad {}", userIdStr, request.name(), propertyId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Visita pre-autorizada exitosamente"));
    }

    private java.time.OffsetDateTime parseFlexibleDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            // Intentar formato estricto ISO-8601
            return java.time.OffsetDateTime.parse(text);
        } catch (Exception ignored) {}

        try {
            // Intentar LocalDateTime con timezone local de Colombia (-05:00)
            java.time.ZoneOffset zoneOffset = java.time.ZoneOffset.of("-05:00");
            java.time.LocalDate today = java.time.LocalDate.now(zoneOffset);

            String lower = text.trim().toLowerCase();
            java.time.LocalDate targetDate = today;

            java.util.regex.Matcher dateMatcher = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(text);
            if (dateMatcher.find()) {
                int year = Integer.parseInt(dateMatcher.group(1));
                int month = Integer.parseInt(dateMatcher.group(2));
                int day = Integer.parseInt(dateMatcher.group(3));
                targetDate = java.time.LocalDate.of(year, month, day);
            } else if (lower.startsWith("mañana") || lower.startsWith("manana")) {
                targetDate = today.plusDays(1);
            } else if (lower.startsWith("hoy")) {
                targetDate = today;
            }

            // Extraer hora si existe: ej. "6:00 am", "04:30 pm", "16:00"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) {
                int hour = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                String ampm = m.group(3);
                if (ampm != null) {
                    if (ampm.equalsIgnoreCase("pm") && hour < 12) hour += 12;
                    if (ampm.equalsIgnoreCase("am") && hour == 12) hour = 0;
                }
                return targetDate.atTime(hour, min).atOffset(zoneOffset);
            }

            return targetDate.atTime(12, 0).atOffset(zoneOffset);
        } catch (Exception e) {
            log.warn("No se pudo parsear expectedAt '{}', usando hora actual: {}", text, e.getMessage());
            return java.time.OffsetDateTime.now(java.time.ZoneOffset.of("-05:00"));
        }
    }

    @PutMapping("/visitors/{visitorId}")
    @Operation(summary = "Actualizar/reprogramar visita de mi propiedad")
    public ResponseEntity<ApiResponse<com.havita.modules.access.api.dto.VisitorResponse>> updateMyVisitor(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID visitorId,
            @RequestBody ResidentUpdateVisitorRequest request) {

        ResidentContext context = getAuthenticatedContext(userIdStr);
        java.time.OffsetDateTime parsedExpectedAt = request.expectedAt() != null ? parseFlexibleDateTime(request.expectedAt()) : null;

        com.havita.modules.access.api.dto.VisitorResponse response = visitorService.updateAccessibleVisitor(
                visitorId,
                context.propertyId(),
                UUID.fromString(userIdStr),
                request.name(),
                request.documentId(),
                request.vehiclePlate(),
                request.observations(),
                request.visitPurpose(),
                parsedExpectedAt
        );

        return ResponseEntity.ok(ApiResponse.ok(response, "Visita actualizada exitosamente"));
    }

    @DeleteMapping("/visitors/{visitorId}")
    @Operation(summary = "Eliminar o cancelar visita de mi propiedad")
    public ResponseEntity<ApiResponse<Void>> deleteMyVisitor(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID visitorId) {

        ResidentContext context = getAuthenticatedContext(userIdStr); User user = context.user(); UUID propertyId = context.propertyId();
        // IDOR: deleteVisitor valida que la visita pertenezca a user.propertyId
        visitorService.deleteAccessibleVisitor(visitorId, propertyId, UUID.fromString(userIdStr));
        log.info("Residente {} elimino visita {} de propiedad {}", userIdStr, visitorId, propertyId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Visita eliminada exitosamente"));
    }

    // ─── Onboarding ───────────────────────────────────────────────────────────────

    @PatchMapping("/onboarding")
    @Operation(summary = "Marcar onboarding como completado")
    public ResponseEntity<ApiResponse<Void>> completeOnboarding(
            @AuthenticationPrincipal String userIdStr) {

        UUID userId = UUID.fromString(userIdStr);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setHasCompletedOnboarding(true);
        userRepository.save(user);
        log.info("Residente {} completo el onboarding", userId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Onboarding completado"));
    }

    // ─── DTOs internos (sin propertyId — se resuelve del JWT) ────────────────────

    public record ResidentCreateVehicleRequest(
            @NotBlank(message = "La placa es obligatoria") @Size(max = 20) String plate,
            @Size(max = 30) String type,
            @Size(max = 50) String brand,
            @Size(max = 50) String model,
            @Size(max = 30) String color,
            @Size(max = 50) String parkingSpot
    ) {}

    public record ResidentCreatePetRequest(
            @NotBlank(message = "El nombre de la mascota es obligatorio") @Size(max = 100) String name,
            @Size(max = 30) String type,
            @Size(max = 50) String breed,
            @Size(max = 30) String color,
            Boolean isDangerousBreed
    ) {}

    public record ResidentUpdateVisitorRequest(
            String name,
            String documentId,
            String expectedAt,
            String vehiclePlate,
            String observations,
            String visitPurpose
    ) {}

    public record ResidentCreateVisitorRequest(
            @NotBlank(message = "El nombre del visitante es obligatorio") @Size(max = 150) String name,
            @NotBlank(message = "El documento es obligatorio") @Size(max = 50) String documentId,
            String expectedAt,
            @Size(max = 20) String vehiclePlate,
            String observations,
            @Size(max = 100) String groupId,
            @Size(max = 150) String groupName,
            @Size(max = 150) String visitPurpose
    ) {}
}





