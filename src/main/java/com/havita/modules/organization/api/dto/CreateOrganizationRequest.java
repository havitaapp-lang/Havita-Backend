package com.havita.modules.organization.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
        @NotBlank(message = "El nombre de la organización es obligatorio")
        @Size(max = 255, message = "El nombre no puede exceder 255 caracteres")
        String name,

        @NotBlank(message = "El NIT es obligatorio")
        @Size(max = 50, message = "El NIT no puede exceder 50 caracteres")
        String nit,

        @Email(message = "El email debe tener un formato válido")
        @Size(max = 150, message = "El email no puede exceder 150 caracteres")
        String email,

        @Size(max = 50, message = "El teléfono no puede exceder 50 caracteres")
        String phone,

        @Size(max = 100, message = "La ciudad no puede exceder 100 caracteres")
        String city,

        @Size(max = 255, message = "La dirección no puede exceder 255 caracteres")
        String address
) {}
