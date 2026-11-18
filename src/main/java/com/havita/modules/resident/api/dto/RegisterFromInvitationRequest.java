package com.havita.modules.resident.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterFromInvitationRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 150)
        String name,

        @NotBlank(message = "El email es obligatorio")
        @Email(message = "Email invlido")
        @Size(max = 150)
        String email,

        @NotBlank(message = "El tipo de documento es obligatorio")
        @Size(max = 20)
        String documentType,

        @NotBlank(message = "El documento es obligatorio")
        @Size(max = 50)
        String documentId,

        @Size(max = 50)
        String phone,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 6, message = "La contraseña debe tener al menos 6 caracteres")
        String password,

        Boolean acceptTerms,
        Boolean acceptPrivacy
) {}
