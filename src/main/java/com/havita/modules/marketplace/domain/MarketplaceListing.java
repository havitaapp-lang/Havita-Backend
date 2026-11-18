package com.havita.modules.marketplace.domain;

import com.havita.common.domain.BaseEntity;
import com.havita.modules.identity.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "marketplace_listings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketplaceListing extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;

    @Column(name = "property_unit", length = 200)
    private String propertyUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ListingType type;

    @Column(name = "category", nullable = false, length = 100)
    private String category;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 20)
    private PriceType priceType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ListingStatus status = ListingStatus.PENDING_APPROVAL;

    @Column(name = "image_keys", columnDefinition = "TEXT")
    private String imageKeys;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "terms_accepted", nullable = false)
    @Builder.Default
    private boolean termsAccepted = true;

    @Column(name = "hidden_by_admin", nullable = false)
    @Builder.Default
    private boolean hiddenByAdmin = false;

    @Column(name = "hidden_reason", columnDefinition = "TEXT")
    private String hiddenReason;

    @Column(name = "hidden_at")
    private OffsetDateTime hiddenAt;

    public List<String> getImageKeysList() {
        if (imageKeys == null || imageKeys.isBlank()) return List.of();
        return Arrays.stream(imageKeys.split(",")).filter(s -> !s.isBlank()).toList();
    }

    public void setImageKeysList(List<String> keys) {
        this.imageKeys = (keys == null || keys.isEmpty()) ? null : String.join(",", keys);
    }
}
