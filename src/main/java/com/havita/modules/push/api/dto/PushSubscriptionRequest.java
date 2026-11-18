package com.havita.modules.push.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PushSubscriptionRequest(

        @NotBlank(message = "El endpoint de la suscripción es obligatorio")
        @Size(max = 2048, message = "El endpoint no puede exceder 2048 caracteres")
        String endpoint,

        @Valid
        Keys keys,

        @Size(max = 150, message = "El nombre del dispositivo no puede exceder 150 caracteres")
        String deviceName
) {
    public record Keys(
            @NotBlank(message = "La clave p256dh es obligatoria")
            @Size(max = 512, message = "La clave p256dh no puede exceder 512 caracteres")
            String p256dh,

            @NotBlank(message = "La clave auth es obligatoria")
            @Size(max = 255, message = "La clave auth no puede exceder 255 caracteres")
            String auth
    ) {}
}
