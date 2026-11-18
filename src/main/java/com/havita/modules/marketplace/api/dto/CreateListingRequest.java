package com.havita.modules.marketplace.api.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.havita.modules.marketplace.domain.ListingType;
import com.havita.modules.marketplace.domain.PriceType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public record CreateListingRequest(
        @NotNull ListingType type,
        @NotBlank @Size(max = 100) String category,
        @NotBlank @Size(max = 150) String title,
        @NotBlank @Size(max = 2000) String description,
        @PositiveOrZero(message = "El precio no puede ser negativo.") BigDecimal price,
        @NotNull PriceType priceType,
        List<String> imageKeys,
        @NotBlank @Pattern(regexp = "^\\+?[0-9 -]{7,20}$", message = "El teléfono debe tener entre 7 y 20 caracteres: solo dígitos, espacios, guiones y + inicial.") String contactPhone,
        @NotNull Boolean termsAccepted
) {}

