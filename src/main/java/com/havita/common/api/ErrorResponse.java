package com.havita.common.api;

import java.time.OffsetDateTime;
import java.util.List;

public record ErrorResponse(
        String type,
        String title,
        int status,
        String code,
        String message,
        String path,
        OffsetDateTime timestamp,
        String traceId,
        List<ValidationErrorItem> errors
) {
    public record ValidationErrorItem(String field, String message, Object rejectedValue) {}
}
