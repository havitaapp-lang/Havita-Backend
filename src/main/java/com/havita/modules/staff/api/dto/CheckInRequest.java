package com.havita.modules.staff.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CheckInRequest(
        @NotNull(message = "El ID del miembro de personal es obligatorio")
        UUID staffMemberId,

        UUID shiftId,

        @Size(max = 100, message = "La portería no puede exceder 100 caracteres")
        String gate,

        @Size(max = 500, message = "Las observaciones no pueden exceder 500 caracteres")
        String observations
) {}

