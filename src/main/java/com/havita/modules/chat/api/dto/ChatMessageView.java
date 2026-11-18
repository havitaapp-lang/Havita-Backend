package com.havita.modules.chat.api.dto;

import com.havita.modules.chat.domain.ChatAuthorRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mensaje. Para el residente, los mensajes del personal llevan el nombre del portero, pero no su id de usuario.
 * {@code clientMessageId} solo se devuelve a su autor (para conciliar el envio optimista).
 */
public record ChatMessageView(
        UUID id,
        UUID conversationId,
        long seq,
        ChatAuthorRole authorRole,
        UUID authorUserId,
        String authorName,
        String body,
        OffsetDateTime createdAt,
        UUID clientMessageId
) {}
