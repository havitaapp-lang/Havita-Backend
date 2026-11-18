package com.havita.modules.intercom.api.dto;

import jakarta.validation.constraints.Size;

/**
 * P8-B: cuerpo OPCIONAL de aceptar o rechazar. {@code pushEndpoint} identifica el dispositivo que actua, solo para no
 * enviarle a el mismo el aviso de «contestada/rechazada en otro dispositivo». No elige destinatarios ni da permisos.
 */
public record IntercomDeviceRequest(
        @Size(max = 2048, message = "El endpoint no puede exceder 2048 caracteres")
        String pushEndpoint
) {
}
