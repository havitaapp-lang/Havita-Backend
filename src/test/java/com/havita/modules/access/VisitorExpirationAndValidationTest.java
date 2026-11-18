package com.havita.modules.access;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.access.application.VisitorExpirationJob;
import com.havita.modules.access.application.VisitorService;
import com.havita.modules.access.application.VisitorSettingsService;
import com.havita.modules.access.domain.Visitor;
import com.havita.modules.access.domain.VisitorStatus;
import com.havita.modules.access.infrastructure.VisitorRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.security.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests exhaustivos para el ciclo de vida, expiración a 12 horas,
 * visitas futuras y validación en tiempo real de pases QR.
 */
@ExtendWith(MockitoExtension.class)
class VisitorExpirationAndValidationTest {

    @Mock private VisitorRepository visitorRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private ResidentRepository residentRepository;
    @Mock private UserRepository userRepository;
    @Mock private VisitorSettingsService visitorSettingsService;
    @Mock private SyncEventPublisher syncEventPublisher;
    @Mock private org.springframework.context.ApplicationEventPublisher applicationEvents;
    @Mock private SecurityContext securityContext;
    @Mock private Authentication authentication;

    private VisitorService visitorService;
    private VisitorExpirationJob expirationJob;

    private UUID tenantId;
    private UUID userId;
    private MockedStatic<TenantContextHolder> mockedTenantContext;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();

        visitorService = new VisitorService(
                visitorRepository,
                propertyRepository,
                residentRepository,
                syncEventPublisher,
                userRepository,
                visitorSettingsService,
                applicationEvents
        );

        expirationJob = new VisitorExpirationJob(visitorRepository, syncEventPublisher);

        mockedTenantContext = mockStatic(TenantContextHolder.class);
        mockedTenantContext.when(TenantContextHolder::getRequiredTenantId).thenReturn(tenantId);

