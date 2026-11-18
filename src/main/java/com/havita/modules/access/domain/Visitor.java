package com.havita.modules.access.domain;

import com.havita.modules.access.domain.VisitorStatus;
import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.property.domain.Property;
import com.havita.modules.resident.domain.Resident;
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
@Table(name = "visitors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Visitor extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resident_id")
    private Resident resident;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "document_id", nullable = false, length = 50)
    private String documentId;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private VisitorStatus status = VisitorStatus.PENDING; // Autorizado, Pendiente, Ingresó, Salió, Expirado

    @Column(name = "expected_at")
    private OffsetDateTime expectedAt;

    @Column(name = "entered_at")
    private OffsetDateTime enteredAt;

    @Column(name = "exited_at")
    private OffsetDateTime exitedAt;

    @Column(name = "vehicle_plate", length = 20)
    private String vehiclePlate;

    @Column(name = "observations", columnDefinition = "TEXT")
    private String observations;

    @Column(name = "qr_code", length = 255)
    private String qrCode;

    @Column(name = "group_id", length = 100)
    private String groupId;

    @Column(name = "group_name", length = 150)
    private String groupName;

    @Column(name = "visit_purpose", length = 150)
    private String visitPurpose;

    @Column(name = "entered_by")
    private java.util.UUID enteredBy;

    @Column(name = "exited_by")
    private java.util.UUID exitedBy;

    @Column(name = "created_by_user_id")
    private java.util.UUID createdByUserId;

    public void registerEntry(java.util.UUID userId) {
        this.status = VisitorStatus.ENTERED;
        this.enteredAt = OffsetDateTime.now();
        this.enteredBy = userId;
    }

    public void registerExit(java.util.UUID userId) {
        this.status = VisitorStatus.EXITED;
        this.exitedAt = OffsetDateTime.now();
        this.exitedBy = userId;
    }

    public void authorize() {
        this.status = VisitorStatus.AUTHORIZED;
    }

    public void cancel() {
        this.status = VisitorStatus.CANCELLED;
    }
}


