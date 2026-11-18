package com.havita.modules.property.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.property.api.dto.CreateStructureRequest;
import com.havita.modules.property.api.dto.StructureResponse;
import com.havita.modules.property.domain.Structure;
import com.havita.modules.property.infrastructure.StructureRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.infrastructure.ResidentRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class StructureService {

    private final StructureRepository structureRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;

    @Transactional(readOnly = true)
    public List<StructureResponse> findAll() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return structureRepository.findByTenantId(tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<StructureResponse> findAllPaged(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return structureRepository.findByTenantId(tenantId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public StructureResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Structure structure = structureRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Structure", id));
        return toResponse(structure);
    }

    @Transactional
    public void deleteByName(String name) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        
        Structure structure = structureRepository.findByTenantIdAndNameIgnoreCase(tenantId, name)
                .orElseThrow(() -> new ResourceNotFoundException("Structure", name));
                
        boolean hasResidents = residentRepository.existsByTenantIdAndPropertyStructureId(tenantId, structure.getId());
        
        if (hasResidents) {
            throw new BusinessRuleConflictException(
                    "STRUCTURE_HAS_RESIDENTS",
                    "No se puede eliminar la estructura porque contiene propiedades con residentes asignados."
            );
        }
        
        propertyRepository.deleteByTenantIdAndStructureId(tenantId, structure.getId());
        structureRepository.delete(structure);
        
        log.info("Estructura eliminada: {} (Tenant: {})", name, tenantId);
    }

    @Transactional
    public StructureResponse create(CreateStructureRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        if (structureRepository.existsByTenantIdAndNameIgnoreCase(tenantId, request.name())) {
            throw new BusinessRuleConflictException(
                    "STRUCTURE_NAME_EXISTS",
                    "Ya existe una estructura con el nombre '" + request.name() + "' en esta copropiedad.");
        }

        Structure structure = Structure.builder()
                .name(request.name().trim())
                .type(request.type() != null ? request.type() : "Torre")
                .totalLevels(request.totalLevels() != null ? request.totalLevels() : 1)
                .description(request.description())
                .build();
        structure.setTenantId(tenantId);

        structure = structureRepository.save(structure);
        log.info("Estructura creada: {} (Tenant: {})", structure.getName(), tenantId);
        return toResponse(structure);
    }

    private StructureResponse toResponse(Structure structure) {
        return new StructureResponse(
                structure.getId(),
                structure.getTenantId(),
                structure.getName(),
                structure.getType(),
                structure.getTotalLevels(),
                structure.getDescription(),
                structure.getCreatedAt(),
                structure.getUpdatedAt()
        );
    }
}
