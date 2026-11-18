package com.havita.modules.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveReportRequest(
        @NotBlank String action  // "RESOLVE" or "DISMISS"
) {}
