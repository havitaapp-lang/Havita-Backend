package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

public record CreateAssemblyRequest(
        @NotBlank(message = "El título de la asamblea es obligatorio")
        @Size(max = 200, message = "El título no puede exceder 200 caracteres")
        String title,

        @Size(max = 30, message = "El tipo no puede exceder 30 caracteres")
        String type,

        @Size(max = 30, message = "La modalidad no puede exceder 30 caracteres")
        String modality,

        @NotNull(message = "La fecha de la asamblea es obligatoria")
        LocalDate assemblyDate,

        @NotNull(message = "La hora de inicio es obligatoria")
        LocalTime startTime,

        String description
) {}
