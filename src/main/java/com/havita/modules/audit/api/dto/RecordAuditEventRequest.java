package com.havita.modules.audit.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RecordAuditEventRequest(
        @NotBlank(message = "El nombre del actor es obligatorio")
        @Size(max = 150, message = "El nombre no puede exceder 150 caracteres")
        String actorName,

        @Size(max = 150, message = "El email no puede exceder 150 caracteres")
        String actorEmail,

        @NotBlank(message = "El rol del actor es obligatorio")
        @Size(max = 50, message = "El rol no puede exceder 50 caracteres")
        String actorRole,

        @NotBlank(message = "La acción es obligatoria")
        @Size(max = 100, message = "La acción no puede exceder 100 caracteres")
        String action,

        @NotBlank(message = "El recurso es obligatorio")
        @Size(max = 150, message = "El recurso no puede exceder 150 caracteres")
        String resource,

        UUID tenantId,

        @Size(max = 150, message = "El nombre del tenant no puede exceder 150 caracteres")
        String tenantName,

        @Size(max = 30, message = "El resultado no puede exceder 30 caracteres")
        String result,

        String details,

        @NotBlank(message = "La dirección IP es obligatoria")
        @Size(max = 45, message = "La IP no puede exceder 45 caracteres")
        String ipAddress
) {}
