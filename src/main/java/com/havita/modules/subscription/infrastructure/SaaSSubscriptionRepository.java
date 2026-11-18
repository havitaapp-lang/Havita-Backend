package com.havita.modules.subscription.infrastructure;

import com.havita.modules.subscription.domain.SaaSSubscriptionStatus;

import com.havita.modules.subscription.domain.SaaSSubscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SaaSSubscriptionRepository extends JpaRepository<SaaSSubscription, UUID> {

    Optional<SaaSSubscription> findByTenantId(UUID tenantId);

    Page<SaaSSubscription> findByStatus(SaaSSubscriptionStatus status, Pageable pageable);

    boolean existsByTenantId(UUID tenantId);

    long countByStatus(SaaSSubscriptionStatus status);
}
