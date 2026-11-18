package com.havita.modules.chat.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Fase 3 — auditoria de la conversacion: quien la tomo, libero, transfirio, cerro o reabrio y cuando
 * ({@code created_at}). Solo insercion. {@code actor} es nulo cuando actua el sistema (AUTO_RELEASED).
 */
@Entity
@Table(name = "chat_conversation_events",
        indexes = @Index(name = "ix_chat_conversation_events_conversation", columnList = "conversation_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class ChatConversationEvent extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private ChatConversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30, updatable = false)
    private ChatEventType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", updatable = false)
    private User actor;

    /** Responsable anterior (liberar, transferir, cerrar, liberacion automatica). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_user_id", updatable = false)
    private User fromUser;

    /** Responsable nuevo (tomar, transferir). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_user_id", updatable = false)
    private User toUser;
}
