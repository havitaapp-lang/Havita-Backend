package com.havita.modules.organization.api.dto;

import com.havita.modules.organization.domain.OrganizationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String nit,
        String email,
        String phone,
        String city,
        String address,
        String logoUrl,
        OrganizationStatus status,
        long communitiesCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