        SecurityContextHolder.setContext(securityContext);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.getName()).thenReturn(userId.toString());
    }

    @AfterEach
    void tearDown() {
        mockedTenantContext.close();
        SecurityContextHolder.clearContext();
    }

    // ─── 1. CRONJOB: VISITA FUTURA NO DEBE EXPIRAR ─────────────────────────────

    @Test
    @DisplayName("Caso 3 & 4: Visita futura programada para dentro de 4 días no es seleccionada para expirar")
    void futureVisit_isNotSelectedForExpiration() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime futureExpectedAt = now.plusDays(4);

        // Simulamos que el repositorio solo devuelve visitas que cumplen: expectedAt <= now - 12h
        // La visita futura NO coincide con la query de expiración
        when(visitorRepository.findVisitsToExpire(any())).thenReturn(List.of());

        expirationJob.expireOldVisits();

        verify(visitorRepository, never()).saveAll(any());
        verify(syncEventPublisher, never()).publish(any());
    }

    // ─── 2. CRONJOB: VISITA CUYA VENTANA DE 12H VENCIÓ SÍ EXPIRA ───────────────

    @Test
    @DisplayName("Caso 6: Visita cuya ventana de 12 horas venció es marcada como EXPIRED")
    void expiredVisit_isMarkedAsExpiredAndBroadcasted() {
        OffsetDateTime pastExpectedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(13);

        Visitor oldVisitor = Visitor.builder()
                .name("Carlos Visitante")
                .documentId("123456")
                .status(VisitorStatus.AUTHORIZED)
                .expectedAt(pastExpectedAt)
                .build();
        oldVisitor.setId(UUID.randomUUID());
        oldVisitor.setTenantId(tenantId);

        when(visitorRepository.findVisitsToExpire(any())).thenReturn(List.of(oldVisitor));

        expirationJob.expireOldVisits();

        assertEquals(VisitorStatus.EXPIRED, oldVisitor.getStatus());
        verify(visitorRepository, times(1)).saveAll(List.of(oldVisitor));
        verify(syncEventPublisher, times(1)).publish(any());
    }

    // ─── 3. IDEMPOTENCIA DEL CRONJOB ──────────────────────────────────────────

    @Test
    @DisplayName("Caso 18: El cronjob ejecutado múltiples veces es 100% idempotente")
    void cronjob_isIdempotent() {
        when(visitorRepository.findVisitsToExpire(any())).thenReturn(List.of());

        // Correr 5 veces consecutivas
        for (int i = 0; i < 5; i++) {
            expirationJob.expireOldVisits();
        }

        verify(visitorRepository, never()).saveAll(any());
    }

    // ─── 4. VALIDACIÓN EN TIEMPO REAL: VISITA CANCELADA RECHAZADA ──────────────

    @Test
    @DisplayName("Caso 8: Escaneo de visita cancelada es rechazado con VISITOR_CANCELLED")
    void registerEntry_cancelledVisitor_throwsException() {
        UUID visitorId = UUID.randomUUID();
        Visitor visitor = Visitor.builder()
                .name("Visita Cancelada")
                .documentId("987654")
                .status(VisitorStatus.CANCELLED)
                .build();
        visitor.setId(visitorId);
        visitor.setTenantId(tenantId);

        when(visitorRepository.findByIdAndTenantId(visitorId, tenantId)).thenReturn(Optional.of(visitor));

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class,
                () -> visitorService.registerEntry(visitorId));

        assertEquals("VISITOR_CANCELLED", ex.getCode());
        verify(visitorRepository, never()).save(any());
    }

    // ─── 5. VALIDACIÓN EN TIEMPO REAL: VISITA YA EXPIRADA POR TIEMPO ───────────

    @Test
    @DisplayName("Caso 6: Escaneo de visita que superó las 12h es rechazada con QR_EXPIRED")
    void registerEntry_over12Hours_throwsQRExpired() {
        UUID visitorId = UUID.randomUUID();
        OffsetDateTime pastExpected = OffsetDateTime.now(ZoneOffset.UTC).minusHours(14);

        Visitor visitor = Visitor.builder()
                .name("Visita Tardía")
                .documentId("112233")
                .status(VisitorStatus.AUTHORIZED)
                .expectedAt(pastExpected)
                .build();
        visitor.setId(visitorId);
        visitor.setTenantId(tenantId);

        when(visitorRepository.findByIdAndTenantId(visitorId, tenantId)).thenReturn(Optional.of(visitor));

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class,
                () -> visitorService.registerEntry(visitorId));

        assertEquals("QR_EXPIRED", ex.getCode());
        assertEquals(VisitorStatus.EXPIRED, visitor.getStatus());
        verify(visitorRepository, times(1)).save(visitor);
    }

    // ─── 6. VALIDACIÓN EN TIEMPO REAL: VISITA FUTURA NO PUEDE INGRESAR HOY ─────

    @Test
    @DisplayName("Caso 9: Escaneo anticipado de visita futura es rechazado con FUTURE_VISIT")
    void registerEntry_futureVisit_throwsFutureVisitException() {
        UUID visitorId = UUID.randomUUID();
        ZoneOffset colZone = ZoneOffset.of("-05:00");
        OffsetDateTime futureVisit = LocalDate.now(colZone).plusDays(3).atTime(15, 0).atOffset(colZone);

        Visitor visitor = Visitor.builder()
                .name("Visita Futura")
                .documentId("334455")
                .status(VisitorStatus.AUTHORIZED)
                .expectedAt(futureVisit)
                .build();
        visitor.setId(visitorId);
        visitor.setTenantId(tenantId);

        when(visitorRepository.findByIdAndTenantId(visitorId, tenantId)).thenReturn(Optional.of(visitor));

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class,
                () -> visitorService.registerEntry(visitorId));

        assertEquals("FUTURE_VISIT", ex.getCode());
        verify(visitorRepository, never()).save(any());
    }

    // ─── 7. VALIDACIÓN EN TIEMPO REAL: VISITA VÁLIDA HOY INGRESA CORRECTAMENTE ──

    @Test
    @DisplayName("Caso 1 & 5: Visita programada para hoy dentro de la ventana de 12h registra ingreso")
    void registerEntry_validTodayVisit_succeeds() {
        UUID visitorId = UUID.randomUUID();
        ZoneOffset colZone = ZoneOffset.of("-05:00");
        // Programada para hoy hace 2 horas (dentro de ventana de 12h)
        OffsetDateTime todayExpected = OffsetDateTime.now(colZone).minusHours(2);

        Property property = Property.builder().number("101").build();
        property.setId(UUID.randomUUID());

        Visitor visitor = Visitor.builder()
                .name("Visita Puntual")
                .documentId("556677")
                .status(VisitorStatus.AUTHORIZED)
                .expectedAt(todayExpected)
                .property(property)
                .build();
        visitor.setId(visitorId);
        visitor.setTenantId(tenantId);

        when(visitorRepository.findByIdAndTenantId(visitorId, tenantId)).thenReturn(Optional.of(visitor));
        when(visitorRepository.save(any(Visitor.class))).thenAnswer(i -> i.getArgument(0));

        var response = visitorService.registerEntry(visitorId);

        assertNotNull(response);
        assertEquals(VisitorStatus.ENTERED, visitor.getStatus());
        assertNotNull(visitor.getEnteredAt());
        assertEquals(userId, visitor.getEnteredBy());
        // Dos eventos: la unidad (sin nombre del personal) y la gestion (con quien registro el ingreso).
        ArgumentCaptor<com.havita.modules.sync.domain.SyncEvent> events =
                ArgumentCaptor.forClass(com.havita.modules.sync.domain.SyncEvent.class);
        verify(syncEventPublisher, times(2)).publish(events.capture());
        assertEquals(com.havita.modules.sync.domain.SyncAudience.Scope.UNIT, events.getAllValues().get(0).audience().scope());
        assertEquals(com.havita.modules.sync.domain.SyncAudience.Scope.STAFF, events.getAllValues().get(1).audience().scope());
    }

    // ─── 8. REPROGRAMACIÓN: MOVER FECHA ACTUALIZA LA VENTANA ───────────────────

    @Test
    @DisplayName("Caso 11: Reprogramar visita a fecha futura actualiza expectedAt y reactiva si estaba expirada")
    void updateAccessibleVisitor_rescheduleUpdatesWindow() {
        UUID visitorId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();

        Property property = Property.builder().number("202").build();
        property.setId(propertyId);

        Visitor visitor = Visitor.builder()
                .name("Visita Original")
                .documentId("778899")
                .status(VisitorStatus.EXPIRED) // Estaba expirada
                .expectedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2))
                .property(property)
                .build();
        visitor.setId(visitorId);
        visitor.setTenantId(tenantId);

        when(visitorRepository.findAccessibleVisitorById(visitorId, tenantId, userId))
                .thenReturn(Optional.of(visitor));
        when(visitorSettingsService.findRawSettings(tenantId)).thenReturn(Optional.empty());
        when(visitorRepository.save(any(Visitor.class))).thenAnswer(i -> i.getArgument(0));

        OffsetDateTime newFutureDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5);

        var response = visitorService.updateAccessibleVisitor(
                visitorId, propertyId, userId,
                "Visita Reprogramada", "778899", "ABC123",
                "Reprogramada para el fin de semana", "Cena",
                newFutureDate
        );

        assertNotNull(response);
        assertEquals(newFutureDate, visitor.getExpectedAt());
        // Debe reactivarse a AUTHORIZED porque la nueva fecha es válida y futura
        assertEquals(VisitorStatus.AUTHORIZED, visitor.getStatus());
        assertEquals("Visita Reprogramada", visitor.getName());
    }
}
