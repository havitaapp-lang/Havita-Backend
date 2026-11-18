package com.havita.modules.realtime.api.dto;

import com.havita.modules.realtime.domain.PanicAlertStatus;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.Size;
public record ResolvePanicAlertRequest(
        @NotNull PanicAlertStatus finalStatus,  // RESOLVED o FALSE_ALARM
        @Size(max = 500, message = "Las notas de resolucion no pueden exceder 500 caracteres")
        String resolutionNotes
) {}

