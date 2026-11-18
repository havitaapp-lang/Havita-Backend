package com.havita.modules.featureflag.domain;

import com.havita.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "feature_flags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeatureFlag extends BaseEntity {

    @Column(name = "flag_key", nullable = false, unique = true, length = 100)
    private String key;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "category", nullable = false, length = 50)
    private String category = "Operación"; // Seguridad, Operación, Finanzas, Comunidad, Avanzado

    @Column(name = "enabled_by_plan", nullable = false, length = 255)
    private String enabledByPlan; // ej: "STARTER,PROFESSIONAL,ENTERPRISE"

    @Builder.Default
    @Column(name = "is_global_enabled", nullable = false)
    private Boolean isGlobalEnabled = true;

    @Builder.Default
    @Column(name = "is_beta", nullable = false)
    private Boolean isBeta = false;

    public boolean isAllowedForTier(String tier) {
        if (tier == null || enabledByPlan == null) {
            return false;
        }
        return enabledByPlan.toUpperCase().contains(tier.toUpperCase());
    }
}
