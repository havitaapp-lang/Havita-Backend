package com.havita.modules.subscription.application;
import com.havita.modules.subscription.domain.SaaSPlanStatus;
import com.havita.modules.subscription.domain.SaaSSubscriptionStatus;
import com.havita.modules.subscription.domain.SaaSInvoiceStatus;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.subscription.api.dto.CreatePlanRequest;
import com.havita.modules.subscription.api.dto.PlanResponse;
import com.havita.modules.subscription.api.dto.SaaSInvoiceResponse;
import com.havita.modules.subscription.api.dto.SubscribeRequest;
import com.havita.modules.subscription.api.dto.SubscriptionResponse;
import com.havita.modules.subscription.domain.SaaSInvoice;
import com.havita.modules.subscription.domain.SaaSPlan;
import com.havita.modules.subscription.domain.SaaSSubscription;
import com.havita.modules.subscription.infrastructure.SaaSInvoiceRepository;
import com.havita.modules.subscription.infrastructure.SaaSPlanRepository;
import com.havita.modules.subscription.infrastructure.SaaSSubscriptionRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final com.havita.security.AccessValidator accessValidator;

    private final SaaSPlanRepository planRepository;
    private final SaaSSubscriptionRepository subscriptionRepository;
    private final SaaSInvoiceRepository invoiceRepository;
    private final TenantRepository tenantRepository;
    private final SubscriptionPricingService pricingService;

    // --- Planes ---

    @Transactional(readOnly = true)
    public List<PlanResponse> findAllPlans() {
        return planRepository.findByStatus(SaaSPlanStatus.ACTIVE)
                .stream()
                .map(this::toPlanResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PlanResponse findPlanById(UUID id) {
        SaaSPlan plan = planRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SaaSPlan", id));
        return toPlanResponse(plan);
    }

    @Transactional
    public PlanResponse createPlan(CreatePlanRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        if (planRepository.existsByTierIgnoreCase(request.tier().trim())) {
            throw new BusinessRuleConflictException(
                    "PLAN_TIER_EXISTS",
                    "Ya existe un plan con el nivel (tier) '" + request.tier() + "'.");
        }

        SaaSPlan plan = SaaSPlan.builder()
                .tier(request.tier().trim().toUpperCase())
                .name(request.name().trim())
                .tagline(request.tagline())
                .grossPricePerProperty(request.grossPricePerProperty())
                .platformPricePerProperty(request.platformPricePerProperty() != null ? request.platformPricePerProperty() : BigDecimal.ZERO)
                .communitySharePerProperty(request.communitySharePerProperty() != null ? request.communitySharePerProperty() : BigDecimal.ZERO)
                .currency(request.currency() != null ? request.currency() : "COP")
                .billingPeriod(request.billingPeriod() != null ? request.billingPeriod() : "MONTHLY")
                .maxProperties(request.maxProperties())
                .maxStorageBytes(request.maxStorageBytes())
                .maxAdminUsers(request.maxAdminUsers())
                .maxStaffUsers(request.maxStaffUsers())
                .maxPorters(request.maxPorters())
                .maxDocuments(request.maxDocuments())
                .maxReservations(request.maxReservations())
                .popular(request.popular() != null ? request.popular() : false)
                .features(request.features())
                .status(SaaSPlanStatus.ACTIVE)
                .build();

        plan = planRepository.save(plan);
        log.info("Plan SaaS creado: {} ({})", plan.getName(), plan.getTier());
        return toPlanResponse(plan);
    }

    // --- Suscripciones ---

    @Transactional(readOnly = true)
    public SubscriptionResponse getCurrentSubscription() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        SaaSSubscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("SaaSSubscription", "Tenant: " + tenantId));
        return toSubscriptionResponse(sub);
    }

    @Transactional
    public SubscriptionResponse subscribeTenant(UUID targetTenantId, SubscribeRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        Tenant tenant = tenantRepository.findById(targetTenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", targetTenantId));

        SaaSPlan plan = planRepository.findById(request.planId())
                .orElseThrow(() -> new ResourceNotFoundException("SaaSPlan", request.planId()));

        LocalDate expires = request.expiresAt() != null ? request.expiresAt() : LocalDate.now().plusMonths(1);

        SaaSSubscription sub = subscriptionRepository.findByTenantId(targetTenantId)
                .orElseGet(() -> {
                    SaaSSubscription s = SaaSSubscription.builder()
                            .plan(plan)
                            .startDate(LocalDate.now())
                            .build();
                    s.setTenantId(targetTenantId);
                    return s;
                });

        sub.setPlan(plan);
        sub.setGrossPricePerProperty(plan.getGrossPricePerProperty());
        sub.setPlatformPricePerProperty(plan.getPlatformPricePerProperty());
        sub.setCommunitySharePerProperty(plan.getCommunitySharePerProperty());
        sub.setCurrency(plan.getCurrency());
        sub.setBillingCycle(request.billingCycle() != null ? request.billingCycle() : "MONTHLY");
        sub.setStatus(SaaSSubscriptionStatus.ACTIVE);
        sub.setExpiresAt(expires);
        sub.setCurrentPeriodStart(LocalDate.now());
        sub.setCurrentPeriodEnd(expires);
        sub.setPaymentMethod(request.paymentMethod());
        sub.setAutoRenew(request.autoRenew() != null ? request.autoRenew() : true);

        sub = subscriptionRepository.save(sub);

        // Actualizar plan en la entidad Tenant
        tenant.setPlanId(plan.getId());
        tenant.setPlanName(plan.getName());
        tenant.setStorageLimitMb(plan.getMaxStorageBytes() != null ? (int)(plan.getMaxStorageBytes() / (1024 * 1024)) : 5120);
        tenantRepository.save(tenant);

        log.info("Suscripción activa para tenant {}: Plan {}", tenant.getName(), plan.getName());
        return toSubscriptionResponse(sub);
    }

    @Transactional
    public SubscriptionResponse changePlan(String planCode) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        
        SaaSSubscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("NO_SUBSCRIPTION", "Tenant without active subscription"));

        SaaSPlan newPlan = planRepository.findByTierIgnoreCase(planCode.trim())
                .orElseThrow(() -> new ResourceNotFoundException("SaaSPlan", "Code: " + planCode));

        // Actualizamos el snapshot
        sub.setPlan(newPlan);
        sub.setGrossPricePerProperty(newPlan.getGrossPricePerProperty());
        sub.setPlatformPricePerProperty(newPlan.getPlatformPricePerProperty());
        sub.setCommunitySharePerProperty(newPlan.getCommunitySharePerProperty());
        sub.setCurrency(newPlan.getCurrency());
        
        sub = subscriptionRepository.save(sub);

        // Actualizamos el límite en la entidad Tenant (si es null = ilimitado -> pasamos 50TB para evitar null pointer, o lo manejamos distinto)
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        tenant.setPlanId(newPlan.getId());
        tenant.setPlanName(newPlan.getName());
        tenant.setStorageLimitMb(newPlan.getMaxStorageBytes() != null ? (int)(newPlan.getMaxStorageBytes() / (1024 * 1024)) : 5120000);
        tenantRepository.save(tenant);

        log.info("Cambio de plan exitoso para {}: a {}", tenant.getName(), newPlan.getName());
        return toSubscriptionResponse(sub);
    }

    // --- Super Admin Methods ---

    @Transactional(readOnly = true)
    public Page<SubscriptionResponse> findAllSubscriptions(Pageable pageable) {
        return subscriptionRepository.findAll(pageable).map(this::toSubscriptionResponse);
    }

    @Transactional
    public SubscriptionResponse updateSubscriptionStatus(UUID subscriptionId, SaaSSubscriptionStatus status) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        SaaSSubscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("SaaSSubscription", subscriptionId));
        
        sub.setStatus(status);
        sub = subscriptionRepository.save(sub);
        log.info("Estado de suscripción {} actualizado a {}", subscriptionId, status);
        return toSubscriptionResponse(sub);
    }

    @Transactional
    public SubscriptionResponse adminChangeSubscriptionPlan(UUID subscriptionId, UUID planId) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        SaaSSubscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("SaaSSubscription", subscriptionId));

        SaaSPlan newPlan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("SaaSPlan", planId));

        sub.setPlan(newPlan);
        sub.setGrossPricePerProperty(newPlan.getGrossPricePerProperty());
        sub.setPlatformPricePerProperty(newPlan.getPlatformPricePerProperty());
        sub.setCommunitySharePerProperty(newPlan.getCommunitySharePerProperty());
        sub.setCurrency(newPlan.getCurrency());
        
        sub = subscriptionRepository.save(sub);

        Tenant tenant = tenantRepository.findById(sub.getTenantId()).orElse(null);
        if (tenant != null) {
            tenant.setPlanId(newPlan.getId());
            tenant.setPlanName(newPlan.getName());
            tenant.setStorageLimitMb(newPlan.getMaxStorageBytes() != null ? (int)(newPlan.getMaxStorageBytes() / (1024 * 1024)) : 5120000);
            tenantRepository.save(tenant);
        }

        log.info("SuperAdmin cambió plan de suscripción {} a {}", subscriptionId, newPlan.getName());
        return toSubscriptionResponse(sub);
    }

    // --- Facturación SaaS ---

    @Transactional(readOnly = true)
    public Page<SaaSInvoiceResponse> findInvoices(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return invoiceRepository.findByTenantId(tenantId, pageable).map(this::toInvoiceResponse);
    }

    @Transactional
    public SaaSInvoiceResponse generateInvoice(UUID targetTenantId, String period) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        Tenant tenant = tenantRepository.findById(targetTenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", targetTenantId));

        SaaSSubscription sub = subscriptionRepository.findByTenantId(targetTenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("NO_SUBSCRIPTION", "El tenant no tiene suscripción activa."));

        BigDecimal amount = pricingService.calculateMonthlyGrossPrice(sub);
        BigDecimal tax = amount.multiply(new BigDecimal("0.19")); // IVA 19%
        BigDecimal total = amount.add(tax);

        String invoiceNum = "INV-" + LocalDate.now().getYear() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        SaaSInvoice invoice = SaaSInvoice.builder()
                .subscription(sub)
                .invoiceNumber(invoiceNum)
                .period(period)
                .amountCop(amount)
                .taxCop(tax)
                .totalCop(total)
                .issuedAt(OffsetDateTime.now())
                .dueAt(OffsetDateTime.now().plusDays(10))
                .status(SaaSInvoiceStatus.PENDING)
                .build();
        invoice.setTenantId(targetTenantId);

        invoice = invoiceRepository.save(invoice);
        log.info("Factura SaaS emitida: {} para tenant {}", invoiceNum, tenant.getName());
        return toInvoiceResponse(invoice);
    }

    @Transactional
    public SaaSInvoiceResponse markInvoicePaid(UUID invoiceId, String paymentRef) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        SaaSInvoice invoice = invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("SaaSInvoice", invoiceId));

        invoice.markAsPaid(paymentRef);
        invoice = invoiceRepository.save(invoice);
        log.info("Factura SaaS {} pagada exitosamente", invoice.getInvoiceNumber());
        return toInvoiceResponse(invoice);
    }

    private PlanResponse toPlanResponse(SaaSPlan p) {
        return new PlanResponse(
                p.getId(),
                p.getTier(),
                p.getName(),
                p.getTagline(),
                p.getGrossPricePerProperty(),
                p.getPlatformPricePerProperty(),
                p.getCommunitySharePerProperty(),
                p.getCurrency(),
                p.getBillingPeriod(),
                p.getMaxProperties(),
                p.getMaxStorageBytes(),
                p.getMaxAdminUsers(),
                p.getMaxStaffUsers(),
                p.getMaxPorters(),
                p.getMaxDocuments(),
                p.getMaxReservations(),
                p.getPopular(),
                p.getFeatures(),
                p.getStatus(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    private SubscriptionResponse toSubscriptionResponse(SaaSSubscription s) {
        String tenantName = tenantRepository.findById(s.getTenantId())
                .map(Tenant::getName)
                .orElse("N/A");

        // The gross price of the subscription is dynamically calculated via SubscriptionPricingService in the controller.
        // For backwards compatibility in this DTO, we can return the grossPricePerProperty for now.
        return new SubscriptionResponse(
                s.getId(),
                s.getTenantId(),
                tenantName,
                s.getPlan().getId(),
                s.getPlan().getName(),
                s.getPlan().getTier(),
                s.getGrossPricePerProperty(),
                s.getBillingCycle(),
                s.getStatus(),
                s.getStartDate(),
                s.getExpiresAt(),
                s.getPaymentMethod(),
                s.getAutoRenew(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }

    private SaaSInvoiceResponse toInvoiceResponse(SaaSInvoice i) {
        String tenantName = tenantRepository.findById(i.getTenantId())
                .map(Tenant::getName)
                .orElse("N/A");

        return new SaaSInvoiceResponse(
                i.getId(),
                i.getTenantId(),
                tenantName,
                i.getSubscription() != null ? i.getSubscription().getId() : null,
                i.getInvoiceNumber(),
                i.getPeriod(),
                i.getAmountCop(),
                i.getTaxCop(),
                i.getTotalCop(),
                i.getIssuedAt(),
                i.getDueAt(),
                i.getPaidAt(),
                i.getStatus(),
                i.getPaymentReference(),
                i.getCreatedAt(),
                i.getUpdatedAt()
        );
    }
}
