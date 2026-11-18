package com.havita.modules.realtime.application;

import com.havita.modules.realtime.domain.AlertType;
import com.havita.modules.realtime.domain.PanicAlertStatus;
import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.realtime.api.dto.NotificationResponse;
import com.havita.modules.realtime.api.dto.PanicAlertResponse;
import com.havita.modules.realtime.api.dto.ResolvePanicAlertRequest;
import com.havita.modules.realtime.api.dto.SendNotificationRequest;
import com.havita.modules.realtime.api.dto.TriggerPanicAlertRequest;
import com.havita.modules.realtime.domain.PanicAlert;
import com.havita.modules.realtime.domain.RealtimeNotification;
import com.havita.modules.realtime.infrastructure.PanicAlertRepository;
import com.havita.modules.realtime.infrastructure.RealtimeNotificationRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.modules.sync.domain.SyncEvent;
import com.havita.modules.push.infrastructure.PushNotificationEventListener;
import com.havita.modules.identity.application.TenantUserResolver;
import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;



@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeService {

    private final RealtimeNotificationRepository notificationRepository;
    private final PanicAlertRepository panicAlertRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final ResidentRepository residentRepository;
    private final SyncEventPublisher syncEventPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final AccessValidator accessValidator;
    private final TenantUserResolver tenantUserResolver;

    // --- NOTIFICATIONS ---

    @Transactional
    public NotificationResponse sendNotification(SendNotificationRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        User user = null;
        if (request.userId() != null) {
            user = tenantUserResolver.requireUserInTenant(request.userId(), tenantId);
        }

        Property property = null;
        if (request.propertyId() != null) {
            property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));
        }

        RealtimeNotification notification = RealtimeNotification.builder()
                .user(user)
                .property(property)
                .title(request.title())
                .message(request.message())
                .type(request.type())
                .dataPayload(request.dataPayload())
                .build();
        notification.setTenantId(tenantId);

        notification = notificationRepository.save(notification);
        log.info("Push notification sent: {} to tenant {}", notification.getTitle(), tenantId);

        // Dispatch push notification event (async, after commit)
        applicationEventPublisher.publishEvent(
                new PushNotificationEventListener.NotificationCreatedEvent(notification));

        return toNotificationResponse(notification);
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> findNotifications(UUID propertyId, UUID userId, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        if (accessValidator.isResidentCaller()) {
            // Un residente consulta las de una unidad suya o, sin unidad, solo las dirigidas a el.
            UUID self = accessValidator.currentUserId();
            if (propertyId != null) {
                if (!accessValidator.isActiveResidentOfProperty(propertyId)) {
                    throw new AccessDeniedException("No tiene acceso a las notificaciones de este inmueble.");
                }
            } else if (userId != null && !userId.equals(self)) {
                throw new AccessDeniedException("No tiene acceso a las notificaciones de otro usuario.");
            } else {
                userId = self;
            }
        }
        Page<RealtimeNotification> page;
        if (propertyId != null) {
            page = notificationRepository.findByTenantIdAndPropertyId(tenantId, propertyId, pageable);
        } else if (userId != null) {
            page = notificationRepository.findByTenantIdAndUserId(tenantId, userId, pageable);
        } else {
            page = notificationRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(this::toNotificationResponse);
    }

    @Transactional
    public NotificationResponse markNotificationAsRead(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        RealtimeNotification notification = notificationRepository.findByIdAndTenantId(id, tenantId)
                .filter(this::isNotificationVisibleToCaller)
                .orElseThrow(() -> new ResourceNotFoundException("RealtimeNotification", id));

        notification.markAsRead();
        notification = notificationRepository.save(notification);
        return toNotificationResponse(notification);
    }

    // --- PANIC ALERTS ---

    /**
     * Triggered by a resident pressing the SOS button. Derives propertyId from the resident's
     * active residence in the current tenant — no sensitive IDs accepted from the request body.
     */
    @Transactional
    public PanicAlertResponse triggerPanicAlert(
            AlertType alertType,
            String notes,
            UUID userId,
            UUID propertyId,
            UUID residentId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        Property property = propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("PROPERTY_NOT_FOUND", "Propiedad no encontrada."));

        // Fase 2 / E1: la relacion del usuario con ESA unidad (resuelta por ResidentUnitResolver), no la "primera"
        // residencia del usuario, que podia ser de otra unidad. Debe pertenecer a la unidad y estar ACTIVE.
        Resident resident = residentId == null ? null : residentRepository.findByIdAndTenantId(residentId, tenantId)
                .filter(r -> r.getProperty() != null && r.getProperty().getId().equals(propertyId))
                .filter(r -> r.getStatus() == ResidentStatus.ACTIVE)
                .orElseThrow(() -> new BusinessRuleConflictException("UNIT_NOT_AVAILABLE",
                        "La unidad seleccionada no está disponible para tu cuenta. Selecciona otra de tus unidades."));

        PanicAlert alert = PanicAlert.builder()
                .property(property)
                .resident(resident)
                .alertType(alertType)
                .notes(notes)
                .status(PanicAlertStatus.ACTIVE)
                .build();
        alert.setTenantId(tenantId);

        PanicAlert saved = panicAlertRepository.save(alert);
        log.warn("[PanicAlert] Nueva alerta {} creada por userId={} en property={}", alertType, userId, propertyId);

        publishPanicEvent("PANIC_ALERT", tenantId, saved);
        return toResponse(saved);
    }

    /** Guard/admin: acknowledge (take) an active alert */
    @Transactional
    public PanicAlertResponse acknowledgeAlert(UUID alertId, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        PanicAlert alert = panicAlertRepository.findByIdAndTenantId(alertId, tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("ALERT_NOT_FOUND", "Alerta no encontrada."));

        if (alert.getStatus() != PanicAlertStatus.ACTIVE) {
            throw new BusinessRuleConflictException("ALERT_NOT_ACTIVE",
                    "Solo se pueden tomar alertas en estado ACTIVE. Estado actual: " + alert.getStatus());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessRuleConflictException("USER_NOT_FOUND", "Usuario no encontrado."));

        alert.acknowledge(user);
        PanicAlert saved = panicAlertRepository.save(alert);
        log.info("[PanicAlert] Alerta {} tomada por userId={}", alertId, userId);

        publishPanicEvent("PANIC_ALERT_UPDATED", tenantId, saved);
        return toResponse(saved);
    }

    /** Guard/admin: resolve or mark as false alarm */
    @Transactional
    public PanicAlertResponse resolveAlert(UUID alertId, UUID userId, PanicAlertStatus finalStatus, String resolutionNotes) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        PanicAlert alert = panicAlertRepository.findByIdAndTenantId(alertId, tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("ALERT_NOT_FOUND", "Alerta no encontrada."));

        if (alert.getStatus() == PanicAlertStatus.RESOLVED || alert.getStatus() == PanicAlertStatus.FALSE_ALARM) {
            throw new BusinessRuleConflictException("ALERT_ALREADY_RESOLVED", "La alerta ya fue resuelta.");
        }

        if (finalStatus != PanicAlertStatus.RESOLVED && finalStatus != PanicAlertStatus.FALSE_ALARM) {
            throw new BusinessRuleConflictException("INVALID_FINAL_STATUS",
                    "El estado final debe ser RESOLVED o FALSE_ALARM.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessRuleConflictException("USER_NOT_FOUND", "Usuario no encontrado."));

        alert.resolve(user, resolutionNotes, finalStatus);
        PanicAlert saved = panicAlertRepository.save(alert);
        log.info("[PanicAlert] Alerta {} resuelta como {} por userId={}", alertId, finalStatus, userId);

        publishPanicEvent("PANIC_ALERT_UPDATED", tenantId, saved);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PanicAlertResponse> getActiveAlerts() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return panicAlertRepository
                .findByTenantIdAndStatusInOrderByCreatedAtDesc(tenantId, java.util.List.of(PanicAlertStatus.ACTIVE, PanicAlertStatus.ACKNOWLEDGED))
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<PanicAlertResponse> getAllAlertsPaginated(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return panicAlertRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PanicAlertResponse getAlertById(UUID alertId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        PanicAlert alert = panicAlertRepository.findByIdAndTenantId(alertId, tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("ALERT_NOT_FOUND", "Alerta no encontrada."));
        return toResponse(alert);
    }

    @Transactional(readOnly = true)
    public List<PanicAlertResponse> getMyAlerts(List<UUID> residentIds) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        if (residentIds == null || residentIds.isEmpty()) {
            return List.of();
        }
        return panicAlertRepository
                .findByTenantIdAndResidentIdInOrderByCreatedAtDesc(tenantId, residentIds)
                .stream().map(this::toResponse).toList();
    }

    // --- HELPERS ---

    /** RESIDENT: notificaciones dirigidas a el o a una unidad en la que hoy tiene relacion y membresia ACTIVE. */
    private boolean isNotificationVisibleToCaller(RealtimeNotification n) {
        if (!accessValidator.isResidentCaller()) {
            return true;
        }
        UUID self = accessValidator.currentUserId();
        return (n.getUser() != null && n.getUser().getId().equals(self))
                || (n.getProperty() != null && accessValidator.isActiveResidentOfProperty(n.getProperty().getId()));
    }

    // --- SSE EVENT ---

    private void publishPanicEvent(String eventType, UUID tenantId, PanicAlert alert) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("alert", toResponse(alert));
        // Porteria y administracion atienden la alerta; de los residentes, solo los de la unidad afectada.
        UUID propertyId = alert.getProperty() != null ? alert.getProperty().getId() : null;
        syncEventPublisher.publish(new SyncEvent(eventType, tenantId, payload, OffsetDateTime.now(),
                com.havita.modules.sync.domain.SyncAudience.unit(propertyId)));
    }

    // --- MAPPERS ---

    private NotificationResponse toNotificationResponse(RealtimeNotification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getTenantId(),
                notification.getUser() != null ? notification.getUser().getId() : null,
                notification.getProperty() != null ? notification.getProperty().getId() : null,
                notification.getTitle(),
                notification.getMessage(),
                notification.getType(),
                notification.getDataPayload(),
                notification.getIsRead(),
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }

    private PanicAlertResponse toResponse(PanicAlert alert) {
        String alertTypeName = alert.getAlertType() != null ? alert.getAlertType().name() : "OTHER";
        String typeLabel = switch (alertTypeName) {
            case "MEDICAL" -> "Emergencia médica";
            case "SECURITY" -> "Seguridad";
            case "FIRE" -> "Incendio";
            default -> "Otra emergencia";
        };

        String residentName = null;
        if (alert.getResident() != null) {
            // Prefer user's fullName if linked, fall back to resident.name
            residentName = alert.getResident().getUser() != null
                    ? alert.getResident().getUser().getFullName()
                    : alert.getResident().getName();
        }

        return new PanicAlertResponse(
                alert.getId(),
                alertTypeName,
                typeLabel,
                alert.getNotes(),
                alert.getStatus(),
                alert.getResident() != null ? alert.getResident().getId() : null,
                residentName,
                alert.getProperty() != null ? alert.getProperty().getId() : null,
                alert.getProperty() != null ? alert.getProperty().getPath() : null,
                alert.getProperty() != null ? alert.getProperty().getNumber() : null,
                alert.getAcknowledgedBy() != null ? alert.getAcknowledgedBy().getId() : null,
                alert.getAcknowledgedBy() != null ? alert.getAcknowledgedBy().getFullName() : null,
                alert.getAcknowledgedAt(),
                alert.getResolvedBy() != null ? alert.getResolvedBy().getId() : null,
                alert.getResolvedBy() != null ? alert.getResolvedBy().getFullName() : null,
                alert.getResolvedAt(),
                alert.getResolutionNotes(),
                alert.getCreatedAt(),
                alert.getUpdatedAt()
        );
    }
}
