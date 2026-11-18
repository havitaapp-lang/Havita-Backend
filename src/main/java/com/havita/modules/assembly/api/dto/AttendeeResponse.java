package com.havita.modules.assembly.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AttendeeResponse(
        UUID id,
        UUID assemblyId,
        UUID propertyId,
        String propertyNumber,
        String propertyName,
        UUID residentId,
        String residentName,
        BigDecimal coefficient,
        Boolean isPowerOfAttorney,
        String representativeName,
        OffsetDateTime checkedInAt
) {}
