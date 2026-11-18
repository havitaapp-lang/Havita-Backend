package com.havita.modules.resident.infrastructure;

import com.havita.modules.resident.domain.ResidentInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResidentInvitationRepository extends JpaRepository<ResidentInvitation, UUID> {
    Optional<ResidentInvitation> findByTokenHash(String tokenHash);

    /** SEC-7: aceptacion de una invitacion; bloquea la fila para que dos registros simultaneos no la usen ambos. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ResidentInvitation i where i.tokenHash = :tokenHash")
    Optional<ResidentInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
    List<ResidentInvitation> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);
    Optional<ResidentInvitation> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByPropertyIdAndInvitationTypeIgnoreCaseAndStatus(UUID propertyId, String invitationType, com.havita.modules.resident.domain.InvitationStatus status);

    List<ResidentInvitation> findByPropertyIdAndInviterUserIdAndStatus(UUID propertyId, UUID inviterUserId, com.havita.modules.resident.domain.InvitationStatus status);
}
