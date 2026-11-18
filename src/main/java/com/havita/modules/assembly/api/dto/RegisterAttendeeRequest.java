package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RegisterAttendeeRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID residentId,

        Boolean isPowerOfAttorney,

        @Size(max = 150, message = "El nombre del apoderado no puede exceder 150 caracteres")
        String representativeName
) {}
