package com.havita.modules.marketplace.domain;

import com.havita.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "marketplace_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketplaceConfig extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30)
    private MarketplaceMode mode = MarketplaceMode.AUTO_APPROVE;

    @Builder.Default
    @Column(name = "listing_duration_days", nullable = false)
    private Integer listingDurationDays = 30;
}
