package com.havita.modules.resident.application;

import com.havita.modules.property.domain.PropertyStatus;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.domain.ResidentTypes;
import java.util.Map;
import com.havita.modules.sync.domain.SyncEvent;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.identity.application.TenantUserResolver;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.api.dto.CreateResidentRequest;
import com.havita.modules.resident.api.dto.ResidentResponse;
import com.havita.modules.resident.api.dto.UpdateResidentRequest;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResidentService {

    private final com.havita.security.AccessValidator accessValidator;
    private final TenantUserResolver tenantUserResolver;

    /** Motivo de baja cuando la decide la administracion (DELETE o PUT status=INACTIVE). */
    public static final String ADMIN_REMOVAL = "ADMIN_REMOVAL";
    @Transactional
    public void terminateResident(UUID residentId, UUID propertyId, UUID endedByUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        Resident target = residentRepository.findByIdAndTenantId(residentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Resident", residentId));

        if (!target.getProperty().getId().equals(propertyId)) {
            throw new BusinessRuleConflictException("UNAUTHORIZED_PROPERTY", "El residente no pertenece a esta propiedad.");
        }

        User endedByUser = userRepository.findById(endedByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", endedByUserId));

        // Obtener la relación del usuario que ejecuta la acción en esta unidad
        List<Resident> actorResidents = residentRepository.findByUserIdAndTenantId(endedByUserId, tenantId).stream()
                .filter(r -> r.getProperty().getId().equals(propertyId) && r.getStatus() == ResidentStatus.ACTIVE)
                .toList();

        Resident actorRel = actorResidents.isEmpty() ? null : actorResidents.get(0);

        if (actorRel != null) {
            String actorType = actorRel.getType();
            String targetType = target.getType();

            if (targetType.equalsIgnoreCase("Propietario")) {
                throw new BusinessRuleConflictException("TENANT_CANNOT_REMOVE_OWNER",
                        "Un arrendatario o residente no puede remover al propietario de la unidad.");
            }

            if (actorType.equalsIgnoreCase("Residente")) {
                throw new BusinessRuleConflictException("UNAUTHORIZED_ACCESS_REVOCATION",
                        "Los co-residentes no tienen permisos para revocar accesos en esta unidad.");
            }

            if (actorType.equalsIgnoreCase("Arrendatario")) {
                if (target.getId().equals(actorRel.getId())) {
                    throw new BusinessRuleConflictException("INVALID_OPERATION",
                            "Un arrendatario no puede auto-revocarse a través de este método.");
                }
                boolean wasGrantedByActor = (target.getParentResident() != null && target.getParentResident().getId().equals(actorRel.getId()))
                        || (target.getGrantedBy() != null && target.getGrantedBy().getId().equals(endedByUserId));

                if (!wasGrantedByActor) {
                    throw new BusinessRuleConflictException("UNAUTHORIZED_ACCESS_REVOCATION",
                            "No tienes permisos para revocar a un residente que no fue agregado por ti.");
                }
            }

            if (actorType.equalsIgnoreCase("Propietario")) {
                if (targetType.equalsIgnoreCase("Residente")) {
                    boolean isUnderTenant = target.getParentResident() != null && target.getParentResident().getType().equalsIgnoreCase("Arrendatario");
                    if (isUnderTenant) {
                        throw new BusinessRuleConflictException("OWNER_CANNOT_REMOVE_TENANT_RESIDENT",
                                "El propietario no puede remover directamente a los residentes del arrendatario. Para revocar su acceso, debes remover al arrendatario.");
                    }
                }
            }
        }

        String endReason = actorRel != null ? "TERMINATED_BY_" + actorRel.getType().toUpperCase() : "TERMINATED_BY_ADMIN";
        endRelation(target, propertyId, endedByUser, endReason);
    }

    /**
     * Baja logica de una relacion residente-unidad: INACTIVE con fecha, autor y motivo. No borra nada: reservas,
     * PQRS, facturas, votos, asistencias, llamadas, alertas, visitas, vehiculos, mascotas e invitaciones siguen
     * apuntando a este registro. Invalida la delegacion de voto afectada, aplica la cascada a los dependientes de
     * un arrendatario, revoca sus invitaciones pendientes y publica los eventos de revocacion.
     */
    private void endRelation(Resident target, UUID propertyId, User endedByUser, String endReason) {
        UUID tenantId = target.getTenantId();
        // 1. Revocar acceso del target
        target.setStatus(ResidentStatus.INACTIVE);
        target.setEndedAt(java.time.OffsetDateTime.now());
        target.setEndedBy(endedByUser);
        target.setEndReason(endReason);
        residentRepository.save(target);
        
        // Invalidar delegación de voto si aplica
        if (target.getType().equalsIgnoreCase("Propietario") || target.getType().equalsIgnoreCase("Arrendatario")) {
            votingDelegationRepository.findByPropertyIdAndStatusAndTenantId(
                propertyId, 
                com.havita.modules.assembly.domain.VotingDelegationStatus.ACTIVE, 
                tenantId
            ).ifPresent(active -> {
                boolean invalidate = false;
                if (target.getType().equalsIgnoreCase("Propietario") && target.getUser() != null && active.getOwnerUser().getId().equals(target.getUser().getId())) {
                    invalidate = true;
                }
                if (target.getType().equalsIgnoreCase("Arrendatario") && target.getUser() != null && active.getDelegateUser().getId().equals(target.getUser().getId())) {
                    invalidate = true;
                }
                if (invalidate) {
                    active.setStatus(com.havita.modules.assembly.domain.VotingDelegationStatus.INVALIDATED);
                    active.setRevokedAt(java.time.OffsetDateTime.now());
                    votingDelegationRepository.save(active);
                    log.info("Delegación de voto invalidada debido a remoción del residente {}", target.getId());
                }
            });
        }

        List<String> affectedUserIds = new java.util.ArrayList<>();
        if (target.getUser() != null) {
            affectedUserIds.add(target.getUser().getId().toString());
        } else if (target.getEmail() != null && !target.getEmail().isBlank()) {
            userRepository.findByEmailIgnoreCase(target.getEmail().trim())
                    .ifPresent(u -> affectedUserIds.add(u.getId().toString()));
        }

        // 2. Si el target es Arrendatario, cascada a los residentes dependientes respetando múltiples orígenes
        if (target.getType().equalsIgnoreCase("Arrendatario")) {
            List<Resident> allUnitResidents = residentRepository.findByPropertyIdAndStatus(propertyId, ResidentStatus.ACTIVE);

            for (Resident dep : allUnitResidents) {
                boolean isDependent = (dep.getParentResident() != null && dep.getParentResident().getId().equals(target.getId()))
                        || (dep.getGrantedBy() != null && target.getUser() != null && dep.getGrantedBy().getId().equals(target.getUser().getId()));

                if (isDependent) {
                    // Evaluar si tiene otro origen activo válido otorgado por el Propietario
                    boolean hasOwnerOrigin = allUnitResidents.stream().anyMatch(other ->
                            !other.getId().equals(dep.getId()) &&
                            other.getUser() != null && dep.getUser() != null &&
                            other.getUser().getId().equals(dep.getUser().getId()) &&
                            (other.getParentResident() == null || other.getParentResident().getType().equalsIgnoreCase("Propietario"))
                    );

                    if (!hasOwnerOrigin) {
                        dep.setStatus(ResidentStatus.INACTIVE);
                        dep.setEndedAt(java.time.OffsetDateTime.now());
                        dep.setEndedBy(endedByUser);
                        dep.setEndReason("CASCADED_TENANT_TERMINATION");
                        residentRepository.save(dep);
                        if (dep.getUser() != null) {
                            affectedUserIds.add(dep.getUser().getId().toString());
                        } else if (dep.getEmail() != null && !dep.getEmail().isBlank()) {
                            userRepository.findByEmailIgnoreCase(dep.getEmail().trim())
                                    .ifPresent(u -> affectedUserIds.add(u.getId().toString()));
                        }
                    }
                }
            }

            // Revocar invitaciones pendientes generadas por este arrendatario
            if (target.getUser() != null) {
                List<com.havita.modules.resident.domain.ResidentInvitation> pendingInvites =
                        invitationRepository.findByPropertyIdAndInviterUserIdAndStatus(propertyId, target.getUser().getId(), com.havita.modules.resident.domain.InvitationStatus.PENDING);
                for (var inv : pendingInvites) {
                    inv.setStatus(com.havita.modules.resident.domain.InvitationStatus.REVOKED);
                    inv.setRevokedAt(java.time.OffsetDateTime.now());
                    invitationRepository.save(inv);
                }
            }
        }

        // 3. Sesiones (D-4): los refresh tokens son globales (no por comunidad), asi que una baja local no los revoca;
        //    cerraria las sesiones del usuario en sus otras comunidades. El acceso se corta en cada peticion contra
        //    PostgreSQL (AccessValidator para la unidad, ResidentAccessGate para la comunidad) y en las conexiones SSE.
        lifecycleAudit.relationEnded(target, endReason);

        // Destinatarios: la gestion y los residentes activos de la unidad reciben el aviso para refrescar datos,
        // sin identificadores ni correos; cada afectado recibe ademas su aviso (con affectedUserIds, que es lo que
        // el frontend usa para cerrar su sesion) aunque ya no tenga relacion con la unidad. No se envian correos.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("propertyId", propertyId.toString());
        payload.put("terminatedResidentId", target.getId().toString());
        payload.put("targetType", target.getType());
        // affectedUserIds solo incluye a quien se queda sin ninguna relacion ACTIVE en la comunidad: el frontend
        // cierra la sesion de esos usuarios. Quien conserva otras unidades recibe el aviso sin su id y solo refresca.
        java.util.List<UUID> affectedIds = affectedUserIds.stream().distinct().map(UUID::fromString).toList();
        java.util.List<String> lostCommunityAccess = affectedIds.stream()
                .filter(uid -> residentRepository.findByUserIdAndTenantId(uid, tenantId).stream()
                        .noneMatch(r -> r.getStatus() == ResidentStatus.ACTIVE))
                .map(UUID::toString)
                .toList();
        Map<String, Object> affectedPayload = new java.util.HashMap<>(payload);
        affectedPayload.put("affectedUserIds", lostCommunityAccess);

        String relationEvent = target.getType().equalsIgnoreCase("Arrendatario") ? "TENANT_RELATION_ENDED" : "RESIDENT_RELATION_ENDED";
        for (String type : java.util.List.of("RESIDENT_ACCESS_REVOKED", relationEvent)) {
            syncEventPublisher.publish(new SyncEvent(type, target.getTenantId(), affectedPayload,
                    com.havita.modules.sync.domain.SyncAudience.users(affectedIds)));
            syncEventPublisher.publish(new SyncEvent(type, target.getTenantId(), payload,
                    com.havita.modules.sync.domain.SyncAudience.unit(propertyId)));
        }
    }

    @Transactional
    public void reactivateResident(UUID personId, UUID propertyId, UUID actorUserId) {
        Resident target = residentRepository.findByIdAndTenantId(personId, TenantContextHolder.getRequiredTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Resident", personId));

        if (!target.getProperty().getId().equals(propertyId)) {
            throw new ResourceNotFoundException("Resident", personId);
        }

        if (target.getStatus() == ResidentStatus.ACTIVE) {
            throw new BusinessRuleConflictException("ALREADY_ACTIVE", "El residente ya se encuentra activo en esta unidad.");
        }

        // Una baja decidida por la administracion solo la deshace la administracion.
        if (ADMIN_REMOVAL.equals(target.getEndReason())) {
            lifecycleAudit.reactivationDenied(target, "OWNER");
            throw new BusinessRuleConflictException("REACTIVATION_REQUIRES_ADMIN",
                    "Este acceso fue retirado por la administración. Solo la administración puede reactivarlo.");
        }

        User actorUser = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", actorUserId));

        // Validar rol del actor
        List<Resident> actorResidents = residentRepository.findByUserIdAndTenantId(actorUserId, target.getTenantId());
        Resident actorRel = actorResidents.stream()
                .filter(r -> r.getProperty().getId().equals(propertyId) && r.getStatus() == ResidentStatus.ACTIVE)
                .findFirst()
                .orElse(null);

        if (actorRel == null || !actorRel.getType().equalsIgnoreCase("Propietario")) {
            throw new BusinessRuleConflictException("UNAUTHORIZED_REACTIVATION", "Solo el propietario puede reactivar accesos en esta propiedad.");
        }

        validateReactivation(target, propertyId);
        lifecycleAudit.relationReactivated(target, "OWNER");

        // Reactivar
        target.setStatus(ResidentStatus.ACTIVE);
        target.setEndedAt(null);
        target.setEndedBy(null);
        target.setEndReason(null);
        target.setGrantedBy(actorUser);
        target.setParentResident(actorRel);
        residentRepository.save(target);

        Map<String, Object> reactivatePayload = new java.util.HashMap<>();
        reactivatePayload.put("propertyId", propertyId.toString());
        reactivatePayload.put("residentId", target.getId().toString());
        if (target.getUser() != null) {
            reactivatePayload.put("userId", target.getUser().getId().toString());
        }
        // Sin correo: la gestion y la unidad refrescan sus datos; el reactivado lo recibe aunque su conexion aun no
        // haya revalidado la nueva relacion.
        syncEventPublisher.publish(new SyncEvent("RESIDENT_RELATION_REACTIVATED", target.getTenantId(), reactivatePayload,
                com.havita.modules.sync.domain.SyncAudience.unit(propertyId)));
        if (target.getUser() != null) {
            syncEventPublisher.publish(new SyncEvent("RESIDENT_RELATION_REACTIVATED", target.getTenantId(), reactivatePayload,
                    com.havita.modules.sync.domain.SyncAudience.users(java.util.List.of(target.getUser().getId()))));
        }
    }


    private final ResidentRepository residentRepository;
    private final PropertyRepository propertyRepository;
    private final com.havita.modules.property.infrastructure.StructureRepository structureRepository;
    private final UserRepository userRepository;
    private final com.havita.modules.sync.application.SyncEventPublisher syncEventPublisher;
    private final com.havita.modules.identity.application.GlobalEmailValidationService globalEmailValidationService;
    private final com.havita.modules.resident.infrastructure.ResidentInvitationRepository invitationRepository;
    private final ResidentLifecycleAudit lifecycleAudit;
    private final com.havita.modules.assembly.infrastructure.VotingDelegationRepository votingDelegationRepository;

    @Transactional(readOnly = true)
    public Page<ResidentResponse> findAll(String search, ResidentStatus status, String type, Pageable pageable) {
        return findAll(search, status, type, null, pageable);
    }

    /** Valor de {@code structureId} que pide las unidades SIN estructura (casas, unidades sueltas). */
    public static final String WITHOUT_STRUCTURE = "none";

    /**
     * Fase 2 / E4 (D7): directorio filtrado por estructura (torre, bloque o manzana) por IDENTIFICADOR, nunca por nombre.
     * {@code structureId} nulo o vacio = todas; {@value #WITHOUT_STRUCTURE} = unidades sin estructura; un UUID debe ser
     * una estructura de ESTA comunidad (si no, 404, igual que si no existiera). El filtro va en la misma consulta paginada
     * y en su COUNT, asi que totales y paginas son correctos.
     */
    public Page<ResidentResponse> findAll(String search, ResidentStatus status, String type, String structureId, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        String structureMode = "";
        UUID structureUuid = new UUID(0L, 0L); // marcador no nulo cuando no se filtra por id
        if (structureId != null && !structureId.isBlank()) {
            if (WITHOUT_STRUCTURE.equalsIgnoreCase(structureId.trim())) {
                structureMode = "NONE";
            } else {
                try {
                    structureUuid = UUID.fromString(structureId.trim());
                } catch (IllegalArgumentException ex) {
                    throw new com.havita.common.exception.InvalidRequestException("INVALID_STRUCTURE_ID", "El identificador de estructura no es válido.");
                }
                UUID requested = structureUuid;
                structureRepository.findByIdAndTenantId(requested, tenantId)
                        .orElseThrow(() -> new ResourceNotFoundException("Structure", requested));
                structureMode = "ID";
            }
        }
        String typeFilter = type == null || type.isBlank() ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        String searchFilter = search == null || search.isBlank() ? "" : "%" + escapeLike(search.trim().toLowerCase(java.util.Locale.ROOT)) + "%";
        return residentRepository.searchDirectory(tenantId, structureMode, structureUuid, status, typeFilter, searchFilter, directoryPage(pageable))
                .map(this::toResponse);
    }

    /** Columnas por las que se puede ordenar el directorio (valor publico -> propiedad JPA). */
    private static final Map<String, String> DIRECTORY_SORT = Map.of(
            "name", "name", "type", "type", "status", "status", "createdAt", "createdAt",
            "propertyNumber", "property.number");

    /**
     * Orden determinista: el pedido (solo columnas permitidas) o, por defecto, por nombre; siempre con el id como
     * desempate, para que dos paginas no repitan ni salten filas con el mismo nombre.
     */
    private static Pageable directoryPage(Pageable pageable) {
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.unsorted();
        for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
            String property = DIRECTORY_SORT.get(order.getProperty());
            if (property != null) {
                sort = sort.and(org.springframework.data.domain.Sort.by(order.getDirection(), property));
            }
        }
        if (sort.isUnsorted()) {
            sort = org.springframework.data.domain.Sort.by("name");
        }
        sort = sort.and(org.springframework.data.domain.Sort.by("id"));
        return org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    /** La busqueda es literal: % y _ escritos por el usuario no actuan como comodines. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional(readOnly = true)
    public ResidentResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Resident resident = residentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Resident", id));
        return toResponse(resident);
    }

    @Transactional(readOnly = true)
    public List<ResidentResponse> findByProperty(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return residentRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ResidentResponse create(CreateResidentRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        // P0-C: el tipo se valida antes de escribir nada; se guarda con la grafia canonica que usan los indices unicos.
        String type = ResidentTypes.canonicalOrDefault(request.type(), ResidentTypes.OWNER);

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        // La cuenta vinculada debe pertenecer ya a esta comunidad; nunca se crea una membresia desde el censo.
        User user = request.userId() != null
                ? tenantUserResolver.requireUserInTenant(request.userId(), tenantId)
                : null;

        if (request.email() != null && !request.email().isBlank()) {
            globalEmailValidationService.validateEmailUniqueness(request.email());
            
            // If they are creating a resident without a user, we should create the user?
            // "Antes de crear cualquier usuario, el backend debe comprobar si el correo ya existe."
            // The original logic just left user as null. We can keep it that way or create a user.
            // Let's just keep the original logic for user (it will be null unless passed).
            // Wait, if we don't link, we just proceed.
        }

        Resident resident = Resident.builder()
                .property(property)
                .user(user)
                .name(request.name().trim())
                .documentType(request.documentType() != null ? request.documentType() : "CC")
                .documentId(request.documentId())
                .type(type)
                .phone(request.phone())
                .email(request.email())
                .isMainContact(request.isMainContact() != null ? request.isMainContact() : false)
                .emergencyContactName(request.emergencyContactName())
                .emergencyContactPhone(request.emergencyContactPhone())
                .status(ResidentStatus.ACTIVE)
                .build();
        resident.setTenantId(tenantId);

        resident = residentRepository.save(resident);

        // Actualizar estado del inmueble a 'OCCUPIED' si estaba desocupado
        if (property.getStatus() == PropertyStatus.VACANT || property.getStatus() == null) {
            property.setStatus(PropertyStatus.OCCUPIED);
            propertyRepository.save(property);
        }

        log.info("Residente creado: {} en inmueble {} (Tenant: {})", resident.getName(), property.getNumber(), tenantId);
        return toResponse(resident);
    }

    @Transactional
    public List<ResidentResponse> assignProperties(UUID userId, com.havita.modules.resident.api.dto.AssignPropertiesRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        // P0-C: tipo validado antes de escribir nada y en su grafia canonica.
        String assignedType = ResidentTypes.canonicalOrDefault(request.type(), ResidentTypes.OWNER);

        // El identificador puede ser un residente del censo (lo que envia la UI) o una cuenta de usuario;
        // ambos se resuelven exclusivamente dentro del tenant actual.
        Resident existingRes = residentRepository.findByIdAndTenantId(userId, tenantId).orElse(null);
        User user = existingRes != null
                ? existingRes.getUser()
                : tenantUserResolver.requireUserInTenant(userId, tenantId);

        List<Resident> currentResidents = user != null 
                ? residentRepository.findByUserIdAndTenantId(user.getId(), tenantId)
                : List.of(existingRes);
        
        List<Resident> newAssignments = new java.util.ArrayList<>();
        
        for (UUID propertyId : request.propertyIds()) {
            // Validar que la propiedad exista en el tenant actual
            Property property = propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));
            
            // Actualizar estado del inmueble a OCCUPIED
            if (property.getStatus() != PropertyStatus.OCCUPIED) {
                property.setStatus(PropertyStatus.OCCUPIED);
                propertyRepository.save(property);
            }

            // Validar que no haya un duplicado en la relación específica (idempotencia)
            boolean alreadyAssigned = currentResidents.stream().anyMatch(r -> 
                r.getProperty() != null &&
                r.getProperty().getId().equals(propertyId) && 
                r.getType().equalsIgnoreCase(assignedType)
            );
            
            Resident inactiveSameRelation = currentResidents.stream()
                .filter(r -> r.getProperty() != null && r.getProperty().getId().equals(propertyId)
                        && r.getType().equalsIgnoreCase(assignedType) && r.getStatus() == ResidentStatus.INACTIVE)
                .findFirst().orElse(null);
            boolean activeSameRelation = currentResidents.stream().anyMatch(r ->
                    r.getProperty() != null && r.getProperty().getId().equals(propertyId)
                    && r.getType().equalsIgnoreCase(assignedType) && r.getStatus() == ResidentStatus.ACTIVE);

            if (!activeSameRelation && inactiveSameRelation != null) {
                newAssignments.add(reactivateByAdmin(inactiveSameRelation));
            } else if (!alreadyAssigned) {
                Resident resident = Resident.builder()
                        .property(property)
                        .user(user)
                        // Perfil de la comunidad primero; la cuenta global solo como origen cuando no hay censo previo.
                        .name(existingRes != null ? existingRes.getName() : user.getFullName())
                        .documentType(existingRes != null && existingRes.getDocumentType() != null ? existingRes.getDocumentType() : "CC")
                        .documentId(existingRes != null && existingRes.getDocumentId() != null ? existingRes.getDocumentId() : "")
                        .type(assignedType)
                        .phone(existingRes != null ? existingRes.getPhone() : user.getPhone())
                        .email(existingRes != null ? existingRes.getEmail() : user.getEmail())
                        .isMainContact(request.isMainContact() != null ? request.isMainContact() : false)
                        .status(ResidentStatus.ACTIVE)
                        .build();
                resident.setTenantId(tenantId);
                newAssignments.add(residentRepository.save(resident));
                
                // Publish Domain Events
                if (user != null) {
                    syncEventPublisher.publish(new com.havita.modules.sync.domain.SyncEvent("OWNER_PROPERTY_ASSIGNED", tenantId, java.util.Map.of(
                            "propertyId", property.getId().toString(),
                            "userId", user.getId().toString()
                    )));
                }
            }
        }
        
        return newAssignments.stream().map(this::toResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional
    public ResidentResponse update(UUID id, UpdateResidentRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        // P0-C: tipo validado antes de modificar la entidad (null = sin cambio).
        String requestedType = ResidentTypes.canonicalOrNull(request.type());
        Resident resident = residentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Resident", id));

        if (request.propertyId() != null) {
            Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));
            resident.setProperty(property);
        }

        if (request.userId() != null) {
            resident.setUser(tenantUserResolver.requireUserInTenant(request.userId(), tenantId));
        }

        // Los datos del censo son el perfil de la persona en esta comunidad: no se propagan a la cuenta
        // global del usuario, que puede pertenecer a otras comunidades.
        if (request.name() != null) resident.setName(request.name().trim());
        if (request.documentType() != null) resident.setDocumentType(request.documentType());
        if (request.documentId() != null) resident.setDocumentId(request.documentId());
        if (requestedType != null) resident.setType(requestedType);
        if (request.phone() != null) {
            String trimmedPhone = request.phone().trim();
            resident.setPhone(trimmedPhone.isEmpty() ? null : trimmedPhone);
        }
        if (request.email() != null && !request.email().isBlank() && !request.email().equalsIgnoreCase(resident.getEmail())) {
            globalEmailValidationService.validateEmailUniqueness(request.email());
            resident.setEmail(request.email().trim());
        }
        if (request.isMainContact() != null) resident.setIsMainContact(request.isMainContact());
        if (request.emergencyContactName() != null) resident.setEmergencyContactName(request.emergencyContactName());
        if (request.emergencyContactPhone() != null) resident.setEmergencyContactPhone(request.emergencyContactPhone());
        ResidentStatus requestedStatus = request.status();
        boolean deactivate = requestedStatus == ResidentStatus.INACTIVE && resident.getStatus() == ResidentStatus.ACTIVE;
        boolean reactivate = requestedStatus == ResidentStatus.ACTIVE && resident.getStatus() == ResidentStatus.INACTIVE;

        Resident saved = residentRepository.save(resident);
        if (deactivate) {
            endRelation(saved, saved.getProperty().getId(), currentUserOrNull(), ADMIN_REMOVAL);
        } else if (reactivate) {
            saved = reactivateByAdmin(saved);
        }
        final Resident finalResident = saved;
        
        // Invalidate voting delegation if the role changed from what the delegation expects
        votingDelegationRepository.findByPropertyIdAndStatusAndTenantId(
            finalResident.getProperty().getId(), 
            com.havita.modules.assembly.domain.VotingDelegationStatus.ACTIVE, 
            tenantId
        ).ifPresent(active -> {
            boolean invalidate = false;
            // Si el residente era el propietario o el delegado, revisemos su rol
            if (finalResident.getUser() != null) {
                if (active.getOwnerUser().getId().equals(finalResident.getUser().getId()) && !finalResident.getType().equalsIgnoreCase("Propietario")) {
                    invalidate = true;
                }
                if (active.getDelegateUser().getId().equals(finalResident.getUser().getId()) && !finalResident.getType().equalsIgnoreCase("Arrendatario")) {
                    invalidate = true;
                }
            }
            if (invalidate) {
                active.setStatus(com.havita.modules.assembly.domain.VotingDelegationStatus.INVALIDATED);
                active.setRevokedAt(java.time.OffsetDateTime.now());
                votingDelegationRepository.save(active);
            }
        });

        log.info("Residente actualizado: {} (Tenant: {})", finalResident.getId(), tenantId);
        return toResponse(finalResident);
    }

    @Transactional
    public void delete(UUID id) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Resident resident = residentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Resident", id));

        // Repetir la baja no tiene efectos: no cambia fecha ni autor ni vuelve a publicar eventos.
        if (resident.getStatus() == ResidentStatus.INACTIVE) {
            log.info("Residente {} ya estaba dado de baja (Tenant: {})", id, tenantId);
            return;
        }

        // Baja de la relacion con esta unidad (no de la comunidad ni de la cuenta global): el historial se conserva
        // y las demas unidades y comunidades del usuario no se tocan.
        endRelation(resident, resident.getProperty().getId(), currentUserOrNull(), ADMIN_REMOVAL);
        log.info("Residente dado de baja por la administracion: {} (Tenant: {})", id, tenantId);
    }

    /**
     * Reactivacion por la administracion de una relacion dada de baja: misma fila (sin duplicados), solo esta
     * unidad (no restaura otras relaciones ni dependientes) y con las reglas de negocio de la reactivacion.
     */
    private Resident reactivateByAdmin(Resident target) {
        UUID propertyId = target.getProperty().getId();
        validateReactivation(target, propertyId);
        lifecycleAudit.relationReactivated(target, "ADMIN");
        target.setStatus(ResidentStatus.ACTIVE);
        target.setEndedAt(null);
        target.setEndedBy(null);
        target.setEndReason(null);
        target.setGrantedBy(currentUserOrNull());
        Resident saved = residentRepository.save(target);

        // Igual que el alta, la asignacion y la aceptacion de invitaciones: una unidad con un residente activo queda ocupada.
        Property property = saved.getProperty();
        if (property.getStatus() != PropertyStatus.OCCUPIED) {
            property.setStatus(PropertyStatus.OCCUPIED);
            propertyRepository.save(property);
        }

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("propertyId", propertyId.toString());
        payload.put("residentId", saved.getId().toString());
        if (saved.getUser() != null) {
            payload.put("userId", saved.getUser().getId().toString());
        }
        syncEventPublisher.publish(new SyncEvent("RESIDENT_RELATION_REACTIVATED", saved.getTenantId(), payload,
                com.havita.modules.sync.domain.SyncAudience.unit(propertyId)));
        if (saved.getUser() != null) {
            syncEventPublisher.publish(new SyncEvent("RESIDENT_RELATION_REACTIVATED", saved.getTenantId(), payload,
                    com.havita.modules.sync.domain.SyncAudience.users(List.of(saved.getUser().getId()))));
        }
        log.info("Residente {} reactivado por la administracion", saved.getId());
        return saved;
    }

    private User currentUserOrNull() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String principal)) {
            return null;
        }
        try {
            return userRepository.findById(UUID.fromString(principal)).orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Reglas de negocio para reactivar una relacion (comunes al propietario y a la administracion). */
    private void validateReactivation(Resident target, UUID propertyId) {
        String targetType = target.getType();

        // Regla 1: Si se reactiva un Arrendatario, validar que no haya otro arrendatario activo en la propiedad
        if (targetType.equalsIgnoreCase("Arrendatario")) {
            boolean hasActiveTenant = residentRepository.existsByPropertyIdAndTypeIgnoreCaseAndStatus(
                    propertyId, "Arrendatario", ResidentStatus.ACTIVE);
            if (hasActiveTenant) {
                throw new BusinessRuleConflictException("SECOND_ACTIVE_TENANT",
                        "Esta unidad ya tiene un arrendatario activo. Debes remover al arrendatario actual antes de reactivar a este arrendatario.");
            }
        }

        // Regla 2: Si es Residente o Arrendatario, validar que NO tenga otra unidad activa (solo 1 apto a la vez)
        if (targetType.equalsIgnoreCase("Residente") || targetType.equalsIgnoreCase("Arrendatario")) {
            UUID targetUserId = target.getUser() != null ? target.getUser().getId() : null;
            List<Resident> activeOtherUnits = residentRepository.findActiveNonOwnerResidences(targetUserId, target.getEmail(), ResidentStatus.ACTIVE)
                    .stream()
                    .filter(r -> !r.getProperty().getId().equals(propertyId))
                    .toList();

            if (!activeOtherUnits.isEmpty()) {
                // P0-C (R5): la busqueda es global; la unidad solo se nombra si es de esta misma comunidad.
                throw new BusinessRuleConflictException("ALREADY_ACTIVE_IN_ANOTHER_PROPERTY",
                        "No se puede reactivar: el usuario ya tiene una residencia activa en "
                        + ResidenceConflictMessages.otherUnit(activeOtherUnits.get(0), target.getTenantId())
                        + ". Un residente o arrendatario solo puede tener un apartamento activo a la vez.");
            }
        }

    }

    private ResidentResponse toResponse(Resident resident) {
        return new ResidentResponse(
                resident.getId(),
                resident.getTenantId(),
                resident.getProperty().getId(),
                resident.getProperty().getNumber(),
                resident.getProperty().getName(),
                resident.getUser() != null ? resident.getUser().getId() : null,
                resident.getName(),
                resident.getDocumentType(),
                resident.getDocumentId(),
                resident.getType(),
                resident.getPhone(),
                resident.getEmail(),
                resident.getIsMainContact(),
                resident.getEmergencyContactName(),
                resident.getEmergencyContactPhone(),
                resident.getStatus(),
                resident.getRegisteredAt(),
                resident.getCreatedAt(),
                resident.getUpdatedAt()
        );
    }

}
