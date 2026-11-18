package com.havita.modules.chat.application;

import com.havita.modules.chat.api.dto.ChatConversationView;
import com.havita.modules.chat.api.dto.ChatEventView;
import com.havita.modules.chat.api.dto.ChatMessageView;
import com.havita.modules.chat.domain.ChatAuthorRole;
import com.havita.modules.chat.domain.ChatConversation;
import com.havita.modules.chat.domain.ChatConversationEvent;
import com.havita.modules.chat.domain.ChatConversationMessage;
import com.havita.modules.chat.domain.ChatConversationStatus;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.domain.Structure;

import java.util.UUID;

/**
 * Fase 3 — conversion a vistas. El residente ve el NOMBRE del portero que le escribe o le atiende (decision de producto),
 * pero nunca su id de usuario ni otros datos del personal.
 */
final class ChatViews {

    /** Si el portero no tiene nombre registrado. */
    static final String STAFF_DISPLAY_NAME = "Portería";

    private ChatViews() {}

    /** «Torre 1 · 101» (como el selector de unidades del residente) o el numero/nombre de la unidad. */
    static String unitLabel(Property p) {
        if (p == null) return null;
        String unit = p.getNumber() != null && !p.getNumber().isBlank() ? p.getNumber() : p.getName();
        Structure s = p.getStructure();
        return s != null && s.getName() != null && !s.getName().isBlank() ? s.getName() + " · " + unit : unit;
    }

    static ChatConversationView staff(ChatConversation c) {
        User a = c.getAssignedTo();
        return new ChatConversationView(c.getId(), c.getStatus(), c.getProperty().getId(), unitLabel(c.getProperty()),
                c.getResidentUser().getId(), c.getResidentUser().getFullName(),
                a != null ? a.getId() : null, a != null ? a.getFullName() : null, c.getAssignedAt(),
                c.getStatus() == ChatConversationStatus.ACTIVE,
                c.getLastMessageSeq(), c.getLastMessageAt(), c.getLastMessagePreview(), c.getLastMessageAuthorRole(),
                c.getStaffUnread(), c.getStaffLastReadSeq(), c.getCreatedAt(), c.getClosedAt());
    }

    static ChatConversationView resident(ChatConversation c) {
        User a = c.getAssignedTo();
        return new ChatConversationView(c.getId(), c.getStatus(), c.getProperty().getId(), unitLabel(c.getProperty()),
                c.getResidentUser().getId(), c.getResidentUser().getFullName(),
                null, a != null ? nameOrDefault(a.getFullName()) : null, null,
                c.getStatus() == ChatConversationStatus.ACTIVE,
                c.getLastMessageSeq(), c.getLastMessageAt(), c.getLastMessagePreview(), c.getLastMessageAuthorRole(),
                c.getResidentUnread(), c.getResidentLastReadSeq(), c.getCreatedAt(), c.getClosedAt());
    }

    static ChatMessageView staff(ChatConversationMessage m, UUID viewerId) {
        UUID author = m.getAuthor().getId();
        return new ChatMessageView(m.getId(), m.getConversation().getId(), m.getSeq(), m.getAuthorRole(), author,
                m.getAuthorName(), m.getBody(), m.getCreatedAt(), author.equals(viewerId) ? m.getClientMessageId() : null);
    }

    static ChatMessageView resident(ChatConversationMessage m, UUID viewerId) {
        boolean fromStaff = m.getAuthorRole() == ChatAuthorRole.STAFF;
        UUID author = m.getAuthor().getId();
        return new ChatMessageView(m.getId(), m.getConversation().getId(), m.getSeq(), m.getAuthorRole(),
                fromStaff ? null : author, fromStaff ? nameOrDefault(m.getAuthorName()) : m.getAuthorName(), m.getBody(),
                m.getCreatedAt(), author.equals(viewerId) ? m.getClientMessageId() : null);
    }

    static ChatEventView event(ChatConversationEvent e) {
        return new ChatEventView(e.getType(), name(e.getActor()), name(e.getFromUser()), name(e.getToUser()), e.getCreatedAt());
    }

    private static String nameOrDefault(String name) {
        return name != null && !name.isBlank() ? name : STAFF_DISPLAY_NAME;
    }

    private static String name(User u) {
        return u != null ? u.getFullName() : null;
    }
}
