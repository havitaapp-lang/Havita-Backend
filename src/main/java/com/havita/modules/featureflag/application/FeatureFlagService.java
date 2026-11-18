package com.havita.modules.featureflag.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.featureflag.api.dto.CreateFeatureFlagRequest;
import com.havita.modules.featureflag.api.dto.FeatureFlagResponse;
import com.havita.modules.featureflag.api.dto.OverrideFeatureRequest;
import com.havita.modules.featureflag.domain.FeatureFlag;
import com.havita.modules.featureflag.domain.TenantFeatureOverride;
import com.havita.modules.featureflag.infrastructure.FeatureFlagRepository;
import com.havita.modules.featureflag.infrastructure.TenantFeatureOverrideRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.subscription.domain.SaaSSubscription;
import com.havita.modules.subscription.infrastructure.SaaSSubscriptionRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final FeatureFlagRepository featureFlagRepository;
    private final TenantFeatureOverrideRepository overrideRepository;
    private final SaaSSubscriptionRepository subscriptionRepository;
    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public List<FeatureFlagResponse> findAllFlagsForCurrentTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        String currentTier = "STARTER";

        if (tenantId != null) {
            Optional<SaaSSubscription> sub = subscriptionRepository.findByTenantId(tenantId);
            if (sub.isPresent() && sub.get().getPlan() != null) {
                currentTier = sub.get().getPlan().getTier();
            }
        }

        final String tier = currentTier;

        return featureFlagRepository.findAll().stream().map(flag -> {
            boolean effective = isFeatureEnabledForTenant(flag, tenantId, tier);
            return toResponse(flag, effective);
        }).toList();
    }

    @Transactional(readOnly = true)
    public boolean isFeatureEnabled(String flagKey) {
        UUID tenantId = TenantContextHolder.getTenantId();
        FeatureFlag flag = featureFlagRepository.findByKey(flagKey)
                .orElseThrow(() -> new ResourceNotFoundException("FeatureFlag", flagKey));

        String currentTier = "STARTER";
        if (tenantId != null) {
            Optional<SaaSSubscription> sub = subscriptionRepository.findByTenantId(tenantId);
            if (sub.isPresent() && sub.get().getPlan() != null) {
                currentTier = sub.get().getPlan().getTier();
            }
        }

        return isFeatureEnabledForTenant(flag, tenantId, currentTier);
    }

    private boolean isFeatureEnabledForTenant(FeatureFlag flag, UUID tenantId, String tier) {
        // 1. Si globalmente está deshabilitado
        if (!Boolean.TRUE.equals(flag.getIsGlobalEnabled())) {
            return false;
        }

        // 2. Revisar si hay un override manual para este tenant
        if (tenantId != null) {
            Optional<TenantFeatureOverride> override = overrideRepository.findByTenantIdAndFeatureFlagId(tenantId, flag.getId());
            if (override.isPresent()) {
                return override.get().getEnabled();
            }
        }

        // 3. Evaluar gating por nivel del plan (Plan Tier)
        return flag.isAllowedForTier(tier);
    }

    @Transactional
    public FeatureFlagResponse createFlag(CreateFeatureFlagRequest request) {
        if (featureFlagRepository.existsByKey(request.key().trim())) {
            throw new BusinessRuleConflictException(
                    "FEATURE_FLAG_EXISTS",
                    "Ya existe un feature flag con la clave '" + request.key() + "'.");
        }

        FeatureFlag flag = FeatureFlag.builder()
                .key(request.key().trim().toUpperCase())
                .name(request.name().trim())
                .description(request.description())
                .category(request.category() != null ? request.category() : "Operación")
                .enabledByPlan(request.enabledByPlan().trim().toUpperCase())
                .isGlobalEnabled(request.isGlobalEnabled() != null ? request.isGlobalEnabled() : true)
                .isBeta(request.isBeta() != null ? request.isBeta() : false)
                .build();

        flag = featureFlagRepository.save(flag);
        log.info("Feature Flag creado: {} ({})", flag.getName(), flag.getKey());
        return toResponse(flag, flag.getIsGlobalEnabled());
    }

    @Transactional
    public FeatureFlagResponse toggleGlobal(UUID flagId, boolean enabled) {
        FeatureFlag flag = featureFlagRepository.findById(flagId)
                .orElseThrow(() -> new ResourceNotFoundException("FeatureFlag", flagId));

        flag.setIsGlobalEnabled(enabled);
        flag = featureFlagRepository.save(flag);
        log.info("Feature Flag {} toggle global a {}", flag.getKey(), enabled);
        return toResponse(flag, enabled);
    }

    @Transactional
    public void setTenantOverride(UUID tenantId, UUID flagId, OverrideFeatureRequest request) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        FeatureFlag flag = featureFlagRepository.findById(flagId)
                .orElseThrow(() -> new ResourceNotFoundException("FeatureFlag", flagId));

        TenantFeatureOverride override = overrideRepository.findByTenantIdAndFeatureFlagId(tenantId, flagId)
                .orElseGet(() -> {
                    TenantFeatureOverride o = TenantFeatureOverride.builder()
                            .featureFlag(flag)
                            .build();
                    o.setTenantId(tenantId);
                    return o;
                });

        override.setEnabled(request.enabled());
        override.setReason(request.reason());
        overrideRepository.save(override);

        log.info("Override configurado para tenant {}: Flag {} = {}", tenant.getName(), flag.getKey(), request.enabled());
    }

    private FeatureFlagResponse toResponse(FeatureFlag f, boolean effective) {
        return new FeatureFlagResponse(
                f.getId(),
                f.getKey(),
                f.getName(),
                f.getDescription(),
                f.getCategory(),
                f.getEnabledByPlan(),
                f.getIsGlobalEnabled(),
                f.getIsBeta(),
                effective
        );
    }
}
