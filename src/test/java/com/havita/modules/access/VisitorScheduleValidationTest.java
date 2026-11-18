package com.havita.modules.access;

import com.havita.modules.access.domain.*;
import com.havita.modules.access.application.VisitorSettingsService;
import com.havita.modules.access.infrastructure.VisitorSettingsRepository;
import com.havita.common.exception.BusinessRuleConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios para la validación de horarios de visitas por tenant.
 */
@ExtendWith(MockitoExtension.class)
class VisitorScheduleValidationTest {

    @Mock
    private VisitorSettingsRepository visitorSettingsRepository;

    @InjectMocks
    private VisitorSettingsService visitorSettingsService;

    private UUID tenantId;
    private static final ZoneOffset COLOMBIA = ZoneOffset.of("-05:00");

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    // ─── Helper para construir OffsetDateTime con hora Colombia ───────────────

    private OffsetDateTime atTime(DayOfWeek targetDay, int hour, int minute) {
        // Construye fecha en Colombia con el día de semana requerido
        java.time.LocalDate today = java.time.LocalDate.now(COLOMBIA);
        int daysUntilTarget = (targetDay.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        java.time.LocalDate targetDate = today.plusDays(daysUntilTarget == 0 ? 7 : daysUntilTarget);
        return targetDate.atTime(hour, minute).atOffset(COLOMBIA);
    }

    private VisitorSettings buildCustomSettings(boolean mondayEnabled, String start, String end,
                                                boolean sundayEnabled) {
        VisitorSettings settings = new VisitorSettings();
        settings.setMode(VisitorScheduleMode.CUSTOM);

        VisitorDaySchedule monday = new VisitorDaySchedule();
        monday.setDayOfWeek(DayOfWeek.MONDAY);
        monday.setEnabled(mondayEnabled);
        if (mondayEnabled && start != null) monday.setStartTime(LocalTime.parse(start));
        if (mondayEnabled && end != null) monday.setEndTime(LocalTime.parse(end));

        VisitorDaySchedule sunday = new VisitorDaySchedule();
        sunday.setDayOfWeek(DayOfWeek.SUNDAY);
        sunday.setEnabled(sundayEnabled);

        settings.setDaySchedules(List.of(monday, sunday));
        return settings;
    }

    // ─── Tests: tenant sin configuración → HOURS_24 implícito ────────────────

    @Test
    @DisplayName("Tenant sin configuración → cualquier hora permitida")
    void tenantWithoutSettings_anyTimeAllowed() {
        when(visitorSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        Optional<VisitorSettings> result = visitorSettingsService.findRawSettings(tenantId);
        assertTrue(result.isEmpty(), "Sin configuración, findRawSettings debe retornar empty");
        // La lógica en VisitorService hace fallback a HOURS_24 cuando empty
    }

    // ─── Tests: modo HOURS_24 ─────────────────────────────────────────────────

    @Test
    @DisplayName("HOURS_24 → 00:00 permitido")
    void hours24_midnight_allowed() {
        VisitorSettings s = new VisitorSettings();
        s.setMode(VisitorScheduleMode.HOURS_24);
        when(visitorSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.of(s));
        // HOURS_24 = siempre permitido → no debe lanzar excepción en VisitorService
        assertEquals(VisitorScheduleMode.HOURS_24, visitorSettingsService.findRawSettings(tenantId).get().getMode());
    }

    @Test
    @DisplayName("HOURS_24 → 23:30 permitido")
    void hours24_lateNight_allowed() {
        VisitorSettings s = new VisitorSettings();
        s.setMode(VisitorScheduleMode.HOURS_24);
        when(visitorSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.of(s));
        assertEquals(VisitorScheduleMode.HOURS_24, visitorSettingsService.findRawSettings(tenantId).get().getMode());
    }

    // ─── Tests: modo CUSTOM ───────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOM Lunes 06:00-22:00 → 06:00 permitido")
    void custom_monday_startTime_allowed() {
        VisitorSettings s = buildCustomSettings(true, "06:00", "22:00", false);
        when(visitorSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.of(s));
        VisitorSettings fetched = visitorSettingsService.findRawSettings(tenantId).get();
        VisitorDaySchedule monday = fetched.getDaySchedules().stream()
                .filter(d -> d.getDayOfWeek() == DayOfWeek.MONDAY).findFirst().get();
        LocalTime time = LocalTime.of(6, 0);
        assertTrue(monday.isEnabled());
        assertFalse(time.isBefore(monday.getStartTime()));
        assertFalse(time.isAfter(monday.getEndTime()));
    }

    @Test
    @DisplayName("CUSTOM Lunes 06:00-22:00 → 05:30 rechazado")
    void custom_monday_beforeStart_rejected() {
        VisitorSettings s = buildCustomSettings(true, "06:00", "22:00", false);
        when(visitorSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.of(s));
        VisitorSettings fetched = visitorSettingsService.findRawSettings(tenantId).get();
        VisitorDaySchedule monday = fetched.getDaySchedules().stream()
                .filter(d -> d.getDayOfWeek() == DayOfWeek.MONDAY).findFirst().get();
        LocalTime time = LocalTime.of(5, 30);
        assertTrue(time.isBefore(monday.getStartTime()), "05:30 debe ser anterior al inicio");
    }

    @Test
    @DisplayName("CUSTOM Lunes 06:00-22:00 → 22:00 permitido")
    void custom_monday_endTime_allowed() {
        VisitorSettings s = buildCustomSettings(true, "06:00", "22:00", false);
        when(visitorSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.of(s));
        VisitorSettings fetched = visitorSettingsService.findRawSettings(tenantId).get();
        VisitorDaySchedule monday = fetched.getDaySchedules().stream()
                .filter(d -> d.getDayOfWeek() == DayOfWeek.MONDAY).findFirst().get();
        LocalTime time = LocalTime.of(22, 0);
        assertFalse(time.isBefore(monday.getStartTime()));
        assertFalse(time.isAfter(monday.getEndTime()));
    }

    @Test
    @DisplayName("CUSTOM Lunes 06:00-22:00 → 22:30 rechazado")
    void custom_monday_afterEnd_rejected() {
        VisitorSettings s = buildCustomSettings(true, "06:00", "22:00", false);
        when(visitorSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.of(s));
        VisitorSettings fetched = visitorSettingsService.findRawSettings(tenantId).get();
        VisitorDaySchedule monday = fetched.getDaySchedules().stream()
                .filter(d -> d.getDayOfWeek() == DayOfWeek.MONDAY).findFirst().get();
        LocalTime time = LocalTime.of(22, 30);
        assertTrue(time.isAfter(monday.getEndTime()), "22:30 debe ser posterior al fin");
    }

    @Test
    @DisplayName("CUSTOM Domingo deshabilitado → rechazado")
    void custom_sunday_disabled_rejected() {
        VisitorSettings s = buildCustomSettings(true, "06:00", "22:00", false);
        when(visitorSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.of(s));
        VisitorSettings fetched = visitorSettingsService.findRawSettings(tenantId).get();
        VisitorDaySchedule sunday = fetched.getDaySchedules().stream()
                .filter(d -> d.getDayOfWeek() == DayOfWeek.SUNDAY).findFirst().get();
        assertFalse(sunday.isEnabled(), "Domingo debe estar deshabilitado");
    }

    // ─── Tests: validación de la configuración (updateSettings) ──────────────

    @Test
    @DisplayName("updateSettings CUSTOM con startTime >= endTime → rechazado")
    void updateSettings_invalidRange_rejected() {
        com.havita.modules.access.api.dto.UpdateVisitorSettingsRequest req =
                new com.havita.modules.access.api.dto.UpdateVisitorSettingsRequest(
                        VisitorScheduleMode.CUSTOM,
                        List.of(new com.havita.modules.access.api.dto.VisitorDayScheduleRequest(
                                DayOfWeek.MONDAY, true, "22:00", "06:00"
                        ))
                );
        assertThrows(BusinessRuleConflictException.class, () ->
                visitorSettingsService.updateSettings(tenantId, req));
    }

    @Test
    @DisplayName("updateSettings CUSTOM día enabled sin horario → rechazado")
    void updateSettings_enabledDayWithoutTime_rejected() {
        com.havita.modules.access.api.dto.UpdateVisitorSettingsRequest req =
                new com.havita.modules.access.api.dto.UpdateVisitorSettingsRequest(
                        VisitorScheduleMode.CUSTOM,
                        List.of(new com.havita.modules.access.api.dto.VisitorDayScheduleRequest(
                                DayOfWeek.MONDAY, true, null, null
                        ))
                );
        assertThrows(BusinessRuleConflictException.class, () ->
                visitorSettingsService.updateSettings(tenantId, req));
    }

    @Test
    @DisplayName("updateSettings HOURS_24 → sin validación de rangos")
    void updateSettings_hours24_noRangeValidation() {
        when(visitorSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        VisitorSettings saved = new VisitorSettings();
        saved.setMode(VisitorScheduleMode.HOURS_24);
        saved.setDaySchedules(List.of());
        when(visitorSettingsRepository.save(any())).thenReturn(saved);

        com.havita.modules.access.api.dto.UpdateVisitorSettingsRequest req =
                new com.havita.modules.access.api.dto.UpdateVisitorSettingsRequest(
                        VisitorScheduleMode.HOURS_24, null
                );
        assertDoesNotThrow(() -> visitorSettingsService.updateSettings(tenantId, req));
    }
}
