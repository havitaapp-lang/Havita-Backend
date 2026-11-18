package com.havita.modules.resident.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreatePetRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID residentId,

        @NotBlank(message = "El nombre de la mascota es obligatorio")
        @Size(max = 100, message = "El nombre no puede exceder 100 caracteres")
        String name,

        @Size(max = 30, message = "El tipo de mascota no puede exceder 30 caracteres")
        String type,

        @Size(max = 50, message = "La raza no puede exceder 50 caracteres")
        String breed,

        @Size(max = 30, message = "El color no puede exceder 30 caracteres")
        String color,

        Boolean isDangerousBreed,

        @Size(max = 500, message = "La URL del carnet de vacunacion no puede exceder 500 caracteres")
        String vaccinationCardUrl,

        @Size(max = 500, message = "La URL de la foto no puede exceder 500 caracteres")
        String photoUrl
) {}
