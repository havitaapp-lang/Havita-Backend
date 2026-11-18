package com.havita.modules.chat.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Fase 3 — mensaje de texto de una conversacion (sin adjuntos ni multimedia).
 *
 * <ul>
 *   <li>{@code seq}: orden dentro de la conversacion (1, 2, 3…), asignado bajo el bloqueo de la conversacion. Sirve
 *       para paginar y para recuperar lo perdido tras una desconexion ({@code after=seq}).</li>
 *   <li>{@code clientMessageId}: idempotencia del envio. El mismo autor reintentando con el mismo id recibe el mensaje
 *       ya guardado, no uno nuevo.</li>
 *   <li>{@code authorName}: nombre del autor al escribir. Identifica al portero que envio el mensaje aunque despues
 *       cambie el responsable.</li>
 * </ul>
 * Nombre de entidad propio: {@code ChatMessage} ya existe en el modulo realtime (chat anterior, sin uso).
 */
@Entity(name = "ChatConversationMessage")
@Table(name = "chat_messages", uniqueConstraints = {
        @UniqueConstraint(name = "uk_chat_messages_seq", columnNames = {"conversation_id", "seq"}),
        @UniqueConstraint(name = "uk_chat_messages_client_id",
                columnNames = {"conversation_id", "author_user_id", "client_message_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class ChatConversationMessage extends TenantScopedEntity {

    public static final int MAX_BODY_LENGTH = 1000;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private ChatConversation conversation;

    @Column(name = "seq", nullable = false, updatable = false)
    private long seq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false, updatable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, length = 20, updatable = false)
    private ChatAuthorRole authorRole;

    @Column(name = "author_name", nullable = false, length = 150, updatable = false)
    private String authorName;

    @Column(name = "body", nullable = false, length = MAX_BODY_LENGTH, updatable = false)
    private String body;

    @Column(name = "client_message_id", updatable = false)
    private UUID clientMessageId;
}
