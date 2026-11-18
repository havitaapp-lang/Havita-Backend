package com.havita.modules.resident.api.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record AssignPropertiesRequest(
    @NotEmpty(message = "Debe proporcionar al menos una propiedad")
    List<UUID> propertyIds,
    String type,
    Boolean isMainContact
) {}
