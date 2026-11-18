package com.havita.modules.staff.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record CreateShiftRequest(
        @NotNull(message = "El ID del miembro de personal es obligatorio")
        UUID staffMemberId,

        @NotNull(message = "La fecha del turno es obligatoria")
        LocalDate shiftDate,

        @NotBlank(message = "El nombre del turno es obligatorio")
        @Size(max = 100, message = "El nombre no puede exceder 100 caracteres")
        String shiftName,

        @NotNull(message = "La hora de inicio es obligatoria")
        LocalTime startTime,

        @NotNull(message = "La hora de finalización es obligatoria")
        LocalTime endTime,

        @Size(max = 100, message = "La portería no puede exceder 100 caracteres")
        String gate,

        @Size(max = 30, message = "El tipo de turno no puede exceder 30 caracteres")
        String shiftType,

        @Size(max = 500, message = "Las notas no pueden exceder 500 caracteres")
        String notes
) {}

