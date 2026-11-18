package com.havita.modules.property.application;

import com.havita.modules.property.domain.PropertyStatus;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.property.api.dto.CreatePropertyRequest;
import com.havita.modules.property.api.dto.PropertyResponse;
import com.havita.modules.property.api.dto.UpdatePropertyRequest;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.domain.Structure;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.property.infrastructure.StructureRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyService {
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPropertyHistory(UUID propertyId) {
        // SEC-8: el historial incluye correo, telefono y motivo de salida de antiguos residentes; solo lo consulta la
        // administracion de la comunidad (comprobada contra la base). Se valida antes de mirar la unidad: no permite enumerar.
        accessValidator.requireCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new com.havita.common.exception.ResourceNotFoundException("Property", propertyId));

        List<Resident> residents = residentRepository.findByTenantIdAndPropertyId(tenantId, propertyId);
        
        return residents.stream()
                .sorted(Comparator.comparing(Resident::getRegisteredAt).reversed())
                .map(r -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", r.getId());
                    map.put("name", r.getName());
                    map.put("type", r.getType());
                    map.put("status", r.getStatus());
                    map.put("email", r.getEmail());
                    map.put("phone", r.getPhone());
                    map.put("registeredAt", r.getRegisteredAt());
                    map.put("endedAt", r.getEndedAt());
                    map.put("endReason", r.getEndReason());
                    return map;
                })
                .toList();
    }


    
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final com.havita.security.AccessValidator accessValidator;

    private final StructureRepository structureRepository;

    @Transactional(readOnly = true)
    public Page<PropertyResponse> findAll(String search, PropertyStatus status, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<Property> page;

        if (search != null && !search.isBlank()) {
            page = propertyRepository.searchProperties(tenantId, search.trim(), pageable);
        } else if (status != null) {
            page = propertyRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = propertyRepository.findByTenantId(tenantId, pageable);
        }

        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PropertyResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Property property = propertyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", id));
        return toResponse(property);
    }

    @Transactional(readOnly = true)
    public List<PropertyResponse> findByStructure(UUID structureId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return propertyRepository.findByTenantIdAndStructureId(tenantId, structureId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PropertyResponse create(CreatePropertyRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        if (propertyRepository.existsByTenantIdAndNumber(tenantId, request.number())) {
            throw new BusinessRuleConflictException(
                    "PROPERTY_NUMBER_EXISTS",
                    "Ya existe un inmueble con el numero '" + request.number() + "' en esta copropiedad.");
        }

        Structure structure = null;
        if (request.structureId() != null) {
            structure = structureRepository.findByIdAndTenantId(request.structureId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Structure", request.structureId()));
        }

        Property property = Property.builder()
                .structure(structure)
                .number(request.number().trim())
                .name(request.name().trim())
                .path(request.path())
                .type(request.type() != null ? request.type() : "Apartamento")
                .status(PropertyStatus.VACANT)
                .level(request.level())
                .area(request.area())
                .coefficient(request.coefficient() != null ? request.coefficient() : BigDecimal.ZERO)
                .bedrooms(request.bedrooms() != null ? request.bedrooms() : 0)
                .bathrooms(request.bathrooms() != null ? request.bathrooms() : 0)
                .vehiclesCount(request.vehiclesCount() != null ? request.vehiclesCount() : 0)
                .petsCount(request.petsCount() != null ? request.petsCount() : 0)
                .build();
        property.setTenantId(tenantId);

        property = propertyRepository.save(property);
        log.info("Inmueble creado: {} (Tenant: {})", property.getNumber(), tenantId);
        return toResponse(property);
    }

    @Transactional
    public PropertyResponse update(UUID id, UpdatePropertyRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Property property = propertyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", id));

        if (request.structureId() != null) {
            Structure structure = structureRepository.findByIdAndTenantId(request.structureId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Structure", request.structureId()));
            property.setStructure(structure);
        }

        if (request.name() != null) property.setName(request.name().trim());
        if (request.path() != null) property.setPath(request.path());
        if (request.type() != null) property.setType(request.type());
        if (request.status() != null) property.setStatus(request.status());
        if (request.level() != null) property.setLevel(request.level());
        if (request.area() != null) property.setArea(request.area());
        if (request.coefficient() != null) property.setCoefficient(request.coefficient());
        if (request.bedrooms() != null) property.setBedrooms(request.bedrooms());
        if (request.bathrooms() != null) property.setBathrooms(request.bathrooms());
        if (request.vehiclesCount() != null) property.setVehiclesCount(request.vehiclesCount());
        if (request.petsCount() != null) property.setPetsCount(request.petsCount());

        property = propertyRepository.save(property);
        log.info("Inmueble actualizado: {} (Tenant: {})", property.getId(), tenantId);
        return toResponse(property);
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Property property = propertyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", id));
        propertyRepository.delete(property);
        log.info("Inmueble eliminado: {} (Tenant: {})", id, tenantId);
    }

    @Transactional(readOnly = true)
    public BigDecimal getTotalCoefficient() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return propertyRepository.sumCoefficientByTenantId(tenantId);
    }

    private PropertyResponse toResponse(Property property) {
        return new PropertyResponse(
                property.getId(),
                property.getTenantId(),
                property.getStructure() != null ? property.getStructure().getId() : null,
                property.getStructure() != null ? property.getStructure().getName() : null,
                property.getStructure() != null ? property.getStructure().getType() : null,
                property.getNumber(),
                property.getName(),
                property.getPath(),
                property.getType(),
                property.getStatus(),
                property.getLevel(),
                property.getArea(),
                property.getCoefficient(),
                property.getBedrooms(),
                property.getBathrooms(),
                property.getVehiclesCount(),
                property.getPetsCount(),
                property.getCreatedAt(),
                property.getUpdatedAt()
        );
    }
}
