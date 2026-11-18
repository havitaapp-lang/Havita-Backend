package com.havita.modules.communication.domain;

import com.havita.modules.communication.domain.CommunicationStatus;
import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.OffsetDateTime;



@Entity
@Table(name = "communications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Communication extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(name = "author_name", nullable = false, length = 150)
    private String authorName;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Builder.Default
    @Column(name = "audience", nullable = false, length = 50)
    private String audience = "Todos"; // Todos, Propietarios, Arrendatarios, Personal

    @Builder.Default
    @Column(name = "category", nullable = false, length = 50)
    private String category = "General"; // General, Mantenimiento, Asamblea, Seguridad

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CommunicationStatus status = CommunicationStatus.PUBLISHED; // Publicado, Borrador, Archivado

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    public void publish() {
        this.status = CommunicationStatus.PUBLISHED;
        this.publishedAt = OffsetDateTime.now();
    }

    public void archive() {
        this.status = CommunicationStatus.ARCHIVED;
    }
}
