package com.havita.modules.access.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.Property;
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
@Table(name = "packages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Package extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "recipient_name", nullable = false, length = 150)
    private String recipientName;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Builder.Default
    @Column(name = "company", nullable = false, length = 100)
    private String company = "Otro";

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PackageStatus status = PackageStatus.RECEIVED; // RECEIVED, NOTIFIED, DELIVERED

    @Builder.Default
    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt = OffsetDateTime.now();

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "delivered_to", length = 150)
    private String deliveredTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "security_guard_id")
    private User securityGuard;

    @Column(name = "observations", columnDefinition = "TEXT")
    private String observations;

    public void markAsNotified() {
        this.status = PackageStatus.NOTIFIED;
    }

    public void deliver(String deliveredTo) {
        this.status = PackageStatus.DELIVERED;
        this.deliveredAt = OffsetDateTime.now();
        this.deliveredTo = deliveredTo;
    }
}
