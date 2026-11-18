package com.havita.modules.marketplace.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MarketplaceReportResponse(
        UUID id,
        MarketplaceListingResponse listing,
        UUID reportedByUserId,
        String reason,
        String description,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        UUID resolvedByUserId
) {}
