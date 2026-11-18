package com.havita.modules.access.api.dto;

import com.havita.modules.access.domain.VisitorScheduleMode;

import java.util.List;

public record VisitorSettingsResponse(
        VisitorScheduleMode mode,
        List<VisitorDayScheduleResponse> daySchedules
) {}
