package com.havita.modules.resident.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateResidentRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID userId,

        @NotBlank(message = "El nombre del residente es obligatorio")
        @Size(max = 150, message = "El nombre no puede exceder 150 caracteres")
        String name,

        @Size(max = 20, message = "El tipo de documento no puede exceder 20 caracteres")
        String documentType,

        @Size(max = 50, message = "El numero de documento no puede exceder 50 caracteres")
        String documentId,

        @Size(max = 30, message = "El tipo de residente no puede exceder 30 caracteres")
        String type,

        @Size(max = 50, message = "El telefono no puede exceder 50 caracteres")
        String phone,

        @Size(max = 150, message = "El email no puede exceder 150 caracteres")
        String email,

        Boolean isMainContact,

        @Size(max = 150, message = "El nombre del contacto de emergencia no puede exceder 150 caracteres")
        String emergencyContactName,

        @Size(max = 50, message = "El telefono de emergencia no puede exceder 50 caracteres")
        String emergencyContactPhone
) {}
