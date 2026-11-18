package com.havita.modules.marketplace.api.dto;

import com.havita.modules.marketplace.domain.PriceType;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

public record UpdateListingRequest(
        @Size(max = 100) String category,
        @Size(max = 150) String title,
        @Size(max = 2000) String description,
        @PositiveOrZero(message = "El precio no puede ser negativo.") BigDecimal price,
        PriceType priceType,
        List<String> imageKeys,
        @NotBlank @Pattern(regexp = "^\\+?[0-9 -]{7,20}$", message = "El teléfono debe tener entre 7 y 20 caracteres: solo dígitos, espacios, guiones y + inicial.") String contactPhone
) {}
