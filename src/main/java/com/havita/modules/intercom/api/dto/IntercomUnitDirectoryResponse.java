package com.havita.modules.intercom.api.dto;

import java.util.List;
import java.util.UUID;

public record IntercomUnitDirectoryResponse(
        UUID propertyId,
        String number,
        String name,
        String path,
        String type,
        String tower,
        String level,
        long residentCount,
        List<String> residentNames
) {}
