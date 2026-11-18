package com.havita.modules.marketplace.api.dto;

import com.havita.modules.marketplace.domain.MarketplaceMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateMarketplaceConfigRequest(
        @NotNull MarketplaceMode mode,
        @Min(1) @Max(365) int listingDurationDays
) {}
