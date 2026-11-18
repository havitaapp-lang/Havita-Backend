package com.havita.modules.audit.domain;

import com.havita.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "platform_audit_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent extends BaseEntity {

    @Builder.Default
    @Column(name = "timestamp", nullable = false)
    private OffsetDateTime timestamp = OffsetDateTime.now();

    @Column(name = "actor_name", nullable = false, length = 150)
    private String actorName;

    @Column(name = "actor_email", length = 150)
    private String actorEmail;

    @Column(name = "actor_role", nullable = false, length = 50)
    private String actorRole;

    @Column(name = "action", nullable = false, length = 100)
    private String action; // LOGIN, LOGOUT, CREATE, UPDATE, DELETE, ACCESS_DENIED, SUSPEND, etc.

    @Column(name = "resource", nullable = false, length = 150)
    private String resource;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "tenant_name", length = 150)
    private String tenantName;

    @Builder.Default
    @Column(name = "result", nullable = false, length = 30)
    private String result = "SUCCESS"; // SUCCESS, FAILURE, FORBIDDEN

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;
}
