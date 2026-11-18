package com.havita.modules.pqrs.application;

import com.havita.modules.pqrs.domain.PqrsTimelineEventStatus;
import com.havita.modules.pqrs.domain.PqrsRequestStatus;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.pqrs.api.dto.AddPqrsCommentRequest;
import com.havita.modules.pqrs.api.dto.CreatePqrsRequest;
import com.havita.modules.pqrs.api.dto.PqrsResponse;
import com.havita.modules.pqrs.domain.PqrsRequest;
import com.havita.modules.pqrs.domain.PqrsTimelineEvent;
import com.havita.modules.pqrs.infrastructure.PqrsRequestRepository;
import com.havita.modules.pqrs.infrastructure.PqrsTimelineEventRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.identity.application.TenantUserResolver;
import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;



@Slf4j
@Service
@RequiredArgsConstructor
public class PqrsService {

    private final PqrsRequestRepository pqrsRequestRepository;
    private final PqrsTimelineEventRepository timelineEventRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final UserRepository userRepository;
    private final AccessValidator accessValidator;
    private final TenantUserResolver tenantUserResolver;

    @Transactional(readOnly = true)
    public Page<PqrsResponse> findAll(String search, PqrsRequestStatus status, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<PqrsRequest> page;

        if (search != null && !search.isBlank()) {
            page = pqrsRequestRepository.searchPqrs(tenantId, search.trim(), pageable);
        } else if (status != null) {
            page = pqrsRequestRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = pqrsRequestRepository.findByTenantId(tenantId, pageable);
        }

        return page.map(p -> toResponse(p, false));
    }

