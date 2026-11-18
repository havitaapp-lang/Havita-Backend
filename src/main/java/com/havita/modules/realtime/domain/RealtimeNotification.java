package com.havita.modules.realtime.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.Property;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "realtime_notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RealtimeNotification extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id")
    private Property property;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "type", nullable = false, length = 50)
    private String type; // VISIT_ARRIVAL, PACKAGE_RECEIVED, RESERVATION_STATUS, GENERAL_ANNOUNCEMENT, PANIC_ALERT

    @Column(name = "data_payload", columnDefinition = "TEXT")
    private String dataPayload;

    @Builder.Default
    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    public void markAsRead() {
        this.isRead = true;
        this.readAt = OffsetDateTime.now();
    }
}
