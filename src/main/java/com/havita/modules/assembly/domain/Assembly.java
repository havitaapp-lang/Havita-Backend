package com.havita.modules.assembly.domain;

import com.havita.common.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "assemblies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Assembly extends TenantScopedEntity {

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Builder.Default
    @Column(name = "type", nullable = false, length = 30)
    private String type = "ORDINARIA"; // ORDINARIA, EXTRAORDINARIA

    @Builder.Default
    @Column(name = "modality", nullable = false, length = 30)
    private String modality = "MIXTA"; // PRESENCIAL, VIRTUAL, MIXTA

    @Column(name = "assembly_date", nullable = false)
    private LocalDate assemblyDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AssemblyStatus status = AssemblyStatus.SCHEDULED; // SCHEDULED, IN_PROGRESS, ENDED

    @Builder.Default
    @Column(name = "total_properties", nullable = false)
    private Integer totalProperties = 0;

    @Builder.Default
    @Column(name = "registered_attendees", nullable = false)
    private Integer registeredAttendees = 0;

    @Builder.Default
    @Column(name = "quorum_percentage", nullable = false, precision = 6, scale = 2)
    private BigDecimal quorumPercentage = BigDecimal.ZERO;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    public void start() {
        this.status = AssemblyStatus.IN_PROGRESS;
    }

    public void finish() {
        this.status = AssemblyStatus.ENDED;
    }
}
