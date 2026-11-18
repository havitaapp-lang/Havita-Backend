package com.havita.modules.property.api.dto;

import com.havita.modules.property.domain.PropertyStatus;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdatePropertyRequest(
        UUID structureId,

        @Size(max = 100, message = "El nombre no puede exceder 100 caracteres")
        String name,

        @Size(max = 200, message = "El path no puede exceder 200 caracteres")
        String path,

        @Size(max = 50, message = "El tipo no puede exceder 50 caracteres")
        String type,

        PropertyStatus status,

        @Size(max = 20, message = "El nivel no puede exceder 20 caracteres")
        String level,

        BigDecimal area,
        BigDecimal coefficient,
        Integer bedrooms,
        Integer bathrooms,
        Integer vehiclesCount,
        Integer petsCount
) {}
