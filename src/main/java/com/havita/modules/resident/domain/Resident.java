package com.havita.modules.resident.domain;

import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.Property;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
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
@Table(name = "residents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Resident extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Builder.Default
    @Column(name = "document_type", length = 20)
    private String documentType = "CC";

    @Column(name = "document_id", length = 50)
    private String documentId;

    @Builder.Default
    @Column(name = "type", nullable = false, length = 30)
    private String type = "Propietario";

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Builder.Default
    @Column(name = "is_main_contact", nullable = false)
    private Boolean isMainContact = false;

    @Column(name = "emergency_contact_name", length = 150)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 50)
    private String emergencyContactPhone;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ResidentStatus status = ResidentStatus.ACTIVE;

    @Builder.Default
    @Column(name = "registered_at", nullable = false)
    private OffsetDateTime registeredAt = OffsetDateTime.now();

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ended_by_id")
    private User endedBy;

    @Column(name = "end_reason", length = 255)
    private String endReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_resident_id")
    private Resident parentResident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by_user_id")
    private User grantedBy;
}
