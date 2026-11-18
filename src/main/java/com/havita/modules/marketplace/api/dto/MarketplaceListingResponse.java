package com.havita.modules.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record MarketplaceListingResponse(
        UUID id,
        String type,
        String category,
        String title,
        String description,
        BigDecimal price,
        String priceType,
        String status,
        List<String> imageUrls,
        /** Claves de las imagenes (para editar la publicacion). No conceden acceso: la lectura exige autorizacion. */
        List<String> imageKeys,
        String contactPhone,
        String rejectionReason,
        boolean hiddenByAdmin,
        String hiddenReason,
        OffsetDateTime hiddenAt,
        OffsetDateTime publishedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        AuthorInfo author
) {
    public record AuthorInfo(
            UUID userId,
            String fullName,
            String propertyUnit
    ) {}
}
