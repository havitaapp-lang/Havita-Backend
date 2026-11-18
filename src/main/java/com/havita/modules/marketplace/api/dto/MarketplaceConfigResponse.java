package com.havita.modules.marketplace.api.dto;

public record MarketplaceConfigResponse(
        String mode,
        int listingDurationDays,
        boolean marketplaceEnabled
) {}
