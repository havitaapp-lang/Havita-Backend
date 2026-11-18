package com.havita.modules.accounting.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BillingConfigResponse(
        UUID id,
        UUID tenantId,
        String calculationMethod,
        BigDecimal baseBudgetAmount,
        BigDecimal fixedFeeAmount,
        BigDecimal lateFeePercentage,
        BigDecimal discountEarlyPaymentPercentage,
        Integer dueDayOfMonth,
        Integer discountDayOfMonth
) {}
