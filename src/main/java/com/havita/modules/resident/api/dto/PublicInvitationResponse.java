package com.havita.modules.resident.api.dto;

import java.util.UUID;

public record PublicInvitationResponse(
        String tenantName,
        String propertyName,
        String structureName,
        String invitationType
) {}
