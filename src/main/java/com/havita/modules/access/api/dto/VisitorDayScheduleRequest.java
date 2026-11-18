package com.havita.modules.access.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;

public record VisitorDayScheduleRequest(
        @NotNull DayOfWeek dayOfWeek,
        boolean enabled,
        String startTime,  // "HH:mm" 24h
        String endTime     // "HH:mm" 24h
) {}
