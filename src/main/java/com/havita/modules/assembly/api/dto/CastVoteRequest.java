package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CastVoteRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID residentId,

        @NotEmpty(message = "Debe seleccionar al menos una opción")
        List<UUID> optionIds
) {}
