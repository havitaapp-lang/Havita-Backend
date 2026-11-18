package com.havita.modules.accounting.domain;

import com.havita.common.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "billing_configs", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tenant_billing_config", columnNames = {"tenant_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingConfig extends TenantScopedEntity {

    @Builder.Default
    @Column(name = "calculation_method", nullable = false, length = 30)
    private String calculationMethod = "COEFFICIENT"; // COEFFICIENT, FIXED_FEE, MIXED

    @Builder.Default
    @Column(name = "base_budget_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseBudgetAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "fixed_fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal fixedFeeAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "late_fee_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal lateFeePercentage = new BigDecimal("1.50");

    @Builder.Default
    @Column(name = "discount_early_payment_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountEarlyPaymentPercentage = new BigDecimal("5.00");

    @Builder.Default
    @Column(name = "due_day_of_month", nullable = false)
    private Integer dueDayOfMonth = 15;

    @Builder.Default
    @Column(name = "discount_day_of_month", nullable = false)
    private Integer discountDayOfMonth = 5;
}
