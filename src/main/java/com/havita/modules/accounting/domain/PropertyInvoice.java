package com.havita.modules.accounting.domain;

import com.havita.common.domain.TenantScopedEntity;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "property_invoices", uniqueConstraints = {
        @UniqueConstraint(name = "uq_property_invoice_period", columnNames = {"property_id", "period"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PropertyInvoice extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resident_id")
    private Resident resident;

    @Column(name = "invoice_number", nullable = false, length = 100)
    private String invoiceNumber;

    @Column(name = "period", nullable = false, length = 100)
    private String period; // ej: "Septiembre 2026"

    @Builder.Default
    @Column(name = "concept", nullable = false, length = 200)
    private String concept = "Cuota Ordinaria de Administración";

    @Builder.Default
    @Column(name = "base_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "late_fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal lateFeeAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "previous_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal previousBalance = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "balance_due", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceDue = BigDecimal.ZERO;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PropertyInvoiceStatus status = PropertyInvoiceStatus.PENDING; // PENDING, PAID, PARTIALLY_PAID, OVERDUE, CANCELLED

    @Builder.Default
    @Column(name = "issued_date", nullable = false)
    private LocalDate issuedDate = LocalDate.now();

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "discount_until_date")
    private LocalDate discountUntilDate;

    public void registerPayment(BigDecimal paymentAmount) {
        if (paymentAmount == null) return;
        this.balanceDue = this.balanceDue.subtract(paymentAmount);
        if (this.balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            this.balanceDue = BigDecimal.ZERO;
            this.status = PropertyInvoiceStatus.PAID;
        } else {
            this.status = PropertyInvoiceStatus.PARTIALLY_PAID;
        }
    }
}
