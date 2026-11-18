package com.havita.modules.staff.domain;

import com.havita.common.domain.TenantScopedEntity;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "attendance_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceRecord extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_member_id", nullable = false)
    private StaffMember staffMember;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(name = "scheduled_check_in")
    private LocalTime scheduledCheckIn;

    @Column(name = "check_in")
    private OffsetDateTime checkIn;

    @Column(name = "scheduled_check_out")
    private LocalTime scheduledCheckOut;

    @Column(name = "check_out")
    private OffsetDateTime checkOut;

    @Column(name = "gate", length = 100)
    private String gate;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AttendanceRecordStatus status = AttendanceRecordStatus.PRESENT; // PRESENT, LATE, ABSENT, INCOMPLETE

    @Column(name = "observations", columnDefinition = "TEXT")
    private String observations;
}
