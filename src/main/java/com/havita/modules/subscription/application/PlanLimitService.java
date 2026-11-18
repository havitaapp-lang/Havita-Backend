package com.havita.modules.subscription.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.subscription.domain.SaaSSubscription;
import com.havita.modules.subscription.infrastructure.SaaSSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanLimitService {

    private final SaaSSubscriptionRepository subscriptionRepository;
    private final PlanUsageService planUsageService;

    public void validatePropertyCreation(UUID tenantId) {
        validatePropertyCreationBulk(tenantId, 1);
    }

    public void validatePropertyCreationBulk(UUID tenantId, int countToAdd) {
        SaaSSubscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("NO_SUBSCRIPTION", "Tenant without active subscription"));

        Integer max = sub.getPlan().getMaxProperties();
        if (max != null) {
            long current = planUsageService.getPropertiesCount(tenantId);
            if (current + countToAdd > max) {
                throw new BusinessRuleConflictException("PLAN_LIMIT_EXCEEDED", "El plan " + sub.getPlan().getName() + " permite un maximo de " + max + " propiedades. Tienes " + current + " y estas intentando crear " + countToAdd + ".");
            }
        }
    }
}
