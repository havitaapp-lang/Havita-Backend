package com.havita.modules.intercom.api.dto;

import com.havita.modules.intercom.domain.IntercomCallStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record IntercomCallResponse(
        UUID id,
        UUID callId,
        UUID propertyId,
        String propertyUnit,
        String propertyNumber,
        String propertyTower,
        UUID callerUserId,
        String callerName,
        String callerRole,
        UUID recipientResidentId,
        UUID recipientUserId,
        String recipientName,
        String recipientRole,
        IntercomCallStatus status,
        String realtimeMeetingId,
        String meetingId,
        String participantToken,
        OffsetDateTime ringingAt,
        OffsetDateTime acceptedAt,
        OffsetDateTime callerConnectedAt,
        OffsetDateTime recipientConnectedAt,
        OffsetDateTime connectedAt,
        OffsetDateTime endedAt,
        Integer durationSeconds,
        String endReason,
        OffsetDateTime createdAt
) {}
