package com.havita.modules.organization.api.dto;

import com.havita.modules.organization.domain.TenantStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        UUID organizationId,
        String organizationName,
        String name,
        String slug,
        String subdomain,
        String nit,
        String city,
        String address,
        String phone,
        String contactEmail,
        TenantStatus status,
        Integer storageLimitMb,
        Integer storageUsedMb,
        String logoUrl,
        String primaryColor,
        String secondaryColor,
        UUID planId,
        String planName,
        OffsetDateTime trialEndsAt,
        String suspendedReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Integer propertiesCount,
        Integer usersCount,
        String adminName,
        String adminEmail,
        String adminPhone,
        BigDecimal mrrCop,
        BigDecimal pricePerUnitCop,
        List<String> activeFeatures
) {
    public TenantResponse(
            UUID id,
            UUID organizationId,
            String organizationName,
            String name,
            String slug,
            String subdomain,
            String nit,
            String city,
            String address,
            String phone,
            String contactEmail,
            TenantStatus status,
            Integer storageLimitMb,
            Integer storageUsedMb,
            String logoUrl,
            String primaryColor,
            String secondaryColor,
            UUID planId,
            String planName,
            OffsetDateTime trialEndsAt,
            String suspendedReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this(
                id, organizationId, organizationName, name, slug, subdomain,
                nit, city, address, phone, contactEmail, status,
                storageLimitMb, storageUsedMb, logoUrl, primaryColor, secondaryColor,
                planId, planName, trialEndsAt, suspendedReason, createdAt, updatedAt,
                0, 0, "", contactEmail, phone, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of("visitors", "packages", "pqrs", "reservations")
        );
    }
}
