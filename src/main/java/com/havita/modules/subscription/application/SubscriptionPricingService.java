package com.havita.modules.subscription.application;

import com.havita.modules.subscription.domain.SaaSSubscription;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class SubscriptionPricingService {

    private final PlanUsageService planUsageService;

    public BigDecimal calculateMonthlyGrossPrice(SaaSSubscription subscription) {
        long properties = planUsageService.getPropertiesCount(subscription.getTenantId());
        return subscription.getGrossPricePerProperty().multiply(new BigDecimal(properties));
    }

    public BigDecimal calculateMonthlyPlatformRevenue(SaaSSubscription subscription) {
        long properties = planUsageService.getPropertiesCount(subscription.getTenantId());
        return subscription.getPlatformPricePerProperty().multiply(new BigDecimal(properties));
    }

    public BigDecimal calculateMonthlyCommunityShare(SaaSSubscription subscription) {
        long properties = planUsageService.getPropertiesCount(subscription.getTenantId());
        return subscription.getCommunitySharePerProperty().multiply(new BigDecimal(properties));
    }
}
