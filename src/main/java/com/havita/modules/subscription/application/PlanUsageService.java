package com.havita.modules.subscription.application;

import com.havita.modules.property.infrastructure.PropertyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanUsageService {

    private final PropertyRepository propertyRepository;

    @Transactional(readOnly = true)
    public long getPropertiesCount(UUID tenantId) {
        return propertyRepository.countByTenantId(tenantId);
    }
}
