package com.havita.modules.accounting.api.dto;

import com.havita.modules.accounting.domain.PropertyInvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PropertyInvoiceResponse(
        UUID id,
        UUID tenantId,
        UUID propertyId,
        String propertyNumber,
        String propertyName,
        UUID residentId,
        String residentName,
        String invoiceNumber,
        String period,
        String concept,
        BigDecimal baseAmount,
        BigDecimal lateFeeAmount,
        BigDecimal discountAmount,
        BigDecimal previousBalance,
        BigDecimal totalAmount,
        BigDecimal balanceDue,
        PropertyInvoiceStatus status,
        LocalDate issuedDate,
        LocalDate dueDate,
        LocalDate discountUntilDate,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
