package com.havita.modules.pqrs.domain;

import com.havita.modules.pqrs.domain.PqrsRequestStatus;
import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.Property;
import com.havita.modules.resident.domain.Resident;
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
@Table(name = "pqrs_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PqrsRequest extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resident_id")
    private Resident resident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_user_id")
    private User assignedToUser;

    @Builder.Default
    @Column(name = "category", nullable = false, length = 50)
    private String category = "Otro";

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PqrsRequestStatus status = PqrsRequestStatus.NEW; // NEW, IN_PROGRESS, RESOLVED, CLOSED

    @Builder.Default
    @Column(name = "priority", nullable = false, length = 30)
    private String priority = "Media"; // Baja, Media, Alta, Crítica

    @Column(name = "response_notes", columnDefinition = "TEXT")
    private String responseNotes;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    public void startProgress(User technician) {
        this.status = PqrsRequestStatus.IN_PROGRESS;
        if (technician != null) {
            this.assignedToUser = technician;
        }
    }

    public void resolve(String response) {
        this.status = PqrsRequestStatus.RESOLVED;
        this.resolvedAt = OffsetDateTime.now();
        if (response != null && !response.isBlank()) {
            this.responseNotes = response;
        }
    }

    public void close() {
        this.status = PqrsRequestStatus.CLOSED;
        this.closedAt = OffsetDateTime.now();
    }
}
