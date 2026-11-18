package com.havita.modules.access.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.access.api.dto.*;
import com.havita.modules.access.domain.*;
import com.havita.modules.access.infrastructure.VisitorSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitorSettingsService {

    private final VisitorSettingsRepository visitorSettingsRepository;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @Transactional(readOnly = true)
    public VisitorSettingsResponse getSettings(UUID tenantId) {
        Optional<VisitorSettings> opt = visitorSettingsRepository.findByTenantId(tenantId);
        if (opt.isEmpty()) {
            // Fallback: 24 horas (no hay configuración guardada)
            return new VisitorSettingsResponse(VisitorScheduleMode.HOURS_24, List.of());
        }
        return toResponse(opt.get());
    }

    @Transactional
    public VisitorSettingsResponse updateSettings(UUID tenantId, UpdateVisitorSettingsRequest request) {
        // Validaciones de negocio para modo CUSTOM
        if (request.mode() == VisitorScheduleMode.CUSTOM && request.daySchedules() != null) {
            for (VisitorDayScheduleRequest day : request.daySchedules()) {
                if (day.enabled()) {
                    if (day.startTime() == null || day.endTime() == null) {
                        throw new BusinessRuleConflictException(
                                "INVALID_SCHEDULE",
                                "El día " + day.dayOfWeek() + " está habilitado pero no tiene horario completo.");
                    }
                    LocalTime start = LocalTime.parse(day.startTime(), TIME_FMT);
                    LocalTime end = LocalTime.parse(day.endTime(), TIME_FMT);
                    if (!start.isBefore(end)) {
                        throw new BusinessRuleConflictException(
                                "INVALID_SCHEDULE_RANGE",
                                "La hora de inicio debe ser anterior a la hora de fin para el día " + day.dayOfWeek() + ".");
                    }
                }
            }
        }

        VisitorSettings settings = visitorSettingsRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    VisitorSettings s = VisitorSettings.builder()
                            .tenantId(tenantId)
                            .build();
                    return s;
                });

                settings.setMode(request.mode());

        if (request.mode() == VisitorScheduleMode.CUSTOM && request.daySchedules() != null) {
            java.util.List<java.time.DayOfWeek> requestDays = request.daySchedules().stream()
                    .map(com.havita.modules.access.api.dto.VisitorDayScheduleRequest::dayOfWeek)
                    .toList();
            settings.getDaySchedules().removeIf(d -> !requestDays.contains(d.getDayOfWeek()));

            for (com.havita.modules.access.api.dto.VisitorDayScheduleRequest dayReq : request.daySchedules()) {
                LocalTime startTime = (dayReq.enabled() && dayReq.startTime() != null)
                        ? LocalTime.parse(dayReq.startTime(), TIME_FMT) : null;
                LocalTime endTime = (dayReq.enabled() && dayReq.endTime() != null)
                        ? LocalTime.parse(dayReq.endTime(), TIME_FMT) : null;

                Optional<VisitorDaySchedule> existingOpt = settings.getDaySchedules().stream()
                        .filter(d -> d.getDayOfWeek() == dayReq.dayOfWeek())
                        .findFirst();

                if (existingOpt.isPresent()) {
                    VisitorDaySchedule existing = existingOpt.get();
                    existing.setEnabled(dayReq.enabled());
                    existing.setStartTime(startTime);
                    existing.setEndTime(endTime);
                } else {
                    VisitorDaySchedule day = VisitorDaySchedule.builder()
                            .visitorSettings(settings)
                            .dayOfWeek(dayReq.dayOfWeek())
                            .enabled(dayReq.enabled())
                            .startTime(startTime)
                            .endTime(endTime)
                            .build();
                    settings.getDaySchedules().add(day);
                }
            }
        } else {
            settings.getDaySchedules().clear();
        }

        VisitorSettings saved = visitorSettingsRepository.save(settings);
        log.info("Configuración de horarios de visitas actualizada para tenant {} → modo {}", tenantId, request.mode());
        return toResponse(saved);
    }

    // Método público para ser usado por VisitorService durante la validación
    @Transactional(readOnly = true)
    public Optional<VisitorSettings> findRawSettings(UUID tenantId) {
        return visitorSettingsRepository.findByTenantId(tenantId);
    }

    private VisitorSettingsResponse toResponse(VisitorSettings settings) {
        List<VisitorDayScheduleResponse> days = settings.getDaySchedules().stream()
                .map(d -> new VisitorDayScheduleResponse(
                        d.getDayOfWeek(),
                        d.isEnabled(),
                        d.getStartTime() != null ? d.getStartTime().format(TIME_FMT) : null,
                        d.getEndTime() != null ? d.getEndTime().format(TIME_FMT) : null
                ))
                .toList();
        return new VisitorSettingsResponse(settings.getMode(), days);
    }
}
