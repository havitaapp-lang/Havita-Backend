package com.havita.modules.subscription.api.dto;

import com.havita.modules.subscription.domain.SaaSSubscriptionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SubscriptionResponse(
        UUID id,
        UUID tenantId,
        String tenantName,
        UUID planId,
        String planName,
        String planTier,
        BigDecimal priceCop,
        String billingCycle,
        SaaSSubscriptionStatus status,
        LocalDate startDate,
        LocalDate expiresAt,
        String paymentMethod,
        Boolean autoRenew,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
