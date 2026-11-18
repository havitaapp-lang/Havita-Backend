package com.havita.modules.reservation.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.CommonZone;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "common_zone_id", nullable = false)
    private CommonZone commonZone;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resident_id")
    private Resident resident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdByUser;

    @Column(name = "reservation_date", nullable = false)
    private LocalDate reservationDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Builder.Default
    @Column(name = "guests_count")
    private Integer guestsCount = 1;

    @Builder.Default
    @Column(name = "total_cost", precision = 12, scale = 2)
    private BigDecimal totalCost = BigDecimal.ZERO;

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING; // Pendiente, Confirmada, Cancelada, Rechazada, Completada

    @Column(name = "observations", columnDefinition = "TEXT")
    private String observations;

    @Column(name = "admin_notes", columnDefinition = "TEXT")
    private String adminNotes;

    public void confirm(String notes) {
        this.status = ReservationStatus.CONFIRMED;
        this.adminNotes = notes;
    }

    public void cancel(String notes) {
        this.status = ReservationStatus.CANCELLED;
        this.adminNotes = notes;
    }

    public void reject(String reason) {
        this.status = ReservationStatus.REJECTED;
        this.adminNotes = reason;
    }
}
