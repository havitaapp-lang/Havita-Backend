package com.havita.modules.chat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Texto plano (sin HTML, sin adjuntos). {@code clientMessageId}: id generado por el cliente para que un reintento no
 * duplique el mensaje.
 */
public record ChatSendRequest(
        @NotBlank(message = "El mensaje no puede estar vacío.")
        @Size(max = 1000, message = "El mensaje admite como máximo 1000 caracteres.")
        String body,
        UUID clientMessageId
) {}
