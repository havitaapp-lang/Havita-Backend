package com.havita.modules.accounting.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateBillingConfigRequest(
        String calculationMethod,
        @NotNull(message = "El presupuesto base es obligatorio")
        BigDecimal baseBudgetAmount,
        BigDecimal fixedFeeAmount,
        BigDecimal lateFeePercentage,
        BigDecimal discountEarlyPaymentPercentage,
        Integer dueDayOfMonth,
        Integer discountDayOfMonth
) {}
