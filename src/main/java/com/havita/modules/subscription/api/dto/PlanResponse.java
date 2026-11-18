package com.havita.modules.subscription.api.dto;

import com.havita.modules.subscription.domain.SaaSPlanStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PlanResponse(
        UUID id,
        String tier,
        String name,
        String tagline,
        BigDecimal grossPricePerProperty,
        BigDecimal platformPricePerProperty,
        BigDecimal communitySharePerProperty,
        String currency,
        String billingPeriod,
        Integer maxProperties,
        Long maxStorageBytes,
        Integer maxAdminUsers,
        Integer maxStaffUsers,
        Integer maxPorters,
        Integer maxDocuments,
        Integer maxReservations,
        Boolean popular,
        String features,
        SaaSPlanStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
