package com.havita.modules.identity.api.dto;

import com.havita.modules.identity.domain.UserStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        String avatarUrl,
        UserStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
