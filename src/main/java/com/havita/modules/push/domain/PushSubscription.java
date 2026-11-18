package com.havita.modules.push.domain;

import com.havita.common.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "push_subscriptions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_push_subscriptions_endpoint", columnNames = {"endpoint"})
    },
    indexes = {
        @Index(name = "idx_push_sub_user_tenant", columnList = "user_id, tenant_id, active"),
        @Index(name = "idx_push_sub_tenant_active", columnList = "tenant_id, active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushSubscription extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "endpoint", nullable = false, columnDefinition = "TEXT")
    private String endpoint;

    @Column(name = "p256dh", nullable = false, columnDefinition = "TEXT")
    private String p256dh;

    @Column(name = "auth", nullable = false, length = 255)
    private String auth;

    @Column(name = "device_name", length = 150)
    private String deviceName;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    public void deactivate() {
        this.active = false;
    }

    public void touchLastUsed() {
        this.lastUsedAt = OffsetDateTime.now();
    }
}
