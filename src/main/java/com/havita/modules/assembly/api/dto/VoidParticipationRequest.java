package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Fase 2 / P4: anulacion de un voto asistido; el motivo es obligatorio y queda en el historial. */
public record VoidParticipationRequest(
        @NotBlank(message = "El motivo de la anulación es obligatorio")
        @Size(max = 500, message = "El motivo no puede exceder 500 caracteres")
        String reason
) {}
