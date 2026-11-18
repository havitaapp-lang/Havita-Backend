package com.havita.modules.access.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code PUT /api/v1/access/visitors/{id}} (administracion y porteria): datos del visitante. La unidad y el
 * estado no se cambian aqui (el estado tiene sus propias acciones: entrada, salida, cancelar).
 */
public record UpdateVisitorRequest(
        @NotBlank(message = "El nombre del visitante es obligatorio")
        @Size(max = 150, message = "El nombre no puede exceder 150 caracteres")
        String name,

        @NotBlank(message = "El documento de identidad es obligatorio")
        @Size(max = 50, message = "El documento no puede exceder 50 caracteres")
        String documentId,

        @Size(max = 20, message = "La placa no puede exceder 20 caracteres")
        String vehiclePlate,

        @Size(max = 1000, message = "Las observaciones no pueden exceder 1000 caracteres")
        String observations,

        @Size(max = 150, message = "El motivo no puede exceder 150 caracteres")
        String visitPurpose
) {}
