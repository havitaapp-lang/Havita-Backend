package com.havita.modules.property.api.dto;

import com.havita.modules.property.domain.PropertyStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PropertyResponse(
        UUID id,
        UUID tenantId,
        UUID structureId,
        String structureName,
        String structureType,
        String number,
        String name,
        String path,
        String type,
        PropertyStatus status,
        String level,
        BigDecimal area,
        BigDecimal coefficient,
        Integer bedrooms,
        Integer bathrooms,
        Integer vehiclesCount,
        Integer petsCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
