package com.havita.modules.marketplace.infrastructure;

import com.havita.modules.marketplace.domain.MarketplaceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MarketplaceConfigRepository extends JpaRepository<MarketplaceConfig, UUID> {
    Optional<MarketplaceConfig> findByTenantId(UUID tenantId);
}
