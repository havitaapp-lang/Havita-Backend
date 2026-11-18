package com.havita.modules.subscription.domain;

import com.havita.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "saas_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaaSPlan extends BaseEntity {

    @Column(name = "tier", nullable = false, length = 50, unique = true)
    private String tier; // ESSENTIAL, PROFESSIONAL, LARGE, ENTERPRISE

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "tagline", length = 255)
    private String tagline;

    @Builder.Default
    @Column(name = "gross_price_per_property", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossPricePerProperty = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "platform_price_per_property", nullable = false, precision = 12, scale = 2)
    private BigDecimal platformPricePerProperty = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "community_share_per_property", nullable = false, precision = 12, scale = 2)
    private BigDecimal communitySharePerProperty = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "COP";

    @Builder.Default
    @Column(name = "billing_period", nullable = false, length = 30)
    private String billingPeriod = "MONTHLY"; // MONTHLY, ANNUAL

    @Column(name = "max_properties")
    private Integer maxProperties; // null = unlimited

    @Column(name = "max_storage_bytes")
    private Long maxStorageBytes; // null = unlimited

    @Column(name = "max_admin_users")
    private Integer maxAdminUsers; // null = unlimited

    @Column(name = "max_staff_users")
    private Integer maxStaffUsers; // null = unlimited

    @Column(name = "max_porters")
    private Integer maxPorters; // null = unlimited

    @Column(name = "max_documents")
    private Integer maxDocuments; // null = unlimited

    @Column(name = "max_reservations")
    private Integer maxReservations; // null = unlimited

    @Builder.Default
    @Column(name = "popular", nullable = false)
    private Boolean popular = false;

    @Column(name = "features", columnDefinition = "TEXT")
    private String features;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SaaSPlanStatus status = SaaSPlanStatus.ACTIVE; // ACTIVE, INACTIVE
}
