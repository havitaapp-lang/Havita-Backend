package com.havita.modules.realtime.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TriggerPanicAlertRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID residentId,

        @NotBlank(message = "El tipo de alerta es obligatorio")
        @Size(max = 50, message = "El tipo de alerta no puede exceder 50 caracteres")
        String alertType, // MEDICAL, FIRE, INTRUSION, SUSPICIOUS_ACTIVITY, GENERAL

        @Size(max = 500, message = "Las notas no pueden exceder 500 caracteres")
        String notes
) {}

