package com.havita.modules.property.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePropertyRequest(
        UUID structureId,

        @NotBlank(message = "El numero de inmueble es obligatorio")
        @Size(max = 50, message = "El numero no puede exceder 50 caracteres")
        String number,

        @NotBlank(message = "El nombre del inmueble es obligatorio")
        @Size(max = 100, message = "El nombre no puede exceder 100 caracteres")
        String name,

        @Size(max = 200, message = "El path no puede exceder 200 caracteres")
        String path,

        @Size(max = 50, message = "El tipo no puede exceder 50 caracteres")
        String type,

        @Size(max = 20, message = "El piso/nivel no puede exceder 20 caracteres")
        String level,

        BigDecimal area,
        BigDecimal coefficient,
        Integer bedrooms,
        Integer bathrooms,
        Integer vehiclesCount,
        Integer petsCount
) {}
