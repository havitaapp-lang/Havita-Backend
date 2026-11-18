package com.havita.modules.identity.api.dto;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UserDto user
) {
        public record MembershipDto(
            UUID tenantId,
            String role,
            String tenantName,
            String tenantSubdomain
    ) {}

    public record UserDto(
            UUID id,
            String email,
            String fullName,
            String role,
            UUID tenantId,
            String propertyUnit,
            UUID residentPropertyId,
            Boolean hasCompletedOnboarding,
            java.util.List<MembershipDto> memberships
    ) {}
}
