package com.havita.modules.intercom.infrastructure;

import com.havita.modules.intercom.domain.IntercomCall;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IntercomCallRepository extends JpaRepository<IntercomCall, UUID> {

    @EntityGraph(attributePaths = {"property", "callerUser", "recipientResident", "recipientUser"})
    Optional<IntercomCall> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM IntercomCall c WHERE c.id = :id AND c.tenantId = :tenantId")
    Optional<IntercomCall> findByIdAndTenantIdWithLock(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @EntityGraph(attributePaths = {"property", "callerUser", "recipientResident", "recipientUser"})
    Page<IntercomCall> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"property", "callerUser", "recipientResident", "recipientUser"})
    Optional<IntercomCall> findFirstByTenantIdAndCallerUserIdAndStatusIn(
            UUID tenantId, UUID callerUserId, List<IntercomCallStatus> statuses);

    @EntityGraph(attributePaths = {"property", "callerUser", "recipientResident", "recipientUser"})
    Optional<IntercomCall> findFirstByTenantIdAndRecipientUserIdAndStatusIn(
            UUID tenantId, UUID recipientUserId, List<IntercomCallStatus> statuses);

    List<IntercomCall> findByStatusAndCreatedAtBefore(IntercomCallStatus status, OffsetDateTime threshold);

    /** P8-A6: solo ids, para releer cada llamada con bloqueo sin un estado obsoleto en el contexto de persistencia. */
    @Query("SELECT c.id FROM IntercomCall c WHERE c.status = :status AND c.createdAt < :threshold")
    List<UUID> findIdsByStatusAndCreatedAtBefore(@Param("status") IntercomCallStatus status,
                                                 @Param("threshold") OffsetDateTime threshold);

    /** P8-A6: aceptadas sin conexion de ambos participantes desde antes del umbral. */
    @Query("SELECT c.id FROM IntercomCall c WHERE c.status = :status AND c.connectedAt IS NULL AND c.acceptedAt < :threshold")
    List<UUID> findIdsAcceptedNeverConnectedBefore(@Param("status") IntercomCallStatus status,
                                                   @Param("threshold") OffsetDateTime threshold);

    /**
     * P8-A6: serializa, hasta el fin de la transaccion, el inicio de llamadas que afectan al mismo participante
     * (bloqueo consultivo de PostgreSQL, sin filas ni tablas nuevas). Sin el, dos porterias podian comprobar «libre» a
     * la vez y crear dos llamadas activas para el mismo residente.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer lockParticipantForCall(@Param("key") String key);

    /** P8-A6: bloqueo para tareas programadas (sin contexto de comunidad; el id viene de la propia consulta). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM IntercomCall c WHERE c.id = :id")
    Optional<IntercomCall> findByIdForUpdate(@Param("id") UUID id);

    long countByTenantIdAndStatus(UUID tenantId, IntercomCallStatus status);

    /** P8-A3 (conciliacion): llamadas terminadas desde el umbral (sin datos de otras tablas). */
    @Query("SELECT c FROM IntercomCall c WHERE c.status IN :statuses AND c.endedAt >= :since")
    List<IntercomCall> findEndedSince(@Param("statuses") List<IntercomCallStatus> statuses,
                                      @Param("since") OffsetDateTime since);
}
