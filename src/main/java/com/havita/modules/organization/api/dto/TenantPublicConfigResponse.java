package com.havita.modules.organization.api.dto;

import java.util.UUID;

public record TenantPublicConfigResponse(
        UUID id,
        String name,
        String logoUrl,
        String primaryColor,
        String secondaryColor,
        String city,
        String address
) {}
