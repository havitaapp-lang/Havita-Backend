package com.havita.modules.property.domain;

import com.havita.common.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "common_zones", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tenant_zone_name", columnNames = {"tenant_id", "name"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommonZone extends TenantScopedEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "capacity", nullable = false)
    private Integer capacity = 10;

    @Builder.Default
    @Column(name = "reservation_required", nullable = false)
    private Boolean reservationRequired = true;

    @Builder.Default
    @Column(name = "cost_per_hour", precision = 12, scale = 2)
    private BigDecimal costPerHour = BigDecimal.ZERO;

    @Column(name = "rules", columnDefinition = "TEXT")
    private String rules;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CommonZoneStatus status = CommonZoneStatus.ACTIVE;

    @Column(name = "disabled_message", length = 255)
    private String disabledMessage;

    @Column(name = "disabled_days", length = 255)
    private String disabledDays;

    @Column(name = "open_time", length = 10)
    private String openTime;

    @Column(name = "close_time", length = 10)
    private String closeTime;
}
