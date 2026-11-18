package com.havita.modules.access.api.dto;

import com.havita.modules.access.domain.VisitorScheduleMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateVisitorSettingsRequest(
        @NotNull VisitorScheduleMode mode,
        @Valid List<VisitorDayScheduleRequest> daySchedules
) {}
