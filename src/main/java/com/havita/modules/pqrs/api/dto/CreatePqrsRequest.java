package com.havita.modules.pqrs.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreatePqrsRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID residentId,

        @Size(max = 50, message = "La categoría no puede exceder 50 caracteres")
        String category,

        @NotBlank(message = "El título de la solicitud es obligatorio")
        @Size(max = 200, message = "El título no puede exceder 200 caracteres")
        String title,

        @NotBlank(message = "La descripción de la solicitud es obligatoria")
        @Size(max = 1000, message = "La descripcion no puede exceder 1000 caracteres")
        String description,

        @Size(max = 30, message = "La prioridad no puede exceder 30 caracteres")
        String priority
) {}