    @Transactional(readOnly = true)
    public PqrsResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        PqrsRequest request = pqrsRequestRepository.findByIdAndTenantId(id, tenantId)
                .filter(this::isVisibleToCaller)
                .orElseThrow(() -> new ResourceNotFoundException("PqrsRequest", id));
        return toResponse(request, true);
    }

    @Transactional(readOnly = true)
    public List<PqrsResponse> findByProperty(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return pqrsRequestRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                .map(p -> toResponse(p, false))
                .toList();
    }

    @Transactional
    public PqrsResponse create(CreatePqrsRequest request, UUID creatorUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        boolean residentCaller = accessValidator.isResidentCaller();
        if (residentCaller && !accessValidator.isActiveResidentOfProperty(request.propertyId())) {
            throw new AccessDeniedException("No tiene permisos para radicar solicitudes de este inmueble.");
        }

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        Resident resident = null;
        if (request.residentId() != null) {
            resident = residentRepository.findByIdAndTenantId(request.residentId(), tenantId).orElse(null);
            if (residentCaller && resident != null && !resident.getProperty().getId().equals(property.getId())) {
                throw new AccessDeniedException("El residente indicado no pertenece a este inmueble.");
            }
        }

        User creator = null;
        if (creatorUserId != null) {
            creator = userRepository.findById(creatorUserId).orElse(null);
        }

        PqrsRequest pqrs = PqrsRequest.builder()
                .property(property)
                .resident(resident)
                .createdByUser(creator)
                .category(request.category() != null ? request.category() : "Otro")
                .title(request.title().trim())
                .description(request.description().trim())
                .priority(request.priority() != null ? request.priority() : "Media")
                .status(PqrsRequestStatus.NEW)
                .build();
        pqrs.setTenantId(tenantId);

        pqrs = pqrsRequestRepository.save(pqrs);

        // Crear primer evento en la línea de tiempo
        PqrsTimelineEvent initialEvent = PqrsTimelineEvent.builder()
                .pqrs(pqrs)
                .user(creator)
                .authorName(creator != null ? creator.getFullName() : (resident != null ? resident.getName() : "Residente"))
                .authorRole("RESIDENT")
                .comment("Solicitud radicada en el sistema.")
                .statusChangedTo(PqrsTimelineEventStatus.NEW)
                .build();
        initialEvent.setTenantId(tenantId);
        timelineEventRepository.save(initialEvent);

        log.info("PQRS creada: '{}' para propiedad {}", pqrs.getTitle(), property.getNumber());
        return toResponse(pqrs);
    }

    @Transactional
    public PqrsResponse update(UUID id, com.havita.modules.pqrs.api.dto.UpdatePqrsRequest request, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        PqrsRequest pqrs = pqrsRequestRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("PQRS", id));
        
        if (request.title() != null && !request.title().isBlank()) pqrs.setTitle(request.title().trim());
        if (request.description() != null && !request.description().isBlank()) pqrs.setDescription(request.description().trim());
        if (request.category() != null && !request.category().isBlank()) pqrs.setCategory(request.category());
        if (request.priority() != null && !request.priority().isBlank()) pqrs.setPriority(request.priority());
        
        if (request.status() != null && !request.status().isBlank()) {
            PqrsRequestStatus newStatus = PqrsRequestStatus.valueOf(request.status());
            if (pqrs.getStatus() != newStatus) {
                pqrs.setStatus(newStatus);
                PqrsTimelineEventStatus timelineStatus = PqrsTimelineEventStatus.valueOf(request.status());
                PqrsTimelineEvent event = PqrsTimelineEvent.builder()
                        .pqrs(pqrs)
                        .statusChangedTo(timelineStatus)
                        .comment("Estado actualizado a " + newStatus)
                        .authorName("Sistema")
                        .authorRole("ADMIN")
                        .build();
                event.setTenantId(tenantId);
                timelineEventRepository.save(event);
            }
        }
        
        pqrs = pqrsRequestRepository.save(pqrs);
        return toResponse(pqrs, true);
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        PqrsRequest pqrs = pqrsRequestRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("PQRS", id));
        pqrsRequestRepository.delete(pqrs);
    }

    @Transactional
    public PqrsResponse assignTechnician(UUID id, UUID technicianUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        PqrsRequest pqrs = pqrsRequestRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("PqrsRequest", id));

        User tech = tenantUserResolver.requireUserInTenant(technicianUserId, tenantId);

        pqrs.startProgress(tech);
        pqrs = pqrsRequestRepository.save(pqrs);

        PqrsTimelineEvent event = PqrsTimelineEvent.builder()
                .pqrs(pqrs)
                .user(tech)
                .authorName(tech.getFullName())
                .authorRole("STAFF")
                .comment("Asignado a: " + tech.getFullName())
                .statusChangedTo(PqrsTimelineEventStatus.IN_PROGRESS)
                .build();
        event.setTenantId(tenantId);
        timelineEventRepository.save(event);

        log.info("PQRS {} asignada a técnico {}", id, tech.getId());
        return toResponse(pqrs);
    }

    @Transactional
    public PqrsResponse resolve(UUID id, String resolutionNotes, UUID authorUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        PqrsRequest pqrs = pqrsRequestRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("PqrsRequest", id));

        User author = authorUserId != null ? userRepository.findById(authorUserId).orElse(null) : null;

        pqrs.resolve(resolutionNotes);
        pqrs = pqrsRequestRepository.save(pqrs);

        PqrsTimelineEvent event = PqrsTimelineEvent.builder()
                .pqrs(pqrs)
                .user(author)
                .authorName(author != null ? author.getFullName() : "Administración")
                .authorRole("COMMUNITY_ADMIN")
                .comment(resolutionNotes != null ? resolutionNotes : "Solicitud marcada como resuelta.")
                .statusChangedTo(PqrsTimelineEventStatus.RESOLVED)
                .build();
        event.setTenantId(tenantId);
        timelineEventRepository.save(event);

        log.info("PQRS {} marcada como resuelta", id);
        return toResponse(pqrs);
    }

    @Transactional
    public PqrsResponse addComment(UUID id, AddPqrsCommentRequest request, UUID authorUserId, String authorRole) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        PqrsRequest pqrs = pqrsRequestRepository.findByIdAndTenantId(id, tenantId)
                .filter(this::isVisibleToCaller)
                .orElseThrow(() -> new ResourceNotFoundException("PqrsRequest", id));

        boolean changesStatus = request.changeStatusTo() != null && !request.changeStatusTo().isBlank();
        // Cambiar el estado es gestion (PUT y resolve no admiten RESIDENT): un residente solo comenta.
        if (changesStatus && accessValidator.isResidentCaller()) {
            throw new AccessDeniedException("Solo la administracion puede cambiar el estado de la solicitud.");
        }

        User author = authorUserId != null ? userRepository.findById(authorUserId).orElse(null) : null;

        if (changesStatus) {
            pqrs.setStatus(PqrsRequestStatus.valueOf(request.changeStatusTo().trim()));
            pqrs = pqrsRequestRepository.save(pqrs);
        }

        PqrsTimelineEvent event = PqrsTimelineEvent.builder()
                .pqrs(pqrs)
                .user(author)
                .authorName(author != null ? author.getFullName() : "Usuario")
                .authorRole(authorRole != null ? authorRole : "USER")
                .comment(request.comment().trim())
                .statusChangedTo(changesStatus ? PqrsTimelineEventStatus.valueOf(request.changeStatusTo().trim()) : null)
                .build();
        event.setTenantId(tenantId);
        timelineEventRepository.save(event);

        log.info("Comentario agregado a PQRS {}", id);
        return toResponse(pqrs);
    }

    private PqrsResponse toResponse(PqrsRequest p) {
        return toResponse(p, true);
    }

    private PqrsResponse toResponse(PqrsRequest p, boolean includeTimeline) {
        List<PqrsResponse.PqrsTimelineEventResponse> timeline = java.util.List.of();
        
        if (includeTimeline) {
            timeline = timelineEventRepository.findByPqrsIdOrderByCreatedAtAsc(p.getId())
                    .stream()
                    .map(e -> new PqrsResponse.PqrsTimelineEventResponse(
                            e.getId(),
                            e.getAuthorName(),
                            e.getAuthorRole(),
                            e.getComment(),
                            e.getStatusChangedTo() != null ? PqrsRequestStatus.valueOf(e.getStatusChangedTo().name()) : null,
                            e.getCreatedAt()
                    ))
                    .toList();
        }

        return new PqrsResponse(
                p.getId(),
                p.getTenantId(),
                p.getProperty().getId(),
                p.getProperty().getNumber(),
                p.getProperty().getName(),
                p.getResident() != null ? p.getResident().getId() : null,
                p.getResident() != null ? p.getResident().getName() : null,
                p.getCategory(),
                p.getTitle(),
                p.getDescription(),
                p.getStatus(),
                p.getPriority(),
                p.getResponseNotes(),
                p.getAssignedToUser() != null ? p.getAssignedToUser().getId() : null,
                p.getAssignedToUser() != null ? p.getAssignedToUser().getFullName() : null,
                p.getResolvedAt(),
                p.getClosedAt(),
                timeline,
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    /**
     * Roles de gestion: cualquier PQRS de su comunidad. RESIDENT: las de una unidad en la que hoy tiene relacion
     * y membresia ACTIVE (AccessValidator consulta PostgreSQL, no el JWT).
     */
    private boolean isVisibleToCaller(PqrsRequest pqrs) {
        return !accessValidator.isResidentCaller()
                || accessValidator.isActiveResidentOfProperty(pqrs.getProperty().getId());
    }
}
