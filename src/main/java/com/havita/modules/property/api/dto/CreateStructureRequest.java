package com.havita.modules.property.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStructureRequest(
        @NotBlank(message = "El nombre de la estructura es obligatorio")
        @Size(max = 100, message = "El nombre no puede exceder 100 caracteres")
        String name,

        @Size(max = 50, message = "El tipo no puede exceder 50 caracteres")
        String type,

        Integer totalLevels,

        @Size(max = 255, message = "La descripcion no puede exceder 255 caracteres")
        String description
) {}
