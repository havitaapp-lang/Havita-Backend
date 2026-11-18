package com.havita.modules.property.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.audit.api.dto.RecordAuditEventRequest;
import com.havita.modules.audit.application.AuditService;
import com.havita.modules.property.api.dto.BulkPropertyGenerationRequest;
import com.havita.modules.property.api.dto.BulkPropertyGenerationResponse;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.domain.Structure;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.property.infrastructure.StructureRepository;
import com.havita.modules.subscription.application.PlanLimitService;
import com.havita.security.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkPropertyService {

    private final StructureRepository structureRepository;
    private final PropertyRepository propertyRepository;
    private final PlanLimitService planLimitService;
    private final AuditService auditService;

    @Transactional
    public BulkPropertyGenerationResponse importBulk(org.springframework.web.multipart.MultipartFile file) {
        // Implementation for parsing CSV and doing the same loop
        // As a simple placeholder, assuming it parses the CSV and creates properties
        // For production, we'd use Apache Commons CSV or similar.
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        List<String> errors = new ArrayList<>();
        
        try {
            String content = new String(file.getBytes());
            String[] lines = content.split("\n");
            
            // basic check
            planLimitService.validatePropertyCreationBulk(tenantId, lines.length > 0 ? lines.length - 1 : 0);
            
            int pCount = 0;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 4) {
                    String struct = parts[0].trim();
                    String level = parts[1].trim();
                    String prop = parts[2].trim();
                    String type = parts[3].trim();
                    
                    String fullNumber = buildPropertyNumber(struct, level, prop);
                    
                    Structure structure = structureRepository.findByTenantIdAndNameIgnoreCase(tenantId, struct)
                        .orElseGet(() -> {
                            Structure s = Structure.builder().name(struct).type("Torre").build();
                            s.setTenantId(tenantId);
                            return structureRepository.save(s);
                        });
                        
                    if (!propertyRepository.existsByTenantIdAndNumberIgnoreCase(tenantId, fullNumber)) {
                        Property property = Property.builder()
                            .structure(structure)
                            .number(fullNumber)
                            .name(prop)
                            .type(type)
                            .level(level)
                            .path(buildPath(struct, level, prop))
                            .build();
                        property.setTenantId(tenantId);
                        propertyRepository.save(property);
                        pCount++;
                    }
                }
            }
            recordAudit(pCount);
            return new BulkPropertyGenerationResponse(0, pCount, errors);
        } catch (Exception e) {
            throw new BusinessRuleConflictException("BULK_IMPORT_FAILED", "Error procesando el archivo: " + e.getMessage());
        }
    }

    @Transactional
    public BulkPropertyGenerationResponse generateBulk(BulkPropertyGenerationRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        
        List<String> structureNames = generateNames(request.structure());
        List<String> levelNames = request.level() != null ? generateNames(request.level()) : new ArrayList<>();
        if (levelNames.isEmpty()) {
            levelNames.add("1"); // Default to level 1 if not specified
        }
        List<String> propertyNames = generateNames(request.property());

        int expectedCount = structureNames.size() * Math.max(1, levelNames.size()) * propertyNames.size();
        
        planLimitService.validatePropertyCreationBulk(tenantId, expectedCount);

        List<String> errors = new ArrayList<>();
        int structuresCreated = 0;
        int propertiesCreated = 0;

        for (String structName : structureNames) {
            String finalStructName = structName.trim();
            Structure structure = structureRepository.findByTenantIdAndNameIgnoreCase(tenantId, finalStructName)
                    .orElseGet(() -> {
                        Structure newStruct = Structure.builder()
                                .name(finalStructName)
                                .type(request.structureType())
                                .totalLevels(levelNames.isEmpty() || levelNames.get(0).isEmpty() ? 1 : levelNames.size())
                                .build();
                        newStruct.setTenantId(tenantId);
                        return structureRepository.save(newStruct);
                    });

            if (structure.getCreatedAt() == null) {
                structuresCreated++;
            }

            for (String levelName : levelNames) {
                String finalLevel = levelName.trim();
                for (String propName : propertyNames) {
                    String finalPropName = propName.trim();
                    
                    String fullNumber = buildPropertyNumber(finalStructName, finalLevel, finalPropName);
                    
                    if (propertyRepository.existsByTenantIdAndNumberIgnoreCase(tenantId, fullNumber)) {
                        errors.add("La propiedad " + fullNumber + " ya existe.");
                        continue;
                    }

                    Property property = Property.builder()
                            .structure(structure)
                            .number(fullNumber)
                            .name(finalPropName)
                            .type(request.propertyType())
                            .level(finalLevel.isEmpty() ? null : finalLevel)
                            .path(buildPath(finalStructName, finalLevel, finalPropName))
                            .build();
                    property.setTenantId(tenantId);
                    
                    propertyRepository.save(property);
                    propertiesCreated++;
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new BusinessRuleConflictException("BULK_GENERATION_FAILED", "Se encontraron duplicados u errores y se cancelo la operacion: " + String.join(", ", errors));
        }

        recordAudit(propertiesCreated);

        return new BulkPropertyGenerationResponse(structuresCreated, propertiesCreated, errors);
    }

    private String buildPropertyNumber(String struct, String level, String prop) {
        StringBuilder sb = new StringBuilder(struct);
        if (!level.isEmpty()) sb.append("-").append(level);
        sb.append("-").append(prop);
        return sb.toString();
    }

    private String buildPath(String struct, String level, String prop) {
        StringBuilder sb = new StringBuilder(struct);
        if (!level.isEmpty()) sb.append(" -> ").append(level);
        sb.append(" -> ").append(prop);
        return sb.toString();
    }

    private List<String> generateNames(BulkPropertyGenerationRequest.LevelConfig config) {
        List<String> names = new ArrayList<>();
        if ("CUSTOM".equalsIgnoreCase(config.generationType())) {
            if (config.customValues() != null) {
                for (String val : config.customValues()) {
                    names.add((config.prefix() != null ? config.prefix() : "") + val.trim());
                }
            }
        } else {
            int start = config.startFrom() != null ? config.startFrom() : 1;
            int count = config.count() != null ? config.count() : 1;
            for (int i = start; i < start + count; i++) {
                names.add((config.prefix() != null ? config.prefix() : "") + i);
            }
        }
        return names;
    }

    private void recordAudit(int count) {
        try {
            String ip = "127.0.0.1";
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest req = attributes.getRequest();
                ip = req.getHeader("X-Forwarded-For");
                if (ip == null) ip = req.getRemoteAddr();
            }

            auditService.recordEvent(new RecordAuditEventRequest(
                    "Admin", "admin@havita.com", "COMMUNITY_ADMIN",
                    "BULK_PROPERTY_CREATION", "Properties",
                    null, null, "SUCCESS",
                    "Se generaron " + count + " propiedades exitosamente.", ip
            ));
        } catch (Exception e) {
            log.warn("No se pudo registrar auditoria para creacion masiva", e);
        }
    }
}

