package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.Size;

/** Fase 2 / P1: motivo de un rechazo (obligatorio) o de una revocacion (opcional). */
public record ReviewAssemblyProxyRequest(
        @Size(max = 500, message = "El motivo no puede exceder 500 caracteres")
        String reason
) {}
