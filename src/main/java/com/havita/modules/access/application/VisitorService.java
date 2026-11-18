package com.havita.modules.access.application;

import com.havita.modules.access.domain.VisitorStatus;

import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.access.api.dto.CreateVisitorRequest;
import com.havita.modules.access.api.dto.VisitorResponse;
import com.havita.modules.access.domain.Visitor;
import com.havita.modules.access.infrastructure.VisitorRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitorService {

    private final VisitorRepository visitorRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final com.havita.modules.sync.application.SyncEventPublisher syncEventPublisher;
    private final com.havita.modules.identity.infrastructure.UserRepository userRepository;
    private final VisitorSettingsService visitorSettingsService;
    /** Avisos push a los residentes de la unidad (se envian tras el commit). */
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public Page<VisitorResponse> findAll(String search, VisitorStatus status, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<Visitor> page;

        if (search != null && !search.isBlank()) {
            page = visitorRepository.searchVisitors(tenantId, search.trim(), pageable);
        } else if (status != null) {
            page = visitorRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = visitorRepository.findByTenantId(tenantId, pageable);
        }

        java.util.Map<UUID, String> names = staffNames(page.getContent());
        return page.map(v -> toStaffResponse(v, names));
    }

    @Transactional(readOnly = true)
    public VisitorResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Visitor visitor = visitorRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Visitor", id));
        return toStaffResponse(visitor);
    }

    @Transactional(readOnly = true)
    public List<VisitorResponse> findByProperty(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        List<Visitor> visitors = visitorRepository.findByTenantIdAndPropertyId(tenantId, propertyId);
        java.util.Map<UUID, String> names = staffNames(visitors);
        return visitors.stream().map(v -> toStaffResponse(v, names)).toList();
    }

    @Transactional
    public VisitorResponse create(CreateVisitorRequest request, boolean isPreAuthorized) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        // Validar horario de visitas según configuración del tenant solo si es pre-autorizada
        if (isPreAuthorized) {
            validateVisitSchedule(tenantId, request.expectedAt());
        }

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        Resident resident = null;
        if (request.residentId() != null) {
            resident = residentRepository.findByIdAndTenantId(request.residentId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Resident", request.residentId()));
        }

        VisitorStatus initialStatus = isPreAuthorized ? VisitorStatus.AUTHORIZED : VisitorStatus.PENDING;
        String generatedQr;
        int attempts = 0;
        do {
            generatedQr = new java.security.SecureRandom().ints(16, 0, 36)
                    .mapToObj(i -> Integer.toString(i, 36))
                    .collect(java.util.stream.Collectors.joining()).toUpperCase();
            attempts++;
            if (attempts > 10) throw new com.havita.common.exception.BusinessRuleConflictException("QR_GENERATION_FAILED", "No se pudo generar un código único.");
        } while (visitorRepository.existsByQrCode(generatedQr));

        UUID currentUserId = UUID.fromString(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName());

        // Si no se especifica expectedAt (ej. registro manual en portería), asignar la fecha y hora actual
        OffsetDateTime effectiveExpectedAt = request.expectedAt() != null 
                ? request.expectedAt() 
                : OffsetDateTime.now(java.time.ZoneOffset.UTC);

        Visitor visitor = Visitor.builder()
                .property(property)
                .resident(resident)
                .name(request.name().trim())
                .documentId(request.documentId().trim())
                .status(initialStatus)
                .expectedAt(effectiveExpectedAt)
                .vehiclePlate(request.vehiclePlate() != null ? request.vehiclePlate().trim().toUpperCase() : null)
                .observations(request.observations())
                .qrCode(generatedQr)
                .groupId(request.groupId())
                .groupName(request.groupName())
                .visitPurpose(request.visitPurpose())
                .createdByUserId(currentUserId)
                .build();
        visitor.setTenantId(tenantId);
        visitor = visitorRepository.save(visitor);
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_CREATED",
                tenantId,
                java.util.Map.of("visitor", toResponse(visitor)),
                com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)
            )
        );

        log.info("Visita registrada: {} para {} (Estado: {})", visitor.getName(), property.getNumber(), initialStatus);
        return toResponse(visitor);
    }

    @Transactional
    public VisitorResponse registerEntry(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID userId = UUID.fromString(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName());
        Visitor visitor = visitorRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Visitor", id));

        // 1. Validaciones de estado actual
        if (visitor.getStatus() == VisitorStatus.CANCELLED) {
            throw new com.havita.common.exception.BusinessRuleConflictException("VISITOR_CANCELLED", "Esta visita fue cancelada por el residente.");
        }
        if (visitor.getStatus() == VisitorStatus.ENTERED) {
            throw new com.havita.common.exception.BusinessRuleConflictException("ALREADY_ENTERED", "El visitante ya registró su ingreso.");
        }
        if (visitor.getStatus() == VisitorStatus.EXITED) {
            throw new com.havita.common.exception.BusinessRuleConflictException("ALREADY_EXITED", "Esta visita ya finalizó.");
        }

        // 2. Validación en tiempo real de la ventana de 12 horas
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        OffsetDateTime refTime = visitor.getExpectedAt() != null ? visitor.getExpectedAt() : visitor.getCreatedAt();
        if (now.isAfter(refTime.plusHours(12))) {
            if (visitor.getStatus() != VisitorStatus.EXPIRED) {
                visitor.setStatus(VisitorStatus.EXPIRED);
                visitorRepository.save(visitor);
                VisitorResponse expResponse = toResponse(visitor);
                syncEventPublisher.publish(
                    new com.havita.modules.sync.domain.SyncEvent(
                        "VISITOR_STATUS_CHANGED",
                        tenantId,
                        java.util.Map.of("visitor", expResponse, "visitorId", visitor.getId().toString(), "status", visitor.getStatus().name()),
                        com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)
                    )
                );
            }
            throw new com.havita.common.exception.BusinessRuleConflictException("QR_EXPIRED", "El pase de visita ha expirado (superó las 12 horas de validez).");
        }

        // 3. Validación de visita futura (no permitir ingreso antes de la fecha programada)
        if (visitor.getExpectedAt() != null) {
            java.time.ZoneOffset colZone = java.time.ZoneOffset.of("-05:00");
            java.time.LocalDate scheduledDate = visitor.getExpectedAt().atZoneSameInstant(colZone).toLocalDate();
            java.time.LocalDate today = now.atZoneSameInstant(colZone).toLocalDate();
            if (scheduledDate.isAfter(today.plusDays(1))) {
                throw new com.havita.common.exception.BusinessRuleConflictException("FUTURE_VISIT", 
                    "Esta visita está programada para el " + scheduledDate + " y aún no es válida para ingreso hoy.");
            }
        }

        visitor.registerEntry(userId);
        visitor = visitorRepository.save(visitor);

        VisitorResponse entryResponse = toStaffResponse(visitor);
        publishStatusChanged(tenantId, visitor, entryResponse);
        if (visitor.getProperty() != null) {
            // Aviso con el celular bloqueado: texto fijo, sin el nombre del visitante.
            events.publishEvent(com.havita.modules.push.application.ResidentPushRequested.unit(tenantId,
                    visitor.getProperty().getId(), "Tu visita ingresó al conjunto", "VISITOR_ENTERED", "/resident/visitors"));
        }

        log.info("Ingreso registrado: {} a las {} por el usuario {}", visitor.getName(), visitor.getEnteredAt(), userId);
        return entryResponse;
    }

    @Transactional
    public VisitorResponse registerExit(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID userId = UUID.fromString(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName());
        Visitor visitor = visitorRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Visitor", id));

        visitor.registerExit(userId);
        visitor = visitorRepository.save(visitor);

        VisitorResponse exitResponse = toStaffResponse(visitor);
        publishStatusChanged(tenantId, visitor, exitResponse);

        log.info("Salida registrada: {} a las {} por el usuario {}", visitor.getName(), visitor.getExitedAt(), userId);
        return exitResponse;
    }

    /**
     * Ingreso o salida: la unidad (residentes y gestion) recibe el evento SIN el nombre del personal; la gestion recibe
     * ademas una copia con quien registro el ingreso o la salida. El cliente fusiona ambas (los nombres solo se
     * aplican cuando vienen).
     */
    private void publishStatusChanged(UUID tenantId, Visitor visitor, VisitorResponse staffResponse) {
        UUID propertyId = visitor.getProperty() != null ? visitor.getProperty().getId() : null;
        VisitorResponse unitResponse = staffResponse.withStaffNames(null, null);
        syncEventPublisher.publish(new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_STATUS_CHANGED", tenantId,
                java.util.Map.of("visitor", unitResponse, "visitorId", visitor.getId().toString(), "status", visitor.getStatus().name()),
                com.havita.modules.sync.domain.SyncAudience.unit(propertyId)));
        syncEventPublisher.publish(new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_STATUS_CHANGED", tenantId,
                java.util.Map.of("visitor", staffResponse, "visitorId", visitor.getId().toString(), "status", visitor.getStatus().name()),
                com.havita.modules.sync.domain.SyncAudience.staff()));
    }

    @Transactional
    public VisitorResponse authorize(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Visitor visitor = visitorRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Visitor", id));

        visitor.authorize();
        visitor = visitorRepository.save(visitor);
        
        VisitorResponse authResponse = toResponse(visitor);
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_STATUS_CHANGED",
                tenantId,
                java.util.Map.of("visitor", authResponse, "visitorId", visitor.getId().toString(), "status", visitor.getStatus().name()),
                com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)
            )
        );

        log.info("Visita autorizada: {}", visitor.getName());
        return authResponse;
    }

    @Transactional
    public void deleteVisitor(UUID id, UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Visitor visitor = visitorRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Visitor", id));
                
        if (propertyId != null && !visitor.getProperty().getId().equals(propertyId)) {
            throw new ResourceNotFoundException("Visitor", id);
        }

        if (visitor.getStatus() == VisitorStatus.ENTERED || visitor.getStatus() == VisitorStatus.EXITED) {
            throw new com.havita.common.exception.BusinessRuleConflictException("VISITOR_ALREADY_LOGGED", "No se puede cancelar o eliminar una visita que ya ingresó o salió de la propiedad.");
        }

        visitor.cancel();
        visitor = visitorRepository.save(visitor);

        VisitorResponse responseDto = toResponse(visitor);
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_STATUS_CHANGED",
                tenantId,
                java.util.Map.of("visitor", responseDto, "visitorId", visitor.getId().toString(), "status", visitor.getStatus().name()),
                com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)
            )
        );
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_UPDATED",
                tenantId,
                java.util.Map.of("visitor", responseDto, "visitorId", visitor.getId().toString(), "status", visitor.getStatus().name()),
                com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)
            )
        );
        log.info("Visita cancelada: {} (id: {})", visitor.getName(), visitor.getId());
    }

    @Transactional
    public void deleteAccessibleVisitor(UUID id, UUID propertyId, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Visitor visitor = visitorRepository.findAccessibleVisitorById(id, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Visitor", id));
                
        if (propertyId != null && !visitor.getProperty().getId().equals(propertyId)) {
            throw new ResourceNotFoundException("Visitor", id);
        }

        if (visitor.getStatus() == VisitorStatus.ENTERED || visitor.getStatus() == VisitorStatus.EXITED) {
            throw new com.havita.common.exception.BusinessRuleConflictException("VISITOR_ALREADY_LOGGED", "No se puede cancelar o eliminar una visita que ya ingresó o salió de la propiedad.");
        }

        visitor.cancel();
        visitor = visitorRepository.save(visitor);

        VisitorResponse responseDto = toResponse(visitor);
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_STATUS_CHANGED",
                tenantId,
                java.util.Map.of("visitor", responseDto, "visitorId", visitor.getId().toString(), "status", visitor.getStatus().name()),
                com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)
            )
        );
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_UPDATED",
                tenantId,
                java.util.Map.of("visitor", responseDto, "visitorId", visitor.getId().toString(), "status", visitor.getStatus().name()),
                com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)
            )
        );
        log.info("Visita accesible cancelada: {} (id: {}) por usuario {}", visitor.getName(), visitor.getId(), userId);
    }

    @Transactional(readOnly = true)
    public List<VisitorResponse> findAccessibleVisitors(UUID propertyId, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return visitorRepository.findAccessibleVisitors(tenantId, propertyId, userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Administracion y porteria editan los datos de un visitante de SU comunidad (otra comunidad: 404). Antes la pantalla
     * solo lo cambiaba en el navegador. No se edita una visita que ya salio o se cancelo (historial); la unidad y el
     * estado no cambian aqui.
     */
    @Transactional
    public VisitorResponse updateAsStaff(UUID id, com.havita.modules.access.api.dto.UpdateVisitorRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Visitor visitor = visitorRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Visitor", id));
        if (visitor.getStatus() == VisitorStatus.EXITED || visitor.getStatus() == VisitorStatus.CANCELLED) {
            throw new com.havita.common.exception.BusinessRuleConflictException("VISITOR_CLOSED",
                    "No se puede modificar una visita que ya salió o fue cancelada.");
        }
        visitor.setName(request.name().trim());
        visitor.setDocumentId(request.documentId().trim());
        visitor.setVehiclePlate(request.vehiclePlate() == null || request.vehiclePlate().isBlank()
                ? null : request.vehiclePlate().trim().toUpperCase());
        visitor.setObservations(request.observations() == null || request.observations().isBlank() ? null : request.observations().trim());
        if (request.visitPurpose() != null && !request.visitPurpose().isBlank()) visitor.setVisitPurpose(request.visitPurpose().trim());
        visitor = visitorRepository.save(visitor);

        VisitorResponse staffResponse = toStaffResponse(visitor);
        syncEventPublisher.publish(new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_UPDATED", tenantId,
                java.util.Map.of("visitor", toResponse(visitor), "visitorId", visitor.getId().toString(), "status", visitor.getStatus()),
                com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)));
        log.info("Visita {} actualizada por el personal", visitor.getId());
        return staffResponse;
    }

    @Transactional
    public VisitorResponse updateAccessibleVisitor(UUID id, UUID propertyId, UUID userId,
                                                  String name, String documentId, String vehiclePlate,
                                                  String observations, String visitPurpose,
                                                  OffsetDateTime expectedAt) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Visitor visitor = visitorRepository.findAccessibleVisitorById(id, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Visitor", id));

        if (propertyId != null && !visitor.getProperty().getId().equals(propertyId)) {
            throw new ResourceNotFoundException("Visitor", id);
        }

        if (visitor.getStatus() == VisitorStatus.ENTERED || visitor.getStatus() == VisitorStatus.EXITED) {
            throw new com.havita.common.exception.BusinessRuleConflictException(
                "VISITOR_ALREADY_LOGGED",
                "No se puede modificar una visita que ya ingresó o salió de la propiedad.");
        }

        if (expectedAt != null) {
            validateVisitSchedule(tenantId, expectedAt);
            visitor.setExpectedAt(expectedAt);
            // Si estaba como expirada y se reprograma a fecha futura o vigente, reactivarla
            OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            if (now.isBefore(expectedAt.plusHours(12))) {
                if (visitor.getStatus() == VisitorStatus.EXPIRED) {
                    visitor.setStatus(VisitorStatus.AUTHORIZED);
                }
            }
        }

        if (name != null && !name.isBlank()) visitor.setName(name.trim());
        if (documentId != null && !documentId.isBlank()) visitor.setDocumentId(documentId.trim());
        if (vehiclePlate != null) visitor.setVehiclePlate(vehiclePlate.trim().toUpperCase());
        if (observations != null) visitor.setObservations(observations);
        if (visitPurpose != null && !visitPurpose.isBlank()) visitor.setVisitPurpose(visitPurpose.trim());

        visitor = visitorRepository.save(visitor);

        VisitorResponse responseDto = toResponse(visitor);
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_UPDATED",
                tenantId,
                java.util.Map.of("visitor", responseDto, "visitorId", visitor.getId().toString(), "status", visitor.getStatus()),
                com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)
            )
        );
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "VISITOR_STATUS_CHANGED",
                tenantId,
                java.util.Map.of("visitor", responseDto, "visitorId", visitor.getId().toString(), "status", visitor.getStatus()),
                com.havita.modules.sync.domain.SyncAudience.unit(visitor.getProperty() != null ? visitor.getProperty().getId() : null)
            )
        );

        log.info("Visita actualizada: {} con fecha {}", visitor.getName(), visitor.getExpectedAt());
        return toResponse(visitor);
    }

    private void validateVisitSchedule(UUID tenantId, OffsetDateTime expectedAt) {
        Optional<com.havita.modules.access.domain.VisitorSettings> settingsOpt =
                visitorSettingsService.findRawSettings(tenantId);

        // Sin configuración → modo 24h implícito, siempre permitido
        if (settingsOpt.isEmpty()) return;

        com.havita.modules.access.domain.VisitorSettings settings = settingsOpt.get();

        // Modo 24h → siempre permitido
        if (settings.getMode() == com.havita.modules.access.domain.VisitorScheduleMode.HOURS_24) return;

        // Modo CUSTOM: si no hay expectedAt no se puede validar contra el horario
        if (expectedAt == null) {
            throw new com.havita.common.exception.BusinessRuleConflictException(
                    "VISIT_TIME_REQUIRED",
                    "Este conjunto requiere una hora aproximada de llegada para pre-autorizar visitas.");
        }

        java.time.ZoneOffset zoneOffset = java.time.ZoneOffset.of("-05:00");
        java.time.DayOfWeek dayOfWeek = expectedAt.atZoneSameInstant(zoneOffset).toLocalDate().getDayOfWeek();
        java.time.LocalTime time = expectedAt.atZoneSameInstant(zoneOffset).toLocalTime();

        com.havita.modules.access.domain.VisitorDaySchedule daySchedule = settings.getDaySchedules().stream()
                .filter(d -> d.getDayOfWeek() == dayOfWeek)
                .findFirst()
                .orElse(null);

        if (daySchedule == null || !daySchedule.isEnabled()) {
            throw new com.havita.common.exception.BusinessRuleConflictException(
                    "VISIT_DAY_NOT_ALLOWED",
                    "Las visitas no están permitidas ese día para este conjunto.");
        }

        if (time.isBefore(daySchedule.getStartTime()) || time.isAfter(daySchedule.getEndTime())) {
            throw new com.havita.common.exception.BusinessRuleConflictException(
                    "VISIT_TIME_NOT_ALLOWED",
                    "El horario seleccionado no está permitido para las visitas de este conjunto.");
        }
    }

    /** Para portería y administración: incluye quién registró el ingreso y la salida. */
    private VisitorResponse toStaffResponse(Visitor visitor) {
        return toStaffResponse(visitor, staffNames(List.of(visitor)));
    }

    private VisitorResponse toStaffResponse(Visitor visitor, java.util.Map<UUID, String> names) {
        return toResponse(visitor).withStaffNames(
                visitor.getEnteredBy() != null ? names.get(visitor.getEnteredBy()) : null,
                visitor.getExitedBy() != null ? names.get(visitor.getExitedBy()) : null);
    }

    /** Nombres de quienes registraron ingresos y salidas, en una sola consulta. */
    private java.util.Map<UUID, String> staffNames(java.util.Collection<Visitor> visitors) {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (Visitor v : visitors) {
            if (v.getEnteredBy() != null) ids.add(v.getEnteredBy());
            if (v.getExitedBy() != null) ids.add(v.getExitedBy());
        }
        if (ids.isEmpty()) return java.util.Map.of();
        java.util.Map<UUID, String> out = new java.util.HashMap<>();
        for (com.havita.modules.identity.domain.User u : userRepository.findAllById(ids)) {
            if (u.getFullName() != null && !u.getFullName().isBlank()) out.put(u.getId(), u.getFullName());
        }
        return out;
    }

    /** Sin datos del personal: residentes, pase público y eventos que recibe la unidad. */
    private VisitorResponse toResponse(Visitor visitor) {
        return new VisitorResponse(
                visitor.getId(),
                visitor.getTenantId(),
                visitor.getProperty() != null ? visitor.getProperty().getId() : null,
                visitor.getProperty() != null ? visitor.getProperty().getNumber() : null,
                visitor.getProperty() != null ? (visitor.getProperty().getPath() != null && !visitor.getProperty().getPath().isBlank() ? visitor.getProperty().getPath() : visitor.getProperty().getName()) : null, // propertyName
                visitor.getResident() != null ? visitor.getResident().getId() : null,
                visitor.getCreatedByUserId(),
                visitor.getResident() != null && visitor.getResident().getUser() != null ? visitor.getResident().getUser().getFullName() : (visitor.getCreatedByUserId() != null ? userRepository.findById(visitor.getCreatedByUserId()).map(com.havita.modules.identity.domain.User::getFullName).orElse(null) : null), // residentName
                visitor.getName(),
                visitor.getDocumentId(),
                visitor.getStatus(), // VisitorStatus
                visitor.getExpectedAt(),
                visitor.getEnteredAt(),
                visitor.getExitedAt(),
                visitor.getVehiclePlate(),
                visitor.getObservations(),
                visitor.getQrCode(),
                visitor.getGroupId(),
                visitor.getGroupName(),
                visitor.getVisitPurpose(),
                visitor.getCreatedAt(),
                visitor.getUpdatedAt()
        );
    }
}





