package com.havita.modules.property.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateCommonZoneRequest(
        @NotBlank(message = "El nombre de la zona comun es obligatorio")
        @Size(max = 100, message = "El nombre no puede exceder 100 caracteres")
        String name,

        @Size(max = 1000, message = "La descripcion no puede exceder 1000 caracteres")
        String description,

        @Min(value = 1, message = "La capacidad minima es 1 persona")
        Integer capacity,

        Boolean reservationRequired,

        BigDecimal costPerHour,

        @Size(max = 2000, message = "Las reglas no pueden exceder 2000 caracteres")
        String rules,

        String disabledMessage,

        String disabledDays,

        String openTime,

        String closeTime
) {}


