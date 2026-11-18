package com.havita.modules.chat.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.Property;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Fase 3 — hilo de chat entre UN residente (cuenta) y la porteria, para UNA unidad de UNA comunidad.
 *
 * <p>Hay como maximo un hilo por (comunidad, unidad, residente): se reabre en lugar de duplicarse, asi el historial es
 * continuo. Los contadores y el resumen del ultimo mensaje se mantienen al escribir (bajo bloqueo de la fila) para que
 * la bandeja se lea en una sola consulta, sin N+1.
 *
 * <p>Esquema: lo crea Hibernate (ddl-auto=update, como el resto de la aplicacion) y, en el modo Flyway, la migracion
 * V107 con las mismas columnas.
 */
@Entity
@Table(name = "chat_conversations",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_conversations_resident_unit",
                columnNames = {"tenant_id", "property_id", "resident_user_id"}),
        indexes = {
                @Index(name = "ix_chat_conversations_inbox", columnList = "tenant_id, status, last_message_at"),
                @Index(name = "ix_chat_conversations_assignee", columnList = "tenant_id, assigned_to_user_id"),
                @Index(name = "ix_chat_conversations_resident", columnList = "tenant_id, resident_user_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class ChatConversation extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false, updatable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resident_user_id", nullable = false, updatable = false)
    private User residentUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChatConversationStatus status = ChatConversationStatus.PENDING;

    /** Portero responsable; no nulo si y solo si el estado es ACTIVE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_user_id")
    private User assignedTo;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    /** Numero del ultimo mensaje (0 = sin mensajes). Los mensajes se numeran 1, 2, 3… sin huecos. */
    @Column(name = "last_message_seq", nullable = false)
    private long lastMessageSeq;

    @Column(name = "last_message_at")
    private OffsetDateTime lastMessageAt;

    /** Primeros caracteres del ultimo mensaje (bandeja). Nunca se envia en notificaciones push. */
    @Column(name = "last_message_preview", length = 160)
    private String lastMessagePreview;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_message_author_role", length = 20)
    private ChatAuthorRole lastMessageAuthorRole;

    /** Mensajes de porteria que el residente no ha leido. */
    @Column(name = "resident_unread", nullable = false)
    private int residentUnread;

    /** Mensajes del residente que la porteria (el responsable) no ha leido. */
    @Column(name = "staff_unread", nullable = false)
    private int staffUnread;

    @Column(name = "resident_last_read_seq", nullable = false)
    private long residentLastReadSeq;

    @Column(name = "staff_last_read_seq", nullable = false)
    private long staffLastReadSeq;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean isAssignedTo(java.util.UUID userId) {
        return assignedTo != null && userId != null && userId.equals(assignedTo.getId());
    }

    /** Asigna (ACTIVE) o deja sin responsable (PENDING/CLOSED) manteniendo el invariante. */
    public void assign(User user, OffsetDateTime at) {
        this.assignedTo = user;
        this.assignedAt = at;
        this.status = ChatConversationStatus.ACTIVE;
        this.closedAt = null;
    }

    public void unassign(ChatConversationStatus newStatus, OffsetDateTime at) {
        if (newStatus == ChatConversationStatus.ACTIVE) {
            throw new IllegalArgumentException("ACTIVE requiere responsable");
        }
        this.assignedTo = null;
        this.assignedAt = null;
        this.status = newStatus;
        this.closedAt = newStatus == ChatConversationStatus.CLOSED ? at : null;
    }
}
