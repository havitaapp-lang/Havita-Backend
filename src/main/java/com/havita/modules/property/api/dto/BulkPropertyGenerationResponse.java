package com.havita.modules.property.api.dto;

import java.util.List;

public record BulkPropertyGenerationResponse(
        int structuresCreated,
        int propertiesCreated,
        List<String> errors
) {}

