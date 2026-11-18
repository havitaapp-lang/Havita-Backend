package com.havita.modules.realtime.api.dto;

import com.havita.modules.realtime.domain.AlertType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PanicAlertRequest(
        @NotNull AlertType alertType,
        @Size(max = 500) String notes
) {}
