package com.havita.modules.communication.application;
import com.havita.modules.communication.domain.CommunicationStatus;

import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.communication.api.dto.CommunicationResponse;
import com.havita.modules.communication.api.dto.CreateCommunicationRequest;
import com.havita.modules.communication.domain.Communication;
import com.havita.modules.communication.infrastructure.CommunicationRepository;
import com.havita.modules.sync.application.SseService;
import com.havita.modules.sync.domain.SyncEvent;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.resident.domain.Resident;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageImpl;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommunicationService {

    private final CommunicationRepository communicationRepository;
    private final UserRepository userRepository;
    private final SseService sseService;
    private final ResidentRepository residentRepository;
    private final com.havita.security.AccessValidator accessValidator;
    /** Avisos push a residentes (se envian tras el commit). */
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public Page<CommunicationResponse> findAll(String search, String audience, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<Communication> page;

        if (search != null && !search.isBlank()) {
            page = communicationRepository.searchCommunications(tenantId, search.trim(), pageable);
        } else if (audience != null && !audience.isBlank()) {
            page = communicationRepository.findPublishedForAudience(tenantId, audience.trim(), pageable);
        } else {
            page = communicationRepository.findByTenantId(tenantId, pageable);
        }

        // --- Visibilidad: borradores y archivadas solo para la gestion; residentes segun su audiencia ---
        java.util.function.Predicate<Communication> visible = visibilityFor(tenantId);
        if (visible != null) {
            List<Communication> filtered = page.getContent().stream().filter(visible).collect(Collectors.toList());
            page = new PageImpl<>(filtered, pageable, filtered.size());
        }

        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CommunicationResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        java.util.function.Predicate<Communication> visible = visibilityFor(tenantId);
        Communication comm = communicationRepository.findByIdAndTenantId(id, tenantId)
                .filter(c -> visible == null || visible.test(c))
                .orElseThrow(() -> new ResourceNotFoundException("Communication", id));
        return toResponse(comm);
    }

    @Transactional
    public CommunicationResponse create(CreateCommunicationRequest request, UUID authorUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        User author = null;
        String authorName = "Administración";
        if (authorUserId != null) {
            author = userRepository.findById(authorUserId).orElse(null);
            if (author != null) {
                authorName = author.getFullName();
            }
        }

        CommunicationStatus initialStatus = request.status() != null ? request.status() : CommunicationStatus.PUBLISHED;
        OffsetDateTime publishedAt = CommunicationStatus.PUBLISHED == initialStatus ? OffsetDateTime.now() : null;

        Communication comm = Communication.builder()
                .author(author)
                .authorName(authorName)
                .title(request.title().trim())
                .content(request.content().trim())
                .audience(request.audience() != null ? request.audience() : "Todos")
                .category(request.category() != null ? request.category() : "General")
                .status(initialStatus)
                .publishedAt(publishedAt)
                .build();
        comm.setTenantId(tenantId);

        comm = communicationRepository.save(comm);
        log.info("Comunicado creado: '{}' (Audiencia: {})", comm.getTitle(), comm.getAudience());
        
        if (initialStatus == CommunicationStatus.PUBLISHED) {
            sseService.publishEvent(new SyncEvent(
                    "COMMUNICATION_CREATED",
                    tenantId,
                    Map.of("id", comm.getId())
            ));
            pushCommunication(tenantId, comm);
        }
        
        return toResponse(comm);
    }

    @Transactional
    public CommunicationResponse publish(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Communication comm = communicationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Communication", id));

        comm.publish();
        comm = communicationRepository.save(comm);
        log.info("Comunicado publicado: {}", id);
        
        sseService.publishEvent(new SyncEvent(
                "COMMUNICATION_CREATED",
                tenantId,
                Map.of("id", comm.getId())
        ));
        pushCommunication(tenantId, comm);
        
        return toResponse(comm);
    }

    /**
     * Aviso push del comunicado a los residentes activos: «Propietarios» o «Arrendatarios» solo a ese tipo de relacion;
     * «Todos», «General» u otra audiencia, a todos. Texto fijo (el contenido se lee en la app).
     */
    private void pushCommunication(UUID tenantId, Communication comm) {
        String audience = comm.getAudience() == null ? "" : comm.getAudience().trim();
        String residentType = switch (audience) {
            case "Propietarios" -> "Propietario";
            case "Arrendatarios" -> "Arrendatario";
            default -> null;
        };
        events.publishEvent(com.havita.modules.push.application.ResidentPushRequested.community(tenantId, residentType,
                "Hay un nuevo comunicado de la administración", "COMMUNICATION", "/resident/community"));
    }

    @Transactional
    public CommunicationResponse archive(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Communication comm = communicationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Communication", id));

        comm.archive();
        comm = communicationRepository.save(comm);
        log.info("Comunicado archivado: {}", id);
        return toResponse(comm);
    }

    @Transactional
    public CommunicationResponse update(UUID id, com.havita.modules.communication.api.dto.UpdateCommunicationRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Communication comm = communicationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Communication", id));

        if (request.title() != null) comm.setTitle(request.title().trim());
        if (request.content() != null) comm.setContent(request.content().trim());
        if (request.audience() != null) comm.setAudience(request.audience());
        if (request.category() != null) comm.setCategory(request.category());
        if (request.status() != null) comm.setStatus(request.status());

        comm = communicationRepository.save(comm);
        log.info("Comunicado actualizado: {}", id);
        return toResponse(comm);
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Communication comm = communicationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Communication", id));
        communicationRepository.delete(comm);
        log.info("Comunicado eliminado: {}", id);
    }

    /**
     * Regla de lectura del llamante: null para SUPER_ADMIN y COMMUNITY_ADMIN (gestionan borradores y archivadas);
     * los demas roles solo ven comunicaciones PUBLISHED y un RESIDENT, ademas, solo las de su audiencia.
     */
    private java.util.function.Predicate<Communication> visibilityFor(UUID tenantId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN") || a.getAuthority().equals("ROLE_COMMUNITY_ADMIN"));
        if (isAdmin) {
            return null;
        }
        boolean isResidentOnly = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_RESIDENT"));
        if (!isResidentOnly) {
            return c -> c.getStatus() == CommunicationStatus.PUBLISHED;
        }
        UUID userId = UUID.fromString(auth.getName());
        // Solo cuentan las relaciones ACTIVE de una membresia ACTIVE (AccessValidator consulta PostgreSQL).
        java.util.Set<UUID> activeUnits = accessValidator.activePropertyIds();
        if (activeUnits.isEmpty()) {
            return c -> false;
        }
        List<Resident> residentProps = residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getProperty() != null && activeUnits.contains(r.getProperty().getId())
                        && r.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE)
                .toList();
        
        boolean isPropietario = residentProps.stream().anyMatch(r -> r.getType() != null && r.getType().toLowerCase().contains("propietario"));
        boolean isArrendatario = residentProps.stream().anyMatch(r -> r.getType() != null && (r.getType().toLowerCase().contains("arrendatario") || r.getType().toLowerCase().contains("inquilino")));
        
        // Fase 2 / E3: la audiencia "visual" guarda el nombre EXACTO de la estructura ("Torre 1"; "Sin Torre" para las
        // unidades sin estructura) o la ruta/nombre exacto de una unidad. Antes se comparaba con contains() y "Torre 1"
        // coincidia con "Torre 10". Ahora se compara exacto (sin distinguir mayusculas) contra esos identificadores.
        java.util.Set<String> unitKeys = new java.util.HashSet<>();
        for (Resident r : residentProps) {
            com.havita.modules.property.domain.Property p = r.getProperty();
            if (p == null) continue;
            unitKeys.add(normalizeAudience(p.getStructure() != null ? p.getStructure().getName() : "Sin Torre"));
            if (p.getPath() != null) unitKeys.add(normalizeAudience(p.getPath()));
            if (p.getName() != null) unitKeys.add(normalizeAudience(p.getName()));
            if (p.getNumber() != null) unitKeys.add(normalizeAudience(p.getNumber()));
        }

        return c -> {
            if (c.getStatus() != CommunicationStatus.PUBLISHED) return false;
            
            String aud = c.getAudience() == null ? "Todos" : c.getAudience();
            if (aud.equals("Todos") || aud.equals("General")) return true;
            if (aud.equals("Propietarios") && isPropietario) return true;
            if (aud.equals("Arrendatarios") && isArrendatario) return true;
            if (aud.equals("Propietarios") && !isPropietario) return false;
            if (aud.equals("Arrendatarios") && !isArrendatario) return false;
            
            for (String t : aud.split(",")) {
                if (!t.isBlank() && unitKeys.contains(normalizeAudience(t))) return true;
            }
            return false;
        };
    }

    /** Clave de comparacion de audiencia: espacios normalizados y sin distinguir mayusculas. */
    static String normalizeAudience(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private CommunicationResponse toResponse(Communication c) {
        return new CommunicationResponse(
                c.getId(),
                c.getTenantId(),
                c.getAuthor() != null ? c.getAuthor().getId() : null,
                c.getAuthorName(),
                c.getTitle(),
                c.getContent(),
                c.getAudience(),
                c.getCategory(),
                c.getStatus(),
                c.getPublishedAt(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
