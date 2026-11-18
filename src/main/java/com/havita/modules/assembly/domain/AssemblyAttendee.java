package com.havita.modules.assembly.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.property.domain.Property;
import com.havita.modules.resident.domain.Resident;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "assembly_attendees", uniqueConstraints = {
        @UniqueConstraint(name = "uq_assembly_property", columnNames = {"assembly_id", "property_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssemblyAttendee extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assembly_id", nullable = false)
    private Assembly assembly;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resident_id")
    private Resident resident;

    @Builder.Default
    @Column(name = "coefficient", nullable = false, precision = 8, scale = 6)
    private BigDecimal coefficient = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "is_power_of_attorney", nullable = false)
    private Boolean isPowerOfAttorney = false;

    @Column(name = "representative_name", length = 150)
    private String representativeName;

    @Builder.Default
    @Column(name = "checked_in_at", nullable = false)
    private OffsetDateTime checkedInAt = OffsetDateTime.now();
}
