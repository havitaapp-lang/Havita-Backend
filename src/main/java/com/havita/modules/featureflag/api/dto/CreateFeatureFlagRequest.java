package com.havita.modules.featureflag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFeatureFlagRequest(
        @NotBlank(message = "La clave del flag es obligatoria")
        @Size(max = 100, message = "La clave no puede exceder 100 caracteres")
        String key,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 150, message = "El nombre no puede exceder 150 caracteres")
        String name,

        @Size(max = 1000, message = "La descripcion no puede exceder 1000 caracteres")
        String description,

        @Size(max = 50, message = "La categoría no puede exceder 50 caracteres")
        String category,

        @NotBlank(message = "Debe definir los planes habilitados (ej: STARTER,PROFESSIONAL,ENTERPRISE)")
        String enabledByPlan,

        Boolean isGlobalEnabled,

        Boolean isBeta
) {}

