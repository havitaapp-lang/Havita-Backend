package com.havita.modules.reservation.application;

import com.havita.modules.reservation.domain.ReservationStatus;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.CommonZone;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.CommonZoneRepository;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.reservation.api.dto.CreateReservationRequest;
import com.havita.modules.reservation.api.dto.ReservationResponse;
import com.havita.modules.reservation.domain.Reservation;
import com.havita.modules.reservation.infrastructure.ReservationRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final CommonZoneRepository commonZoneRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final UserRepository userRepository;
    private final com.havita.modules.sync.application.SseService sseService;
    private final AccessValidator accessValidator;

    @Transactional(readOnly = true)
    public Page<ReservationResponse> findAll(ReservationStatus status, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<Reservation> page;

        if (status != null) {
            page = reservationRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = reservationRepository.findByTenantId(tenantId, pageable);
        }

        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ReservationResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Reservation reservation = reservationRepository.findByIdAndTenantId(id, tenantId)
                .filter(this::isVisibleToCaller)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
        return toResponse(reservation);
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> findByZoneAndDate(UUID zoneId, LocalDate date) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return reservationRepository.findActiveByTenantIdAndCommonZoneIdAndReservationDate(tenantId, zoneId, date)
                .stream()
                .map(r -> isVisibleToCaller(r) ? toResponse(r) : toAvailabilityResponse(r))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> findByProperty(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return reservationRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> findAccessibleReservations(UUID propertyId, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return reservationRepository.findAccessibleReservations(tenantId, propertyId, userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> findMyReservations(UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return reservationRepository.findMyReservations(tenantId, userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ReservationResponse create(CreateReservationRequest request, UUID creatorUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        boolean residentCaller = accessValidator.isResidentCaller();
        if (residentCaller && !accessValidator.isActiveResidentOfProperty(request.propertyId())) {
            throw new AccessDeniedException("No tiene permisos para reservar a nombre de este inmueble.");
        }

        if (request.startTime().isAfter(request.endTime()) || request.startTime().equals(request.endTime())) {
            throw new BusinessRuleConflictException(
                    "INVALID_TIME_RANGE",
                    "La hora de inicio debe ser anterior a la hora de finalización.");
        }

        CommonZone zone = commonZoneRepository.findByIdAndTenantId(request.commonZoneId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CommonZone", request.commonZoneId()));

        if (request.guestsCount() != null && zone.getCapacity() != null && request.guestsCount() > zone.getCapacity()) {
            throw new BusinessRuleConflictException(
                    "CAPACITY_EXCEEDED",
                    "El número de invitados (" + request.guestsCount() + ") supera la capacidad máxima de la zona (" + zone.getCapacity() + ").");
        }

        // Verificar solapamiento de horario
        List<Reservation> overlapping = reservationRepository.findOverlappingReservations(
                tenantId, zone.getId(), request.reservationDate(), request.startTime(), request.endTime());
        if (!overlapping.isEmpty()) {
            throw new BusinessRuleConflictException(
                    "ZONE_ALREADY_RESERVED",
                    "La zona común ya tiene una reserva activa o confirmada en ese horario.");
        }

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        Resident resident = null;
        if (request.residentId() != null) {
            resident = residentRepository.findByIdAndTenantId(request.residentId(), tenantId).orElse(null);
            if (residentCaller && resident != null && !resident.getProperty().getId().equals(property.getId())) {
                throw new AccessDeniedException("El residente indicado no pertenece a este inmueble.");
            }
        } else if (creatorUserId != null) {
            resident = residentRepository.findByUserIdAndTenantId(creatorUserId, tenantId)
                    .stream()
                    .filter(r -> r.getProperty().getId().equals(property.getId()))
                    .findFirst()
                    .orElse(null);
        }

        User creator = creatorUserId != null ? userRepository.findById(creatorUserId).orElse(null) : null;

        // Calcular costo por horas
        BigDecimal totalCost = BigDecimal.ZERO;
        if (zone.getCostPerHour() != null && zone.getCostPerHour().compareTo(BigDecimal.ZERO) > 0) {
            long minutes = Duration.between(request.startTime(), request.endTime()).toMinutes();
            double hours = Math.ceil(minutes / 60.0);
            totalCost = zone.getCostPerHour().multiply(BigDecimal.valueOf(hours));
        }

        Reservation reservation = Reservation.builder()
                .commonZone(zone)
                .property(property)
                .resident(resident)
                .createdByUser(creator)
                .reservationDate(request.reservationDate())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .guestsCount(request.guestsCount() != null ? request.guestsCount() : 1)
                .totalCost(totalCost)
                .status(ReservationStatus.PENDING)
                .observations(request.observations())
                .build();
        reservation.setTenantId(tenantId);

        reservation = reservationRepository.save(reservation);
        ReservationResponse response = toResponse(reservation);
        sseService.publishEvent(new com.havita.modules.sync.domain.SyncEvent(
                "RESERVATION_CREATED",
                tenantId,
                java.util.Map.of("reservation", response),
                eventAudience(reservation)
        ));
        log.info("Reserva creada para zona {} el {} de {} a {}",
                zone.getName(), reservation.getReservationDate(), reservation.getStartTime(), reservation.getEndTime());
        return response;
    }

    @Transactional
    public ReservationResponse confirm(UUID id, String notes) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Reservation reservation = reservationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));

        reservation.confirm(notes);
        reservation = reservationRepository.save(reservation);
        ReservationResponse response = toResponse(reservation);
        sseService.publishEvent(new com.havita.modules.sync.domain.SyncEvent(
                "RESERVATION_UPDATED",
                tenantId,
                java.util.Map.of("reservation", response),
                eventAudience(reservation)
        ));
        log.info("Reserva confirmada: {}", id);
        return response;
    }

    @Transactional
    public ReservationResponse cancel(UUID id, String notes) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Reservation reservation = reservationRepository.findByIdAndTenantId(id, tenantId)
                .filter(this::isVisibleToCaller)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));

        reservation.cancel(notes);
        reservation = reservationRepository.save(reservation);
        ReservationResponse response = toResponse(reservation);
        sseService.publishEvent(new com.havita.modules.sync.domain.SyncEvent(
                "RESERVATION_UPDATED",
                tenantId,
                java.util.Map.of("reservation", response),
                eventAudience(reservation)
        ));
        log.info("Reserva cancelada: {}", id);
        return response;
    }

    @Transactional
    public ReservationResponse reject(UUID id, String notes) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Reservation reservation = reservationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));

        reservation.reject(notes);
        reservation = reservationRepository.save(reservation);
        ReservationResponse response = toResponse(reservation);
        sseService.publishEvent(new com.havita.modules.sync.domain.SyncEvent(
                "RESERVATION_UPDATED",
                tenantId,
                java.util.Map.of("reservation", response),
                eventAudience(reservation)
        ));
        log.info("Reserva rechazada: {}", id);
        return response;
    }

    /**
     * Roles administrativos: cualquier reserva de su comunidad. RESIDENT: solo las reservas que creo o en las
     * que figura como residente (igual que findAccessibleReservations), de una unidad en la que hoy tiene
     * relacion y membresia ACTIVE.
     */
    private boolean isVisibleToCaller(Reservation r) {
        if (!accessValidator.isResidentCaller()) {
            return true;
        }
        UUID userId = accessValidator.currentUserId();
        boolean ownReservation = userId != null
                && ((r.getCreatedByUser() != null && userId.equals(r.getCreatedByUser().getId()))
                || (r.getResident() != null && r.getResident().getUser() != null
                        && userId.equals(r.getResident().getUser().getId())));
        return ownReservation && accessValidator.isActiveResidentOfProperty(r.getProperty().getId());
    }

    /**
     * Destinatarios de los eventos SSE de una reserva: los roles de gestion y, de los residentes, solo quien la
     * creo o figura como titular y sigue activo en la unidad (la misma regla que {@link #isVisibleToCaller}).
     */
    private static com.havita.modules.sync.domain.SyncAudience eventAudience(Reservation r) {
        return com.havita.modules.sync.domain.SyncAudience.unitUsers(r.getProperty().getId(), java.util.Arrays.asList(
                r.getCreatedByUser() != null ? r.getCreatedByUser().getId() : null,
                r.getResident() != null && r.getResident().getUser() != null ? r.getResident().getUser().getId() : null));
    }

    /** Reserva ajena vista por un residente al consultar disponibilidad: solo zona, fecha, horario y estado. */
    private ReservationResponse toAvailabilityResponse(Reservation r) {
        return new ReservationResponse(
                r.getId(),
                r.getTenantId(),
                r.getCommonZone().getId(),
                r.getCommonZone().getName(),
                null, null, null, null, null, null,
                r.getReservationDate(),
                r.getStartTime(),
                r.getEndTime(),
                null, null,
                r.getStatus(),
                null, null, null, null
        );
    }

    private ReservationResponse toResponse(Reservation r) {
        String resName = r.getResident() != null ? r.getResident().getName() : 
                (r.getCreatedByUser() != null ? r.getCreatedByUser().getFullName() : null);
        UUID creatorId = r.getCreatedByUser() != null ? r.getCreatedByUser().getId() : null;

        return new ReservationResponse(
                r.getId(),
                r.getTenantId(),
                r.getCommonZone().getId(),
                r.getCommonZone().getName(),
                r.getProperty().getId(),
                r.getProperty().getNumber(),
                r.getProperty().getName(),
                r.getResident() != null ? r.getResident().getId() : null,
                resName,
                creatorId,
                r.getReservationDate(),
                r.getStartTime(),
                r.getEndTime(),
                r.getGuestsCount(),
                r.getTotalCost(),
                r.getStatus(),
                r.getObservations(),
                r.getAdminNotes(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
