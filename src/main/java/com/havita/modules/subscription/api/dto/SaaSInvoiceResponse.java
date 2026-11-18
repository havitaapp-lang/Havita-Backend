package com.havita.modules.subscription.api.dto;

import com.havita.modules.subscription.domain.SaaSInvoiceStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SaaSInvoiceResponse(
        UUID id,
        UUID tenantId,
        String tenantName,
        UUID subscriptionId,
        String invoiceNumber,
        String period,
        BigDecimal amountCop,
        BigDecimal taxCop,
        BigDecimal totalCop,
        OffsetDateTime issuedAt,
        OffsetDateTime dueAt,
        OffsetDateTime paidAt,
        SaaSInvoiceStatus status,
        String paymentReference,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
