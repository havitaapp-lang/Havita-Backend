package com.havita.modules.organization.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.audit.application.AuditService;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.api.dto.CreateTenantRequest;
import com.havita.modules.organization.api.dto.TenantResponse;
import com.havita.modules.organization.api.dto.UpdateTenantRequest;
import com.havita.modules.organization.domain.Organization;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.domain.TenantStatus;
import com.havita.modules.organization.infrastructure.OrganizationRepository;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.subscription.domain.SaaSPlan;
import com.havita.modules.subscription.domain.SaaSSubscription;
import com.havita.modules.subscription.domain.SaaSSubscriptionStatus;
import com.havita.modules.subscription.infrastructure.SaaSPlanRepository;
import com.havita.modules.subscription.infrastructure.SaaSSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final com.havita.security.AccessValidator accessValidator;

    private final TenantRepository tenantRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final PropertyRepository propertyRepository;
    private final PasswordEncoder passwordEncoder;
    private final SaaSPlanRepository saasPlanRepository;
    private final SaaSSubscriptionRepository saasSubscriptionRepository;
    private final AuditService auditService;
    private final com.havita.modules.identity.application.GlobalEmailValidationService globalEmailValidationService;

    @Transactional(readOnly = true)
    public Page<TenantResponse> findAll(String search, TenantStatus status, Pageable pageable) {
        Page<Tenant> page;
        if (search != null && !search.isBlank()) {
            page = tenantRepository.searchByNameOrSlugOrSubdomain(search.trim(), pageable);
        } else if (status != null) {
            page = tenantRepository.findByStatus(status, pageable);
        } else {
            page = tenantRepository.findAll(pageable);
        }
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public TenantResponse findById(UUID id) {
        Tenant tenant = tenantRepository.findById(id)
                .filter(this::inCallerScope)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", id));
        return toResponse(tenant);
    }

    /**
     * SEC-9: la administracion global ve cualquier comunidad; cualquier otro llamante, solo la comunidad de su
     * peticion. Fuera de su alcance la comunidad "no existe" (404, igual que un id inexistente): no permite enumerar.
     */
    private boolean inCallerScope(Tenant tenant) {
        return com.havita.security.AuthorizationScope.isGlobal()
                || tenant.getId().equals(com.havita.security.TenantContextHolder.getTenantId());
    }

    @Transactional(readOnly = true)
    public TenantResponse findBySlug(String slug) {
        Tenant tenant = tenantRepository.findBySlug(slug)
                .filter(this::inCallerScope)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", slug));
        return toResponse(tenant);
    }

    @Transactional(readOnly = true)
    public List<TenantResponse> findByOrganization(UUID organizationId) {
        return tenantRepository.findByOrganizationId(organizationId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new BusinessRuleConflictException(
                    "TENANT_SLUG_EXISTS",
                    "Ya existe un tenant con el slug '" + request.slug() + "'");
        }
        if (tenantRepository.existsBySubdomain(request.subdomain())) {
            throw new BusinessRuleConflictException(
                    "TENANT_SUBDOMAIN_EXISTS",
                    "Ya existe un tenant con el subdominio '" + request.subdomain() + "'");
        }

        Organization org = null;
        if (request.organizationId() != null) {
            org = organizationRepository.findById(request.organizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Organization", request.organizationId()));
        }

        SaaSPlan resolvedPlan = resolvePlan(request.planId(), request.planName());

        Tenant tenant = Tenant.builder()
                .organization(org)
                .name(request.name())
                .slug(request.slug())
                .subdomain(request.subdomain())
                .nit(request.nit())
                .city(request.city())
                .address(request.address())
                .phone(request.phone() != null && !request.phone().isBlank() ? request.phone() : request.adminPhone())
                .contactEmail(request.contactEmail() != null && !request.contactEmail().isBlank() ? request.contactEmail() : request.adminEmail())
                .status(TenantStatus.ACTIVE)
                .storageLimitMb(request.storageLimitMb() != null ? request.storageLimitMb() : 10240)
                .contractedProperties(request.propertiesCount() != null ? request.propertiesCount() : 0)
                .activeFeatures(Tenant.DEFAULT_ACTIVE_FEATURES)
                .planId(resolvedPlan != null ? resolvedPlan.getId() : null)
                .planName(resolvedPlan != null ? resolvedPlan.getName() : (request.planName() != null ? request.planName() : "Esencial"))
                .build();

        tenant = tenantRepository.save(tenant);
        log.info("Tenant creado: {} (slug: {})", tenant.getName(), tenant.getSlug());

        // Create admin user
        if (request.adminEmail() != null && !request.adminEmail().isBlank()) {
            globalEmailValidationService.validateEmailUniqueness(request.adminEmail());
            
            User admin = User.builder()
                    .email(request.adminEmail().toLowerCase())
                    .passwordHash(passwordEncoder.encode(request.adminPassword() != null && !request.adminPassword().isBlank() ? request.adminPassword() : "Temporal123!"))
                    .fullName(request.adminName() != null && !request.adminName().isBlank() ? request.adminName() : "Administrador " + tenant.getName())
                    .phone(request.adminPhone() != null ? request.adminPhone() : request.phone())
                    .build();
            admin = userRepository.save(admin);

            if (membershipRepository.findByUserIdAndTenantId(admin.getId(), tenant.getId()).isEmpty()) {
                Membership adminMembership = Membership.builder()
                        .user(admin)
                        .tenantId(tenant.getId())
                        .role(RoleType.COMMUNITY_ADMIN)
                        .build();
                membershipRepository.save(adminMembership);
            }
        }

        // Create SaaSSubscription if plan is resolved
        if (resolvedPlan != null) {
            SaaSSubscription sub = SaaSSubscription.builder()
                    .plan(resolvedPlan)
                    .grossPricePerProperty(resolvedPlan.getGrossPricePerProperty())
                    .platformPricePerProperty(resolvedPlan.getPlatformPricePerProperty())
                    .communitySharePerProperty(resolvedPlan.getCommunitySharePerProperty())
                    .currency(resolvedPlan.getCurrency())
                    .billingCycle(resolvedPlan.getBillingPeriod())
                    .build();
            sub.setTenantId(tenant.getId());
            sub.activate(LocalDate.now().plusYears(1));
            saasSubscriptionRepository.save(sub);
            log.info("SaaSSubscription creada para tenant: {}", tenant.getId());
        }

        auditService.recordSystemAudit(
                "Alta de Comunidad",
                "Comunidades",
                "Se creó la comunidad " + tenant.getName() + " con plan " + tenant.getPlanName(),
                tenant.getId(),
                tenant.getName(),
                "SUCCESS"
        );

        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse update(UUID id, UpdateTenantRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.COMMUNITY_ADMIN, com.havita.modules.identity.domain.RoleType.SUPER_ADMIN), java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN, com.havita.modules.identity.domain.RoleType.PLATFORM_ADMIN));
        Tenant tenant = tenantRepository.findById(id)
                .filter(this::inCallerScope)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", id));

        if (request.name() != null && !request.name().isBlank()) tenant.setName(request.name().trim());
        if (request.subdomain() != null) {
            String currentSubdomain = tenant.getSubdomain() != null ? tenant.getSubdomain().trim().toLowerCase() : "";
            String requestedSubdomain = request.subdomain().trim().toLowerCase();
            if (!requestedSubdomain.equals(currentSubdomain)) {
                // SEC-9: solo un SUPER_ADMIN global (una membresia SUPER_ADMIN con comunidad no basta).
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                boolean isSuperAdmin = com.havita.security.AuthorizationScope.isGlobal() && auth.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
                if (!isSuperAdmin) {
                    throw new AccessDeniedException("El subdominio no puede ser modificado. Solo un SUPER_ADMIN tiene permisos para cambiar el subdominio de una copropiedad.");
                }
                if (tenantRepository.existsBySubdomain(requestedSubdomain)) {
                    throw new BusinessRuleConflictException(
                            "TENANT_SUBDOMAIN_EXISTS",
                            "Ya existe un tenant con el subdominio '" + requestedSubdomain + "'");
                }
                tenant.setSubdomain(requestedSubdomain);
            }
        }
        if (request.nit() != null) tenant.setNit(request.nit().trim());
        if (request.city() != null) tenant.setCity(request.city().trim());
        if (request.address() != null) tenant.setAddress(request.address().trim());
        if (request.phone() != null) tenant.setPhone(request.phone().trim());
        if (request.storageLimitMb() != null) tenant.setStorageLimitMb(request.storageLimitMb());
        if (request.logoUrl() != null) tenant.setLogoUrl(request.logoUrl());
        if (request.primaryColor() != null) tenant.setPrimaryColor(request.primaryColor());
        if (request.secondaryColor() != null) tenant.setSecondaryColor(request.secondaryColor());
        if (request.planName() != null) tenant.setPlanName(request.planName());
        if (request.contactEmail() != null) tenant.setContactEmail(request.contactEmail().trim());
        if (request.propertiesCount() != null && request.propertiesCount() >= 0) {
            tenant.setContractedProperties(request.propertiesCount());
        }
        if (request.activeFeatures() != null) {
            tenant.setActiveFeaturesList(request.activeFeatures());
        }

        if (request.organizationId() != null) {
            Organization org = organizationRepository.findById(request.organizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Organization", request.organizationId()));
            tenant.setOrganization(org);
        } else {
            tenant.setOrganization(null);
        }

        // Update or assign COMMUNITY_ADMIN user if admin fields are provided
        if ((request.adminName() != null && !request.adminName().isBlank())
                || (request.adminEmail() != null && !request.adminEmail().isBlank())
                || (request.adminPhone() != null && !request.adminPhone().isBlank())) {
            List<Membership> adminMemberships = membershipRepository.findByTenantIdAndRole(tenant.getId(), RoleType.COMMUNITY_ADMIN);
            if (!adminMemberships.isEmpty()) {
                User adminUser = adminMemberships.get(0).getUser();
                if (request.adminName() != null && !request.adminName().isBlank()) {
                    adminUser.setFullName(request.adminName().trim());
                }
                if (request.adminPhone() != null && !request.adminPhone().isBlank()) {
                    adminUser.setPhone(request.adminPhone().trim());
                    tenant.setPhone(request.adminPhone().trim());
                }
                if (request.adminEmail() != null && !request.adminEmail().isBlank()) {
                    tenant.setContactEmail(request.adminEmail().trim());
                }
                if (request.adminPassword() != null && !request.adminPassword().isBlank()) {
                    adminUser.setPasswordHash(passwordEncoder.encode(request.adminPassword()));
                }
                userRepository.save(adminUser);
            } else if (request.adminEmail() != null && !request.adminEmail().isBlank()) {
                globalEmailValidationService.validateEmailUniqueness(request.adminEmail());
                
                final String fallbackName = tenant.getName();
                final String fallbackPhone = tenant.getPhone();
                User adminUser = User.builder()
                        .email(request.adminEmail().trim().toLowerCase())
                        .fullName(request.adminName() != null && !request.adminName().isBlank() ? request.adminName().trim() : "Administrador " + fallbackName)
                        .phone(request.adminPhone() != null ? request.adminPhone().trim() : fallbackPhone)
                        .passwordHash(passwordEncoder.encode(request.adminPassword() != null && !request.adminPassword().isBlank() ? request.adminPassword() : "Temporal123!"))
                        .build();
                adminUser = userRepository.save(adminUser);
                
                Membership m = Membership.builder()
                        .user(adminUser)
                        .tenantId(tenant.getId())
                        .role(RoleType.COMMUNITY_ADMIN)
                        .build();
                membershipRepository.save(m);
            }
        }

        tenant = tenantRepository.save(tenant);
        log.info("Tenant actualizado: {}", tenant.getId());

        auditService.recordSystemAudit(
                "Actualización de Comunidad",
                "Comunidades",
                "Se actualizaron los datos generales de " + tenant.getName(),
                tenant.getId(),
                tenant.getName(),
                "SUCCESS"
        );

        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse toggleFeature(UUID id, String featureKey, boolean enabled) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN, com.havita.modules.identity.domain.RoleType.PLATFORM_ADMIN));
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", id));

        String normalizedKey = featureKey.trim().toLowerCase();
        List<String> current = tenant.getActiveFeaturesList();

        if (enabled) {
            if (!current.contains(normalizedKey)) {
                current.add(normalizedKey);
            }
        } else {
            current.removeIf(k -> k.equalsIgnoreCase(normalizedKey));
        }

        tenant.setActiveFeaturesList(current);
        tenant = tenantRepository.save(tenant);

        auditService.recordSystemAudit(
                (enabled ? "Activación" : "Desactivación") + " de Módulo",
                "Features",
                "Módulo '" + normalizedKey + "' " + (enabled ? "activado" : "desactivado") + " en " + tenant.getName(),
                tenant.getId(),
                tenant.getName(),
                "SUCCESS"
        );

        log.info("Módulo {} {} para tenant {}", normalizedKey, enabled ? "activado" : "desactivado", tenant.getName());
        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse changePlan(UUID id, UUID planId) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN, com.havita.modules.identity.domain.RoleType.PLATFORM_ADMIN));
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", id));

        SaaSPlan plan = saasPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("SaaSPlan", planId));

        String previousPlan = tenant.getPlanName() != null ? tenant.getPlanName() : "N/A";

        SaaSSubscription sub = saasSubscriptionRepository.findByTenantId(id)
                .orElseGet(() -> {
                    SaaSSubscription s = SaaSSubscription.builder()
                            .plan(plan)
                            .startDate(LocalDate.now())
                            .build();
                    s.setTenantId(id);
                    return s;
                });

        sub.setPlan(plan);
        sub.setGrossPricePerProperty(plan.getGrossPricePerProperty());
        sub.setPlatformPricePerProperty(plan.getPlatformPricePerProperty());
        sub.setCommunitySharePerProperty(plan.getCommunitySharePerProperty());
        sub.setCurrency(plan.getCurrency() != null ? plan.getCurrency() : "COP");
        sub.setBillingCycle(plan.getBillingPeriod() != null ? plan.getBillingPeriod() : "MONTHLY");
        sub.setStatus(SaaSSubscriptionStatus.ACTIVE);
        if (sub.getExpiresAt() == null) {
            sub.setExpiresAt(LocalDate.now().plusYears(1));
        }
        saasSubscriptionRepository.save(sub);

        tenant.setPlanId(plan.getId());
        tenant.setPlanName(plan.getName());
        if (plan.getMaxStorageBytes() != null) {
            tenant.setStorageLimitMb((int) (plan.getMaxStorageBytes() / (1024 * 1024)));
        }
        tenant = tenantRepository.save(tenant);

        auditService.recordSystemAudit(
                "Cambio de Plan SaaS",
                "Suscripciones",
                "Cambio de plan de " + previousPlan + " a " + plan.getName() + " en " + tenant.getName(),
                tenant.getId(),
                tenant.getName(),
                "SUCCESS"
        );

        log.info("Plan actualizado para tenant {}: {} -> {}", tenant.getName(), previousPlan, plan.getName());
        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse suspendTenant(UUID id, String reason) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN, com.havita.modules.identity.domain.RoleType.PLATFORM_ADMIN));
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", id));

        if (tenant.isCancelled()) {
            throw new BusinessRuleConflictException(
                    "TENANT_CANCELLED",
                    "No se puede suspender un tenant cancelado.");
        }

        tenant.suspend(reason);
        tenant = tenantRepository.save(tenant);
        log.info("Tenant suspendido: {} - Razon: {}", tenant.getId(), reason);

        auditService.recordSystemAudit(
                "Suspensión de Comunidad",
                "Comunidades",
                "Comunidad suspendida. Motivo: " + reason,
                tenant.getId(),
                tenant.getName(),
                "WARNING"
        );

        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse activateTenant(UUID id) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN, com.havita.modules.identity.domain.RoleType.PLATFORM_ADMIN));
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", id));

        if (tenant.isCancelled()) {
            throw new BusinessRuleConflictException(
                    "TENANT_CANCELLED",
                    "No se puede reactivar un tenant cancelado.");
        }

        tenant.activate();
        tenant = tenantRepository.save(tenant);
        log.info("Tenant activado: {}", tenant.getId());

        auditService.recordSystemAudit(
                "Reactivación de Comunidad",
                "Comunidades",
                "Se restauró el acceso operativo a " + tenant.getName(),
                tenant.getId(),
                tenant.getName(),
                "SUCCESS"
        );

        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse cancelTenant(UUID id) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN, com.havita.modules.identity.domain.RoleType.PLATFORM_ADMIN));
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", id));

        if (tenant.isCancelled()) {
            throw new BusinessRuleConflictException(
                    "TENANT_ALREADY_CANCELLED",
                    "El tenant ya se encuentra cancelado.");
        }

        tenant.cancel();
        tenant = tenantRepository.save(tenant);
        log.info("Tenant cancelado: {}", tenant.getId());

        auditService.recordSystemAudit(
                "Cancelación de Comunidad",
                "Comunidades",
                "Se canceló la suscripción de " + tenant.getName(),
                tenant.getId(),
                tenant.getName(),
                "WARNING"
        );

        return toResponse(tenant);
    }

    private SaaSPlan resolvePlan(String planIdStr, String planName) {
        if (planIdStr != null && !planIdStr.isBlank()) {
            try {
                UUID parsed = UUID.fromString(planIdStr.trim());
                Optional<SaaSPlan> byId = saasPlanRepository.findById(parsed);
                if (byId.isPresent()) return byId.get();
            } catch (IllegalArgumentException ignored) {
            }
            Optional<SaaSPlan> byTier = saasPlanRepository.findByTierIgnoreCase(planIdStr.trim());
            if (byTier.isPresent()) return byTier.get();
        }
        if (planName != null && !planName.isBlank()) {
            List<SaaSPlan> all = saasPlanRepository.findAll();
            for (SaaSPlan p : all) {
                if (p.getName().equalsIgnoreCase(planName.trim()) || p.getTier().equalsIgnoreCase(planName.trim())) {
                    return p;
                }
            }
        }
        return saasPlanRepository.findAll().stream().findFirst().orElse(null);
    }

    private TenantResponse toResponse(Tenant tenant) {
        int dbPropertiesCount = (int) propertyRepository.countByTenantId(tenant.getId());
        int contractedCount = tenant.getContractedProperties() != null ? tenant.getContractedProperties() : 0;
        int effectivePropertiesCount = Math.max(dbPropertiesCount, contractedCount);

        int usersCount = (int) membershipRepository.countByTenantId(tenant.getId());

        String adminName = "Administrador Designado";
        String adminEmail = tenant.getContactEmail() != null ? tenant.getContactEmail() : "";
        String adminPhone = tenant.getPhone() != null ? tenant.getPhone() : "";

        List<Membership> adminMemberships = membershipRepository.findByTenantIdAndRole(tenant.getId(), RoleType.COMMUNITY_ADMIN);
        if (!adminMemberships.isEmpty() && adminMemberships.get(0).getUser() != null) {
            User adminUser = adminMemberships.get(0).getUser();
            if (adminUser.getFullName() != null && !adminUser.getFullName().isBlank()) {
                adminName = adminUser.getFullName();
            }
            if (adminUser.getEmail() != null && !adminUser.getEmail().isBlank()) {
                adminEmail = adminUser.getEmail();
            }
            if (adminUser.getPhone() != null && !adminUser.getPhone().isBlank()) {
                adminPhone = adminUser.getPhone();
            }
        }

        UUID resolvedPlanId = tenant.getPlanId();
        String resolvedPlanName = tenant.getPlanName() != null ? tenant.getPlanName() : "Esencial";
        BigDecimal pricePerUnitCop = new BigDecimal("3000");

        Optional<SaaSSubscription> subOpt = saasSubscriptionRepository.findByTenantId(tenant.getId());
        if (subOpt.isPresent() && subOpt.get().getPlan() != null) {
            SaaSPlan plan = subOpt.get().getPlan();
            resolvedPlanId = plan.getId();
            resolvedPlanName = plan.getName();
            if (subOpt.get().getGrossPricePerProperty() != null) {
                pricePerUnitCop = subOpt.get().getGrossPricePerProperty();
            } else if (plan.getGrossPricePerProperty() != null) {
                pricePerUnitCop = plan.getGrossPricePerProperty();
            }
        } else {
            SaaSPlan fallbackPlan = resolvePlan(resolvedPlanId != null ? resolvedPlanId.toString() : null, resolvedPlanName);
            if (fallbackPlan != null) {
                resolvedPlanId = fallbackPlan.getId();
                resolvedPlanName = fallbackPlan.getName();
                if (fallbackPlan.getGrossPricePerProperty() != null) {
                    pricePerUnitCop = fallbackPlan.getGrossPricePerProperty();
                }
            }
        }

        BigDecimal mrrCop = pricePerUnitCop.multiply(BigDecimal.valueOf(effectivePropertiesCount));

        return new TenantResponse(
                tenant.getId(),
                tenant.getOrganization() != null ? tenant.getOrganization().getId() : null,
                tenant.getOrganization() != null ? tenant.getOrganization().getName() : null,
                tenant.getName(),
                tenant.getSlug(),
                tenant.getSubdomain(),
                tenant.getNit(),
                tenant.getCity(),
                tenant.getAddress(),
                tenant.getPhone(),
                tenant.getContactEmail(),
                tenant.getStatus(),
                tenant.getStorageLimitMb(),
                tenant.getStorageUsedMb(),
                tenant.getLogoUrl(),
                tenant.getPrimaryColor(),
                tenant.getSecondaryColor(),
                resolvedPlanId,
                resolvedPlanName,
                tenant.getTrialEndsAt(),
                tenant.getSuspendedReason(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt(),
                effectivePropertiesCount,
                usersCount,
                adminName,
                adminEmail,
                adminPhone,
                mrrCop,
                pricePerUnitCop,
                tenant.getActiveFeaturesList()
        );
    }
}
