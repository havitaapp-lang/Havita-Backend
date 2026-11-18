package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreatePollRequest(
        @NotNull(message = "El ID de la asamblea es obligatorio")
        UUID assemblyId,

        @NotBlank(message = "La pregunta o moción es obligatoria")
        @Size(max = 300, message = "La pregunta no puede exceder 300 caracteres")
        String question,

        @Size(max = 30, message = "El tipo no puede exceder 30 caracteres")
        String type,

        Boolean showResultsToResidents,

        Integer durationMinutes,

        @NotEmpty(message = "Debe incluir al menos dos opciones de votación")
        List<String> options
) {}
