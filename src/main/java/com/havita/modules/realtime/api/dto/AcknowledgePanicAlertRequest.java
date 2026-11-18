package com.havita.modules.realtime.api.dto;

import jakarta.validation.constraints.Size;

public record AcknowledgePanicAlertRequest(
        @Size(max = 500, message = "Las notas no pueden exceder 500 caracteres")
        String notes  // opcional, nota interna del guardia al tomar la alerta
) {}


