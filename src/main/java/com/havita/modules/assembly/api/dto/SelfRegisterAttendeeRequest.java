package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SelfRegisterAttendeeRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId
) {}
