package com.havita.modules.intercom.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.Property;
import com.havita.modules.resident.domain.Resident;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "intercom_calls")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntercomCall extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "caller_user_id", nullable = false)
    private User callerUser;

    @Column(name = "caller_name", nullable = false, length = 150)
    private String callerName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_resident_id", nullable = false)
    private Resident recipientResident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id")
    private User recipientUser;

    @Column(name = "recipient_name", nullable = false, length = 150)
    private String recipientName;

    @Column(name = "property_unit", nullable = false, length = 100)
    private String propertyUnit;

    @Column(name = "realtime_meeting_id", nullable = false, length = 150)
    private String realtimeMeetingId;

    @Column(name = "caller_participant_id", length = 150)
    private String callerParticipantId;

    @Column(name = "recipient_participant_id", length = 150)
    private String recipientParticipantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private IntercomCallStatus status;

    @Column(name = "ringing_at")
    private OffsetDateTime ringingAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "caller_connected_at")
    private OffsetDateTime callerConnectedAt;

    @Column(name = "recipient_connected_at")
    private OffsetDateTime recipientConnectedAt;

    @Column(name = "connected_at")
    private OffsetDateTime connectedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Builder.Default
    @Column(name = "duration_seconds")
    private Integer durationSeconds = 0;

    @Column(name = "end_reason", length = 100)
    private String endReason;

    @Column(name = "technical_failure_reason", length = 255)
    private String technicalFailureReason;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;
}
