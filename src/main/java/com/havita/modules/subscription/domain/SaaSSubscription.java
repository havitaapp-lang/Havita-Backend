package com.havita.modules.subscription.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.organization.domain.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "saas_subscriptions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tenant_subscription", columnNames = {"tenant_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaaSSubscription extends TenantScopedEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private SaaSPlan plan;

    // Pricing Snapshot
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
    @Column(name = "billing_cycle", nullable = false, length = 30)
    private String billingCycle = "MONTHLY"; // MONTHLY, ANNUAL

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SaaSSubscriptionStatus status = SaaSSubscriptionStatus.TRIAL; // TRIAL, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED, EXPIRED

    @Builder.Default
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate = LocalDate.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDate expiresAt;
    
    @Column(name = "trial_ends_at")
    private LocalDate trialEndsAt;
    
    @Column(name = "cancelled_at")
    private LocalDate cancelledAt;
    
    @Column(name = "current_period_start")
    private LocalDate currentPeriodStart;
    
    @Column(name = "current_period_end")
    private LocalDate currentPeriodEnd;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod; // PSE, CARD, BANK_TRANSFER

    @Builder.Default
    @Column(name = "auto_renew", nullable = false)
    private Boolean autoRenew = true;

    public void activate(LocalDate newExpiration) {
        this.status = SaaSSubscriptionStatus.ACTIVE;
        if (newExpiration != null) {
            this.expiresAt = newExpiration;
            this.currentPeriodEnd = newExpiration;
        }
        this.currentPeriodStart = LocalDate.now();
    }

    public void suspend() {
        this.status = SaaSSubscriptionStatus.SUSPENDED;
    }

    public void cancel() {
        this.status = SaaSSubscriptionStatus.CANCELLED;
        this.cancelledAt = LocalDate.now();
        this.autoRenew = false;
    }
}
