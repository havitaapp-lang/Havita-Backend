package com.havita.modules.audit.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * SEC-9 (P1): lo que un cliente puede aportar a un evento de auditoria. La identidad del actor, su rol efectivo y la
 * IP los fija el servidor; si un cliente antiguo aun los envia, se ignoran (propiedades desconocidas).
 */
public record ClientAuditEventRequest(
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

        @Size(max = 2000, message = "El detalle no puede exceder 2000 caracteres")
        String details
) {
}
