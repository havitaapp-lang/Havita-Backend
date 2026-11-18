package com.havita.modules.resident.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.property.domain.Property;
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

@Entity
@Table(name = "resident_invitations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResidentInvitation extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resident_id")
    private Resident resident;


    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Builder.Default
    @Column(name = "invitation_type", nullable = false, length = 50)
    private String invitationType = "OWNER";

    @Builder.Default
    @Column(name = "max_uses", nullable = false)
    private Integer maxUses = 1;

    @Builder.Default
    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    @Column(name = "additional_property_ids", length = 2000)
    private String additionalPropertyIds;

    @Column(name = "inviter_user_id")
    private java.util.UUID inviterUserId;

    @Column(name = "invitee_email", length = 150)
    private String inviteeEmail;

    @Column(name = "invitee_name", length = 150)
    private String inviteeName;

    @Column(name = "invitee_phone", length = 50)
    private String inviteePhone;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;
}
