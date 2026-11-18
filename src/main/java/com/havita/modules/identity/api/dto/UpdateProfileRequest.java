package com.havita.modules.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "El nombre completo es requerido")
        @Size(max = 150, message = "El nombre no puede superar 150 caracteres")
        String fullName,

        @Size(max = 50, message = "El teléfono no puede superar 50 caracteres")
        String phone
) {}
