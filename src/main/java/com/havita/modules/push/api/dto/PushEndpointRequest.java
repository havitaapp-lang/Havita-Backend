package com.havita.modules.push.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** P8-B: identifica la suscripcion de ESTE dispositivo (su endpoint de push), p. ej. para revocarla al cerrar sesion. */
public record PushEndpointRequest(
        @NotBlank(message = "El endpoint de la suscripción es obligatorio")
        @Size(max = 2048, message = "El endpoint no puede exceder 2048 caracteres")
        String endpoint
) {
}
