package com.havita.modules.accounting.api.dto;

import com.havita.modules.accounting.domain.PropertyPaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PropertyPaymentResponse(
        UUID id,
        UUID tenantId,
        UUID invoiceId,
        String invoiceNumber,
        UUID propertyId,
        String propertyNumber,
        String paymentMethod,
        String referenceNumber,
        BigDecimal amountPaid,
        LocalDate paymentDate,
        PropertyPaymentStatus status,
        String receiptUrl,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
