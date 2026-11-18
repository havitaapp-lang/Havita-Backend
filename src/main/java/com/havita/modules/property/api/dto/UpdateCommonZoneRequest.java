package com.havita.modules.property.api.dto;

import com.havita.modules.property.domain.CommonZoneStatus;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateCommonZoneRequest(
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

        CommonZoneStatus status,

        String disabledMessage,

        String disabledDays,

        String openTime,

        String closeTime
) {}


