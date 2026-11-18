package com.havita.modules.resident.api.dto;

import java.util.List;
import java.util.UUID;

public record GenerateInvitationRequest(
        List<UUID> additionalPropertyIds
) {}