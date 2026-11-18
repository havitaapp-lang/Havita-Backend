package com.havita.modules.property.application;
import com.havita.modules.property.domain.CommonZoneStatus;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.property.api.dto.CommonZoneResponse;
import com.havita.modules.property.api.dto.CreateCommonZoneRequest;
import com.havita.modules.property.api.dto.UpdateCommonZoneRequest;
import com.havita.modules.property.domain.CommonZone;
import com.havita.modules.property.infrastructure.CommonZoneRepository;
import com.havita.modules.sync.application.SseService;
import com.havita.modules.sync.domain.SyncEvent;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommonZoneService {

    private final CommonZoneRepository commonZoneRepository;
    private final SseService sseService;

    @Transactional(readOnly = true)
    public List<CommonZoneResponse> findAll() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return commonZoneRepository.findByTenantId(tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<CommonZoneResponse> findAllPaged(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return commonZoneRepository.findByTenantId(tenantId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CommonZoneResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        CommonZone zone = commonZoneRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CommonZone", id));
        return toResponse(zone);
    }

    @Transactional
    public CommonZoneResponse create(CreateCommonZoneRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        if (commonZoneRepository.existsByTenantIdAndNameIgnoreCase(tenantId, request.name())) {
            throw new BusinessRuleConflictException(
                    "COMMON_ZONE_NAME_EXISTS",
                    "Ya existe una zona comun con el nombre '" + request.name() + "' en esta copropiedad.");
        }

        CommonZone zone = CommonZone.builder()
                .name(request.name().trim())
                .description(request.description())
                .capacity(request.capacity() != null ? request.capacity() : 10)
                .reservationRequired(request.reservationRequired() != null ? request.reservationRequired() : true)
                .costPerHour(request.costPerHour() != null ? request.costPerHour() : BigDecimal.ZERO)
                .rules(request.rules())
                .status(CommonZoneStatus.ACTIVE)
                .disabledMessage(request.disabledMessage())
                .disabledDays(request.disabledDays())
                .openTime(request.openTime())
                .closeTime(request.closeTime())
                .build();
        zone.setTenantId(tenantId);

        zone = commonZoneRepository.save(zone);
        log.info("Zona comun creada: {} (Tenant: {})", zone.getName(), tenantId);
        
        CommonZoneResponse response = toResponse(zone);
        sseService.publishEvent(new SyncEvent("ZONE_CREATED", tenantId, Map.of("zone", response)));
        return response;
    }

    @Transactional
    public CommonZoneResponse update(UUID id, UpdateCommonZoneRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        CommonZone zone = commonZoneRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CommonZone", id));

        if (request.name() != null) zone.setName(request.name().trim());
        if (request.description() != null) zone.setDescription(request.description());
        if (request.capacity() != null) zone.setCapacity(request.capacity());
        if (request.reservationRequired() != null) zone.setReservationRequired(request.reservationRequired());
        if (request.costPerHour() != null) zone.setCostPerHour(request.costPerHour());
        if (request.rules() != null) zone.setRules(request.rules());
        if (request.status() != null) zone.setStatus(request.status());
        if (request.disabledMessage() != null) zone.setDisabledMessage(request.disabledMessage());
        if (request.disabledDays() != null) zone.setDisabledDays(request.disabledDays());
        if (request.openTime() != null) zone.setOpenTime(request.openTime());
        if (request.closeTime() != null) zone.setCloseTime(request.closeTime());

        zone = commonZoneRepository.save(zone);
        log.info("Zona comun actualizada: {} (Tenant: {})", zone.getId(), tenantId);
        
        CommonZoneResponse response = toResponse(zone);
        sseService.publishEvent(new SyncEvent("ZONE_UPDATED", tenantId, Map.of("zone", response)));
        return response;
    }

    private CommonZoneResponse toResponse(CommonZone zone) {
        return new CommonZoneResponse(
                zone.getId(),
                zone.getTenantId(),
                zone.getName(),
                zone.getDescription(),
                zone.getCapacity(),
                zone.getReservationRequired(),
                zone.getCostPerHour(),
                zone.getRules(),
                zone.getStatus(),
                zone.getDisabledMessage(),
                zone.getDisabledDays(),
                zone.getOpenTime(),
                zone.getCloseTime(),
                zone.getCreatedAt(),
                zone.getUpdatedAt()
        );
    }
}
