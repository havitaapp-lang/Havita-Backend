package com.havita.modules.featureflag.api.dto;

import java.util.UUID;

public record FeatureFlagResponse(
        UUID id,
        String key,
        String name,
        String description,
        String category,
        String enabledByPlan,
        Boolean isGlobalEnabled,
        Boolean isBeta,
        Boolean isEffectiveEnabledForTenant
) {}
