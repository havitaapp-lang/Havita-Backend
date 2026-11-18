package com.havita.modules.assembly.infrastructure;

import com.havita.modules.assembly.domain.VotingDelegation;
import com.havita.modules.assembly.domain.VotingDelegationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VotingDelegationRepository extends JpaRepository<VotingDelegation, UUID> {
    Optional<VotingDelegation> findByPropertyIdAndStatusAndTenantId(UUID propertyId, VotingDelegationStatus status, UUID tenantId);
    List<VotingDelegation> findByPropertyIdAndTenantId(UUID propertyId, UUID tenantId);
    List<VotingDelegation> findByStatusAndTenantId(VotingDelegationStatus status, UUID tenantId);
}
