package com.havita.modules.subscription.domain;

import com.havita.common.domain.TenantScopedEntity;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "saas_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaaSInvoice extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private SaaSSubscription subscription;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 100)
    private String invoiceNumber;

    @Column(name = "period", nullable = false, length = 100)
    private String period;

    @Builder.Default
    @Column(name = "amount_cop", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountCop = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tax_cop", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxCop = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_cop", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalCop = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt = OffsetDateTime.now();

    @Column(name = "due_at", nullable = false)
    private OffsetDateTime dueAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SaaSInvoiceStatus status = SaaSInvoiceStatus.PENDING; // PAID, PENDING, OVERDUE, VOIDED

    @Column(name = "payment_reference", length = 150)
    private String paymentReference;

    public void markAsPaid(String reference) {
        this.status = SaaSInvoiceStatus.PAID;
        this.paidAt = OffsetDateTime.now();
        this.paymentReference = reference;
    }

    public void markAsVoided() {
        this.status = SaaSInvoiceStatus.VOIDED;
    }
}
