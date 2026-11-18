package com.havita.modules.accounting.domain;

import com.havita.common.domain.TenantScopedEntity;
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

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "property_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PropertyPayment extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private PropertyInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Builder.Default
    @Column(name = "payment_method", nullable = false, length = 50)
    private String paymentMethod = "TRANSFERENCIA"; // PSE, TRANSFERENCIA, EFECTIVO, CONSIGNACION, TARJETA

    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    @Builder.Default
    @Column(name = "amount_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate = LocalDate.now();

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PropertyPaymentStatus status = PropertyPaymentStatus.PENDING_VERIFICATION; // PENDING_VERIFICATION, CONFIRMED, REJECTED

    @Column(name = "receipt_url", length = 500)
    private String receiptUrl;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
