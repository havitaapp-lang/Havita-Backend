package com.havita.modules.chat.api.dto;

import com.havita.modules.chat.domain.ChatAuthorRole;
import com.havita.modules.chat.domain.ChatConversationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resumen de una conversacion. Dos variantes con la misma forma:
 * <ul>
 *   <li>porteria: responsable ({@code assignedTo*}), no leidos de porteria;</li>
 *   <li>residente: nombre de quien le atiende ({@code assignedToName}), sin ids del personal ({@code assignedToUserId} nulo),
 *       no leidos del residente.</li>
 * </ul>
 * {@code readSeq}: hasta que mensaje ha leido quien consulta (su lado).
 */
public record ChatConversationView(
        UUID id,
        ChatConversationStatus status,
        UUID propertyId,
        String unitLabel,
        UUID residentUserId,
        String residentName,
        UUID assignedToUserId,
        String assignedToName,
        OffsetDateTime assignedAt,
        boolean attended,
        long lastMessageSeq,
        OffsetDateTime lastMessageAt,
        String lastMessagePreview,
        ChatAuthorRole lastMessageAuthorRole,
        int unread,
        long readSeq,
        OffsetDateTime createdAt,
        OffsetDateTime closedAt
) {}
