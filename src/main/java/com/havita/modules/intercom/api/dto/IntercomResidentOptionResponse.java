package com.havita.modules.intercom.api.dto;

import java.util.UUID;

public record IntercomResidentOptionResponse(
        UUID residentId,
        UUID userId,
        String fullName,
        String residentType,
        Boolean isPrimaryContact,
        boolean hasUserAccount,
        boolean isAvailable,
        String phone
) {}
