package com.havita.modules.property.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StructureResponse(
        UUID id,
        UUID tenantId,
        String name,
        String type,
        Integer totalLevels,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
