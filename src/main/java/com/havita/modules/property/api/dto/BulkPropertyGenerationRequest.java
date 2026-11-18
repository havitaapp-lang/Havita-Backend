package com.havita.modules.property.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record BulkPropertyGenerationRequest(
        @NotNull(message = "La configuracion de la estructura principal es requerida")
        LevelConfig structure,

        LevelConfig level,

        @NotNull(message = "La configuracion de las propiedades es requerida")
        LevelConfig property,

        @NotNull(message = "El tipo de estructura no puede estar vacio (ej. Torre, Manzana)")
        String structureType,

        @NotNull(message = "El tipo de propiedad no puede estar vacio (ej. Apartamento, Casa)")
        String propertyType
) {
    public record LevelConfig(
            @NotEmpty(message = "El tipo de generacion no puede estar vacio (SEQUENTIAL o CUSTOM)")
            String generationType,
            
            Integer count,
            Integer startFrom,
            List<String> customValues,
            
            @NotNull(message = "El prefijo/plantilla no puede ser nulo")
            String prefix
    ) {}
}
