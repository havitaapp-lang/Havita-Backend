package com.havita.modules.marketplace.application;

import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.marketplace.api.dto.MarketplaceConfigResponse;
import com.havita.modules.marketplace.api.dto.UpdateMarketplaceConfigRequest;
import com.havita.modules.marketplace.domain.MarketplaceConfig;
import com.havita.modules.marketplace.domain.MarketplaceMode;
import com.havita.modules.marketplace.infrastructure.MarketplaceConfigRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceConfigService {

    private final MarketplaceConfigRepository configRepository;
    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public MarketplaceConfigResponse getConfig(UUID tenantId) {
        MarketplaceConfig config = configRepository.findByTenantId(tenantId).orElse(null);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        boolean enabled = tenant.getActiveFeaturesList().contains("marketplace");
        if (config == null) {
            return new MarketplaceConfigResponse(MarketplaceMode.AUTO_APPROVE.name(), 30, enabled);
        }
        return new MarketplaceConfigResponse(config.getMode().name(), config.getListingDurationDays(), enabled);
    }

    @Transactional
    public MarketplaceConfigResponse updateConfig(UUID tenantId, UpdateMarketplaceConfigRequest req) {
        MarketplaceConfig config = configRepository.findByTenantId(tenantId).orElseGet(() -> {
            MarketplaceConfig c = new MarketplaceConfig();
            c.setTenantId(tenantId);
            return c;
        });
        config.setMode(req.mode());
        config.setListingDurationDays(req.listingDurationDays());
        MarketplaceConfig saved = configRepository.save(config);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        boolean enabled = tenant.getActiveFeaturesList().contains("marketplace");
        return new MarketplaceConfigResponse(saved.getMode().name(), saved.getListingDurationDays(), enabled);
    }

    @Transactional
    public void toggleFeature(UUID tenantId, boolean enabled) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        List<String> features = new ArrayList<>(tenant.getActiveFeaturesList());
        if (enabled && !features.contains("marketplace")) {
            features.add("marketplace");
        } else if (!enabled) {
            features.remove("marketplace");
        }
        tenant.setActiveFeaturesList(features);
        tenantRepository.save(tenant);
        log.info("Marketplace feature toggled to {} for tenant {}", enabled, tenantId);
    }

    @Transactional(readOnly = true)
    public MarketplaceMode getMode(UUID tenantId) {
        return configRepository.findByTenantId(tenantId)
                .map(MarketplaceConfig::getMode)
                .orElse(MarketplaceMode.AUTO_APPROVE);
    }

    @Transactional(readOnly = true)
    public int getListingDurationDays(UUID tenantId) {
        return configRepository.findByTenantId(tenantId)
                .map(MarketplaceConfig::getListingDurationDays)
                .orElse(30);
    }
}
