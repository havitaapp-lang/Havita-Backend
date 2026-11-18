package com.havita.modules.assembly.api.dto;

import java.util.UUID;

public record VotingRightResponse(
        UUID propertyId,
        String propertyNumber,
        String propertyName,
        String voterName,
        String voterRole
) {}
