package com.havita.modules.access.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreatePackageRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        @NotBlank(message = "El nombre del destinatario es obligatorio")
        @Size(max = 150, message = "El nombre no puede exceder 150 caracteres")
        String recipientName,

        @Size(max = 100, message = "El número de guía no puede exceder 100 caracteres")
        String trackingNumber,

        @Size(max = 100, message = "La empresa de envíos no puede exceder 100 caracteres")
        String company,

        @Size(max = 500, message = "Las observaciones no pueden exceder 500 caracteres")
        String observations
) {}

