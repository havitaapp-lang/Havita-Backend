package com.havita.modules.reservation.infrastructure;

import com.havita.modules.reservation.domain.ReservationStatus;

import com.havita.modules.reservation.domain.Reservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @EntityGraph(attributePaths = {"commonZone", "property", "resident"})
    List<Reservation> findByTenantId(UUID tenantId);

    @EntityGraph(attributePaths = {"commonZone", "property", "resident"})
    Page<Reservation> findByTenantId(UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"commonZone", "property", "resident"})
    Optional<Reservation> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"commonZone", "property", "resident"})
    List<Reservation> findByTenantIdAndCommonZoneIdAndReservationDate(UUID tenantId, UUID commonZoneId, LocalDate reservationDate);

    @Query("SELECT r FROM Reservation r WHERE r.tenantId = :tenantId AND " +
           "r.commonZone.id = :commonZoneId AND r.reservationDate = :reservationDate AND " +
           "r.status NOT IN (com.havita.modules.reservation.domain.ReservationStatus.CANCELLED, com.havita.modules.reservation.domain.ReservationStatus.REJECTED) " +
           "ORDER BY r.startTime ASC")
    List<Reservation> findActiveByTenantIdAndCommonZoneIdAndReservationDate(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("commonZoneId") UUID commonZoneId,
            @org.springframework.data.repository.query.Param("reservationDate") LocalDate reservationDate);

    @EntityGraph(attributePaths = {"commonZone", "property", "resident"})
    List<Reservation> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);

    @Query("SELECT r FROM Reservation r LEFT JOIN r.resident res LEFT JOIN res.user u " +
           "WHERE r.tenantId = :tenantId AND r.property.id = :propertyId AND " +
           "(r.createdByUser.id = :userId OR (res IS NOT NULL AND u.id = :userId)) " +
           "ORDER BY r.reservationDate DESC, r.startTime DESC")
    List<Reservation> findAccessibleReservations(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("propertyId") UUID propertyId,
            @org.springframework.data.repository.query.Param("userId") UUID userId);

    @Query("SELECT r FROM Reservation r LEFT JOIN r.resident res LEFT JOIN res.user u " +
           "WHERE r.tenantId = :tenantId AND " +
           "(r.createdByUser.id = :userId OR (res IS NOT NULL AND u.id = :userId)) " +
           "ORDER BY r.reservationDate DESC, r.startTime DESC")
    List<Reservation> findMyReservations(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("userId") UUID userId);

    @EntityGraph(attributePaths = {"commonZone", "property", "resident"})
    Page<Reservation> findByTenantIdAndStatus(UUID tenantId, ReservationStatus status, Pageable pageable);

    @Query("SELECT r FROM Reservation r WHERE r.tenantId = :tenantId AND " +
           "r.commonZone.id = :zoneId AND r.reservationDate = :date AND " +
           "r.status NOT IN (com.havita.modules.reservation.domain.ReservationStatus.CANCELLED, com.havita.modules.reservation.domain.ReservationStatus.REJECTED) AND " +
           "((r.startTime < :endTime AND r.endTime > :startTime))")
    List<Reservation> findOverlappingReservations(
            UUID tenantId, UUID zoneId, LocalDate date, LocalTime startTime, LocalTime endTime);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, ReservationStatus status);
}
