package com.havita.modules.realtime.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SendNotificationRequest(
        UUID userId,
        UUID propertyId,

        @NotBlank(message = "El título de la notificación es obligatorio")
        @Size(max = 200, message = "El título no puede exceder 200 caracteres")
        String title,

        @NotBlank(message = "El mensaje es obligatorio")
        String message,

        @NotBlank(message = "El tipo de notificación es obligatorio")
        String type,

        String dataPayload
) {}
