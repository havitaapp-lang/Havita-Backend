package com.havita.modules.featureflag.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OverrideFeatureRequest(
        @NotNull(message = "El valor booleano enabled es obligatorio")
        Boolean enabled,

        @Size(max = 255, message = "El motivo no puede exceder 255 caracteres")
        String reason
) {}
