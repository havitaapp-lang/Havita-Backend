package com.havita.modules.access.api.dto;

import java.time.DayOfWeek;

public record VisitorDayScheduleResponse(
        DayOfWeek dayOfWeek,
        boolean enabled,
        String startTime,  // "HH:mm" o null
        String endTime     // "HH:mm" o null
) {}
