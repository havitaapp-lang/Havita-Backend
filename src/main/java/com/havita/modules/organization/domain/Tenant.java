package com.havita.modules.organization.domain;

import com.havita.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant extends BaseEntity {

    public static final String DEFAULT_ACTIVE_FEATURES = "visitors,packages,pqrs,maintenance,reservations,staff,documents,pets,vehicles,surveys,marketplace";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    @Column(name = "subdomain", nullable = false, unique = true, length = 100)
    private String subdomain;

    @Column(name = "nit", length = 50)
    private String nit;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TenantStatus status = TenantStatus.ACTIVE;

    @Builder.Default
    @Column(name = "storage_limit_mb", nullable = false)
    private Integer storageLimitMb = 5120;

    @Builder.Default
    @Column(name = "storage_used_mb", nullable = false)
    private Integer storageUsedMb = 0;

    @Builder.Default
    @Column(name = "contracted_properties")
    private Integer contractedProperties = 0;

    @Builder.Default
    @Column(name = "active_features", length = 1000)
    private String activeFeatures = DEFAULT_ACTIVE_FEATURES;

    // Branding
    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Builder.Default
    @Column(name = "primary_color", length = 7)
    private String primaryColor = "#3B82F6";

    @Builder.Default
    @Column(name = "secondary_color", length = 7)
    private String secondaryColor = "#1E40AF";

    // Plan
    @Column(name = "plan_id")
    private UUID planId;

    @Builder.Default
    @Column(name = "plan_name", length = 100)
    private String planName = "Esencial";

    // Lifecycle
    @Column(name = "trial_ends_at")
    private OffsetDateTime trialEndsAt;

    @Column(name = "suspended_reason", length = 255)
    private String suspendedReason;

    // Business methods
    public boolean isSuspended() {
        return this.status == TenantStatus.SUSPENDED;
    }

    public boolean isCancelled() {
        return this.status == TenantStatus.CANCELLED;
    }

    public boolean isOperational() {
        return this.status == TenantStatus.ACTIVE || this.status == TenantStatus.TRIAL;
    }

    public void suspend(String reason) {
        this.status = TenantStatus.SUSPENDED;
        this.suspendedReason = reason;
    }

    public void activate() {
        this.status = TenantStatus.ACTIVE;
        this.suspendedReason = null;
    }

    public void cancel() {
        this.status = TenantStatus.CANCELLED;
    }

    public List<String> getActiveFeaturesList() {
        String raw = this.activeFeatures != null ? this.activeFeatures : DEFAULT_ACTIVE_FEATURES;
        if (raw.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public void setActiveFeaturesList(List<String> features) {
        if (features == null || features.isEmpty()) {
            this.activeFeatures = "";
        } else {
            this.activeFeatures = features.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
        }
    }
}
