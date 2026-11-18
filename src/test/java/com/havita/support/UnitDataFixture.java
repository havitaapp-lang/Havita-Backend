package com.havita.support;

import com.havita.modules.access.domain.Package;
import com.havita.modules.access.infrastructure.PackageRepository;
import com.havita.modules.accounting.domain.PropertyInvoice;
import com.havita.modules.accounting.domain.PropertyInvoiceStatus;
import com.havita.modules.accounting.infrastructure.PropertyInvoiceRepository;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.CommonZone;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.CommonZoneRepository;
import com.havita.modules.realtime.domain.RealtimeNotification;
import com.havita.modules.realtime.infrastructure.RealtimeNotificationRepository;
import com.havita.modules.reservation.domain.Reservation;
import com.havita.modules.reservation.infrastructure.ReservationRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.support.TwoTenantFixture.Side;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Datos sinteticos por unidad (paquetes, cuentas de cobro, reservas y notificaciones) sobre una
 * comunidad de {@link TwoTenantFixture}. La unidad propia ({@code home}) no tiene deuda pendiente y la del
 * vecino ({@code neighbourHome}) si, para distinguir los datos de cada una en las respuestas.
 */
@Component
public class UnitDataFixture {

    @Autowired private PackageRepository packageRepository;
    @Autowired private PropertyInvoiceRepository invoiceRepository;
    @Autowired private CommonZoneRepository commonZoneRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private RealtimeNotificationRepository notificationRepository;

    public UnitData seed(Side side) {
        CommonZone zone = CommonZone.builder().name("Salon social").capacity(50).build();
        zone.setTenantId(side.tenantId());
        zone = commonZoneRepository.save(zone);
        LocalDate day = LocalDate.now().plusDays(10);

        return new UnitData(
                pkg(side, side.home(), "Destinatario propio"),
                pkg(side, side.neighbourHome(), "Destinatario vecino"),
                invoice(side, side.home(), PropertyInvoiceStatus.PAID, BigDecimal.ZERO),
                invoice(side, side.neighbourHome(), PropertyInvoiceStatus.PENDING, new BigDecimal("450000.00")),
                zone,
                day,
                reservation(side, zone, side.home(), side.ownerResident(), side.owner(), day, 8),
                reservation(side, zone, side.neighbourHome(), side.neighbourResident(), side.neighbour(), day, 14),
                notification(side, side.owner(), null, "Para el propietario"),
                notification(side, side.neighbour(), null, "Para el vecino"),
                notification(side, null, side.neighbourHome(), "Para la unidad del vecino"));
    }

    private Package pkg(Side side, Property property, String recipient) {
        Package p = Package.builder().property(property).recipientName(recipient).trackingNumber("TRK-" + UUID.randomUUID()).build();
        p.setTenantId(side.tenantId());
        return packageRepository.save(p);
    }

    private PropertyInvoice invoice(Side side, Property property, PropertyInvoiceStatus status, BigDecimal balance) {
        PropertyInvoice i = PropertyInvoice.builder()
                .property(property).invoiceNumber("INV-" + UUID.randomUUID()).period("Enero 2027")
                .baseAmount(new BigDecimal("450000.00")).totalAmount(new BigDecimal("450000.00")).balanceDue(balance)
                .status(status).dueDate(LocalDate.now().plusDays(15))
                .build();
        i.setTenantId(side.tenantId());
        return invoiceRepository.save(i);
    }

    private Reservation reservation(Side side, CommonZone zone, Property property, Resident resident, User creator,
                                    LocalDate day, int startHour) {
        Reservation r = Reservation.builder()
                .commonZone(zone).property(property).resident(resident).createdByUser(creator)
                .reservationDate(day).startTime(LocalTime.of(startHour, 0)).endTime(LocalTime.of(startHour + 2, 0))
                .observations("Observacion privada " + property.getNumber())
                .build();
        r.setTenantId(side.tenantId());
        return reservationRepository.save(r);
    }

    private RealtimeNotification notification(Side side, User user, Property property, String title) {
        RealtimeNotification n = RealtimeNotification.builder()
                .user(user).property(property).title(title).message(title).type("GENERAL_ANNOUNCEMENT")
                .build();
        n.setTenantId(side.tenantId());
        return notificationRepository.save(n);
    }

    public record UnitData(Package ownPackage, Package neighbourPackage,
                           PropertyInvoice ownInvoice, PropertyInvoice neighbourInvoice,
                           CommonZone zone, LocalDate reservationDay,
                           Reservation ownReservation, Reservation neighbourReservation,
                           RealtimeNotification ownNotification, RealtimeNotification neighbourNotification,
                           RealtimeNotification neighbourUnitNotification) {}
}
