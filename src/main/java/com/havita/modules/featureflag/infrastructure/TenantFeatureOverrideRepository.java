package com.havita.modules.featureflag.infrastructure;

import com.havita.modules.featureflag.domain.TenantFeatureOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantFeatureOverrideRepository extends JpaRepository<TenantFeatureOverride, UUID> {

    List<TenantFeatureOverride> findByTenantId(UUID tenantId);

    Optional<TenantFeatureOverride> findByTenantIdAndFeatureFlagId(UUID tenantId, UUID featureFlagId);

    Optional<TenantFeatureOverride> findByTenantIdAndFeatureFlagKey(UUID tenantId, String key);
}
