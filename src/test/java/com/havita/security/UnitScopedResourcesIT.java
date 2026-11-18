package com.havita.security;

import com.havita.modules.accounting.infrastructure.PropertyInvoiceRepository;
import com.havita.modules.identity.domain.User;
import com.havita.modules.realtime.infrastructure.RealtimeNotificationRepository;
import com.havita.modules.reservation.domain.ReservationStatus;
import com.havita.modules.reservation.infrastructure.ReservationRepository;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import com.havita.support.UnitDataFixture;
import com.havita.support.UnitDataFixture.UnitData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-3: recursos individuales, listados y escrituras de paqueteria, facturacion, reservas y
 * notificaciones. Cada modulo valida, ademas de la unidad, su propia relacion con el recurso.
 * Un recurso ajeno se responde como inexistente (404) para no revelar que existe.
 */
class UnitScopedResourcesIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UnitDataFixture unitData;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private RealtimeNotificationRepository notificationRepository;
    @Autowired private PropertyInvoiceRepository invoiceRepository;
    @Autowired private ResidentRepository residentRepository;

    private Side a;
    private Side b;
    private UnitData da;
    private UnitData db;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        da = unitData.seed(a);
        db = unitData.seed(b);
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                .header("Authorization", fixture.bearer(user))
                .header("X-Tenant-ID", side.tenantId().toString()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Nested
    @DisplayName("Paqueteria")
    class Packages {

        @Test
        @DisplayName("RESIDENT lee un paquete de su unidad pero no el del vecino ni uno de otra comunidad (404)")
        void packageById() throws Exception {
            as(a.owner(), a, get("/api/v1/access/packages/{id}", da.ownPackage().getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.recipientName").value("Destinatario propio"));
            as(a.owner(), a, get("/api/v1/access/packages/{id}", da.neighbourPackage().getId())).andExpect(status().isNotFound());
            as(a.owner(), a, get("/api/v1/access/packages/{id}", db.ownPackage().getId())).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("SECURITY sigue leyendo los paquetes de cualquier unidad de su comunidad")
        void guardKeepsAccess() throws Exception {
            as(a.guard(), a, get("/api/v1/access/packages/{id}", da.neighbourPackage().getId())).andExpect(status().isOk());
            as(a.guard(), a, get("/api/v1/access/packages/by-property/{id}", a.neighbourHome().getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("RESIDENT confirma la recogida de SU paquete; no la de otra unidad u otra comunidad (404) ni dos veces (409)")
        void residentConfirmsOwnPickup() throws Exception {
            // Ajenos: no revela que existen y no los cambia.
            as(a.owner(), a, post("/api/v1/access/packages/{id}/confirm-pickup", da.neighbourPackage().getId()))
                    .andExpect(status().isNotFound());
            as(a.owner(), a, post("/api/v1/access/packages/{id}/confirm-pickup", db.ownPackage().getId()))
                    .andExpect(status().isNotFound());
            as(a.guard(), a, get("/api/v1/access/packages/{id}", da.neighbourPackage().getId()))
                    .andExpect(jsonPath("$.data.status").value("RECEIVED"));
            // Porteria no usa el endpoint del residente.
            as(a.guard(), a, post("/api/v1/access/packages/{id}/confirm-pickup", da.ownPackage().getId()))
                    .andExpect(status().isForbidden());

            // Propio: queda entregado, con constancia de que lo confirmo el residente; una segunda vez, 409.
            as(a.owner(), a, post("/api/v1/access/packages/{id}/confirm-pickup", da.ownPackage().getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                    .andExpect(jsonPath("$.data.deliveredTo").value(a.owner().getFullName() + " (confirmado por el residente)"));
            as(a.owner(), a, post("/api/v1/access/packages/{id}/confirm-pickup", da.ownPackage().getId()))
                    .andExpect(status().isConflict());
            // El residente sigue sin poder usar el endpoint de porteria.
            as(a.owner(), a, post("/api/v1/access/packages/{id}/deliver", da.ownPackage().getId())).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("El listado por unidad del residente solo contiene paquetes de su unidad")
        void ownListOnlyHasOwnPackages() throws Exception {
            as(a.owner(), a, get("/api/v1/access/packages/by-property/{id}", a.home().getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].recipientName").value("Destinatario propio"));
        }
    }

    @Nested
    @DisplayName("Facturacion y saldo")
    class Accounting {

        @Test
        @DisplayName("GET /invoices: el residente solo ve las cuentas de cobro de sus unidades; el admin ve las de su comunidad")
        void invoiceListIsScoped() throws Exception {
            as(a.owner(), a, get("/api/v1/accounting/invoices"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(da.ownInvoice().getId().toString()));
            as(a.admin(), a, get("/api/v1/accounting/invoices"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("GET /invoices: sin unidades activas el residente recibe una pagina vacia")
        void invoiceListWithoutUnitsIsEmpty() throws Exception {
            var rel = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
            rel.setStatus(ResidentStatus.INACTIVE);
            residentRepository.save(rel);
            // A-8: sin unidades activas el JWT ya no autentica en la comunidad (antes: pagina vacia).
            as(a.owner(), a, get("/api/v1/accounting/invoices"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Saldo: un COMMUNITY_ADMIN no consulta el saldo de una unidad de otra comunidad (404)")
        void adminBalanceIsTenantScoped() throws Exception {
            as(a.admin(), a, get("/api/v1/accounting/properties/{id}/balance", b.neighbourHome().getId()))
                    .andExpect(status().isNotFound());
            as(a.admin(), a, get("/api/v1/accounting/properties/{id}/balance", a.neighbourHome().getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.balanceDue").value(450000.00));
        }

        @Test
        @DisplayName("Crear cuenta de cobro: un admin no puede facturar a una unidad ni a un residente de otra comunidad")
        void createInvoiceIsTenantScoped() throws Exception {
            String foreignProperty = """
                    {"propertyId":"%s","period":"Febrero 2027","baseAmount":100000,"dueDate":"%s"}
                    """.formatted(b.home().getId(), LocalDate.now().plusDays(20));
            as(a.admin(), a, json(post("/api/v1/accounting/invoices"), foreignProperty)).andExpect(status().isNotFound());

            String foreignResident = """
                    {"propertyId":"%s","residentId":"%s","period":"Febrero 2027","baseAmount":100000,"dueDate":"%s"}
                    """.formatted(a.home().getId(), b.ownerResident().getId(), LocalDate.now().plusDays(20));
            as(a.admin(), a, json(post("/api/v1/accounting/invoices"), foreignResident)).andExpect(status().isNotFound());

            assertThat(invoiceRepository.findByTenantIdAndPropertyId(b.tenantId(), b.home().getId())).hasSize(1);
        }

        @Test
        @DisplayName("Crear cuenta de cobro en su comunidad queda asociada a su tenant")
        void createInvoiceInOwnTenant() throws Exception {
            String body = """
                    {"propertyId":"%s","period":"Febrero 2027","baseAmount":100000,"dueDate":"%s"}
                    """.formatted(a.home().getId(), LocalDate.now().plusDays(20));
            as(a.admin(), a, json(post("/api/v1/accounting/invoices"), body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.tenantId").value(a.tenantId().toString()));
        }

        @Test
        @DisplayName("Un RESIDENT no puede crear cuentas de cobro ni registrar pagos")
        void residentCannotWrite() throws Exception {
            String body = """
                    {"propertyId":"%s","period":"Febrero 2027","baseAmount":1,"dueDate":"%s"}
                    """.formatted(a.home().getId(), LocalDate.now().plusDays(20));
            as(a.owner(), a, json(post("/api/v1/accounting/invoices"), body)).andExpect(status().isForbidden());
            String payment = """
                    {"invoiceId":"%s","amountPaid":450000}
                    """.formatted(da.neighbourInvoice().getId());
            as(a.owner(), a, json(post("/api/v1/accounting/payments"), payment)).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Reservas")
    class Reservations {

        @Test
        @DisplayName("RESIDENT lee su reserva por id; la del vecino y la de otra comunidad responden 404")
        void reservationById() throws Exception {
            as(a.owner(), a, get("/api/v1/reservations/{id}", da.ownReservation().getId())).andExpect(status().isOk());
            as(a.owner(), a, get("/api/v1/reservations/{id}", da.neighbourReservation().getId())).andExpect(status().isNotFound());
            as(a.owner(), a, get("/api/v1/reservations/{id}", db.ownReservation().getId())).andExpect(status().isNotFound());
            as(a.admin(), a, get("/api/v1/reservations/{id}", da.neighbourReservation().getId())).andExpect(status().isOk());
        }

        @Test
        @DisplayName("RESIDENT no puede cancelar la reserva del vecino; la reserva no cambia")
        void cannotCancelNeighbourReservation() throws Exception {
            as(a.owner(), a, post("/api/v1/reservations/{id}/cancel", da.neighbourReservation().getId()))
                    .andExpect(status().isNotFound());
            assertThat(reservationRepository.findById(da.neighbourReservation().getId()).orElseThrow().getStatus())
                    .isEqualTo(ReservationStatus.PENDING);
        }

        @Test
        @DisplayName("RESIDENT cancela su propia reserva")
        void canCancelOwnReservation() throws Exception {
            as(a.owner(), a, post("/api/v1/reservations/{id}/cancel", da.ownReservation().getId()))
                    .andExpect(status().isOk());
            assertThat(reservationRepository.findById(da.ownReservation().getId()).orElseThrow().getStatus())
                    .isNotEqualTo(ReservationStatus.PENDING);
        }

        private String reservation(UUID propertyId, UUID residentId, int startHour) {
            return """
                    {"commonZoneId":"%s","propertyId":"%s","residentId":%s,"reservationDate":"%s",
                     "startTime":"%02d:00:00","endTime":"%02d:00:00","guestsCount":2}
                    """.formatted(da.zone().getId(), propertyId, residentId == null ? "null" : "\"" + residentId + "\"",
                    da.reservationDay(), startHour, startHour + 1);
        }

        @Test
        @DisplayName("RESIDENT no puede reservar a nombre de otra unidad ni de un residente de otra unidad")
        void cannotCreateForOtherUnit() throws Exception {
            long before = reservationRepository.count();
            as(a.owner(), a, json(post("/api/v1/reservations"), reservation(a.neighbourHome().getId(), null, 18)))
                    .andExpect(status().isForbidden());
            as(a.owner(), a, json(post("/api/v1/reservations"), reservation(a.home().getId(), a.neighbourResident().getId(), 18)))
                    .andExpect(status().isForbidden());
            as(a.owner(), a, json(post("/api/v1/reservations"), reservation(b.home().getId(), null, 18)))
                    .andExpect(status().isForbidden());
            assertThat(reservationRepository.count()).isEqualTo(before);
        }

        @Test
        @DisplayName("RESIDENT reserva para su unidad; el admin puede reservar para cualquier unidad de su comunidad")
        void legitimateCreations() throws Exception {
            as(a.owner(), a, json(post("/api/v1/reservations"), reservation(a.home().getId(), a.censusOnly().getId(), 18)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.propertyId").value(a.home().getId().toString()));
            as(a.admin(), a, json(post("/api/v1/reservations"), reservation(a.neighbourHome().getId(), null, 20)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Disponibilidad por zona: el residente ve horarios ajenos sin datos personales ni de la unidad")
        void availabilityHidesOtherUnits() throws Exception {
            as(a.owner(), a, get("/api/v1/reservations/by-zone-and-date")
                    .param("zoneId", da.zone().getId().toString()).param("date", da.reservationDay().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[?(@.id == '%s')].residentName", da.neighbourReservation().getId()).value(hasItem(nullValue())))
                    .andExpect(jsonPath("$.data[?(@.id == '%s')].propertyNumber", da.neighbourReservation().getId()).value(hasItem(nullValue())))
                    .andExpect(jsonPath("$.data[?(@.id == '%s')].observations", da.neighbourReservation().getId()).value(hasItem(nullValue())))
                    .andExpect(jsonPath("$.data[?(@.id == '%s')].startTime", da.neighbourReservation().getId()).value(hasItem("14:00:00")))
                    .andExpect(jsonPath("$.data[?(@.id == '%s')].propertyNumber", da.ownReservation().getId()).value(hasItem(a.home().getNumber())));
            as(a.admin(), a, get("/api/v1/reservations/by-zone-and-date")
                    .param("zoneId", da.zone().getId().toString()).param("date", da.reservationDay().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].propertyNumber").value(everyItem(not(nullValue()))));
        }
    }

    @Nested
    @DisplayName("Notificaciones")
    class Notifications {

        @Test
        @DisplayName("RESIDENT sin filtros solo ve las notificaciones dirigidas a el")
        void defaultListIsOwn() throws Exception {
            as(a.owner(), a, get("/api/v1/realtime/notifications"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(da.ownNotification().getId().toString()));
        }

        @Test
        @DisplayName("RESIDENT no lista las notificaciones de otro usuario ni de otra unidad")
        void cannotListOthers() throws Exception {
            as(a.owner(), a, get("/api/v1/realtime/notifications").param("userId", a.neighbour().getId().toString()))
                    .andExpect(status().isForbidden());
            as(a.owner(), a, get("/api/v1/realtime/notifications").param("propertyId", a.neighbourHome().getId().toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("RESIDENT no puede marcar como leida una notificacion ajena (404) y si la suya")
        void markAsRead() throws Exception {
            as(a.owner(), a, put("/api/v1/realtime/notifications/{id}/read", da.neighbourNotification().getId()))
                    .andExpect(status().isNotFound());
            as(a.owner(), a, put("/api/v1/realtime/notifications/{id}/read", da.neighbourUnitNotification().getId()))
                    .andExpect(status().isNotFound());
            assertThat(notificationRepository.findById(da.neighbourNotification().getId()).orElseThrow().getIsRead()).isFalse();
            as(a.owner(), a, put("/api/v1/realtime/notifications/{id}/read", da.ownNotification().getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isRead").value(true));
        }

        @Test
        @DisplayName("COMMUNITY_ADMIN lista todas las de su comunidad y no envia a usuarios ni unidades de otra comunidad")
        void adminScope() throws Exception {
            as(a.admin(), a, get("/api/v1/realtime/notifications"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(3));
            String toForeignUser = """
                    {"userId":"%s","title":"t","message":"m","type":"GENERAL_ANNOUNCEMENT"}
                    """.formatted(b.owner().getId());
            as(a.admin(), a, json(post("/api/v1/realtime/notifications"), toForeignUser)).andExpect(status().isNotFound());
            String toForeignUnit = """
                    {"propertyId":"%s","title":"t","message":"m","type":"GENERAL_ANNOUNCEMENT"}
                    """.formatted(b.home().getId());
            as(a.admin(), a, json(post("/api/v1/realtime/notifications"), toForeignUnit)).andExpect(status().isNotFound());
            String toOwnUnit = """
                    {"propertyId":"%s","title":"t","message":"m","type":"GENERAL_ANNOUNCEMENT"}
                    """.formatted(a.home().getId());
            as(a.admin(), a, json(post("/api/v1/realtime/notifications"), toOwnUnit))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.tenantId", is(a.tenantId().toString())));
        }
    }
}
