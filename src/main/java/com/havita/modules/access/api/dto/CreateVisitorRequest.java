package com.havita.modules.access.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateVisitorRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID residentId,

        @NotBlank(message = "El nombre del visitante es obligatorio")
        @Size(max = 150, message = "El nombre no puede exceder 150 caracteres")
        String name,

        @NotBlank(message = "El documento de identidad es obligatorio")
        @Size(max = 50, message = "El documento no puede exceder 50 caracteres")
        String documentId,

        OffsetDateTime expectedAt,

        @Size(max = 20, message = "La placa no puede exceder 20 caracteres")
        String vehiclePlate,

        @Size(max = 500, message = "Las observaciones no pueden exceder 500 caracteres")
        String observations,

        @Size(max = 100, message = "El ID de grupo no puede exceder 100 caracteres")
        String groupId,

        @Size(max = 150, message = "El nombre del grupo no puede exceder 150 caracteres")
        String groupName,

        @Size(max = 150, message = "El motivo de la visita no puede exceder 150 caracteres")
        String visitPurpose
) {}

