package com.havita.modules.marketplace.api.dto;

import com.havita.modules.marketplace.domain.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReportListingRequest(
        @NotNull ReportReason reason,
        @Size(max = 500) String description
) {}
