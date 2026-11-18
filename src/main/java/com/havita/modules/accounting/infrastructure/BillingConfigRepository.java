package com.havita.modules.accounting.infrastructure;

import com.havita.modules.accounting.domain.BillingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingConfigRepository extends JpaRepository<BillingConfig, UUID> {

    Optional<BillingConfig> findByTenantId(UUID tenantId);

    boolean existsByTenantId(UUID tenantId);
}
