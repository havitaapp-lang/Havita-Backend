package com.havita.modules.property.application;

import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.property.domain.PropertyStatus;
import com.havita.common.exception.InvalidRequestException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.property.api.dto.TransferOwnershipRequest;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.identity.application.TenantUserResolver;
import com.havita.modules.identity.domain.User;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.modules.sync.domain.SyncEvent;
import java.util.Map;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.havita.modules.resident.application.ResidentInvitationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;




@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyTransferService {

    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final TenantUserResolver tenantUserResolver;
    private final AssemblyAttendeeRepository assemblyAttendeeRepository;
    private final SyncEventPublisher syncEventPublisher;
    private final ResidentInvitationService invitationService;
    private final com.havita.modules.resident.application.ResidentLifecycleAudit lifecycleAudit;
    private final com.havita.modules.identity.infrastructure.UserRepository userRepository;
    private final com.havita.security.AccessValidator accessValidator;

    /**
     * SEC-8: transferencia de titularidad. Solo la administracion de la comunidad (comprobada contra la base, no solo
     * con el rol del JWT) y con exactamente un destinatario: una cuenta existente de esta comunidad (vinculacion directa,
     * decision aprobada el 2026-11-04) o un correo (invitacion ligada a ese correo). La unidad se bloquea durante la
     * operacion para que dos transferencias simultaneas no dejen dos propietarios activos, y los enlaces de traspaso
     * pendientes de la unidad se revocan. Todo se valida antes de modificar nada; queda auditado.
     */
    @Transactional
    public Map<String, Object> transferOwnership(UUID propertyId, TransferOwnershipRequest request, UUID adminUserId) {
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        String email = request.newOwnerEmail() == null || request.newOwnerEmail().isBlank()
                ? null : request.newOwnerEmail().trim().toLowerCase();
        if ((request.newOwnerUserId() == null) == (email == null)) {
            throw new InvalidRequestException("TRANSFER_RECIPIENT_REQUIRED",
                    "Indica exactamente un destinatario: una cuenta existente de la comunidad o un correo electronico.");
        }

        Property property = propertyRepository.findByIdAndTenantIdForUpdate(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));

        // Solo cuentas que ya pertenecen a esta comunidad; un id ajeno responde 404 en lugar de vincularse.
        User newOwnerUser = request.newOwnerUserId() != null
                ? tenantUserResolver.requireUserInTenant(request.newOwnerUserId(), tenantId)
                : null;
        User actor = userRepository.findById(adminUserId).orElse(null);
        OffsetDateTime effective = request.effectiveDate() != null ? request.effectiveDate() : OffsetDateTime.now();

        invitationService.revokePendingTransferInvitations(propertyId);

        // 1. Find current active owners and end them
        List<Resident> currentOwners = residentRepository.findByTenantIdAndPropertyId(tenantId, propertyId).stream()
                .filter(r -> "Propietario".equalsIgnoreCase(r.getType()) && r.getStatus() == ResidentStatus.ACTIVE)
                .toList();

        for (Resident oldOwner : currentOwners) {
            oldOwner.setStatus(ResidentStatus.INACTIVE);
            oldOwner.setEndedAt(effective);
            oldOwner.setEndedBy(actor);
            oldOwner.setEndReason(request.reason() != null ? request.reason() : "OWNERSHIP_TRANSFER");
            residentRepository.save(oldOwner);

            // Invalidate future assembly delegations
            List<AssemblyAttendee> futureAttendees = assemblyAttendeeRepository.findByPropertyId(propertyId).stream()
                .filter(a -> a.getAssembly() != null && a.getAssembly().getAssemblyDate() != null)
                .filter(a -> a.getAssembly().getAssemblyDate().isAfter(java.time.LocalDate.now()) || a.getAssembly().getAssemblyDate().isEqual(java.time.LocalDate.now()))
                .filter(a -> Boolean.TRUE.equals(a.getIsPowerOfAttorney()))
                .toList();

            for (AssemblyAttendee attendee : futureAttendees) {
                // Remove or invalidate delegation
                attendee.setIsPowerOfAttorney(false);
                assemblyAttendeeRepository.save(attendee);
            }
        }

        // 2. Create the new owner
        Resident newOwner = Resident.builder()
                .property(property)
                .name(request.newOwnerName())
                .email(newOwnerUser != null ? newOwnerUser.getEmail() : email)
                .phone(request.newOwnerPhone())
                .documentId(request.newOwnerDocumentId())
                .documentType("CC")
                .type("Propietario")
                .status(ResidentStatus.ACTIVE)
                .isMainContact(true)
                .registeredAt(effective)
                .build();
        newOwner.setTenantId(tenantId);
        newOwner.setUser(newOwnerUser);
        residentRepository.save(newOwner);

        property.setStatus(PropertyStatus.OCCUPIED);
        propertyRepository.save(property);

        // 3. Cuenta existente: vinculada ya, sin enlace. Correo: invitacion ligada a ese destinatario.
        String link = newOwnerUser == null ? invitationService.generateTransferInvitation(propertyId, newOwner).link() : null;
        lifecycleAudit.ownershipTransferred(newOwner, currentOwners, newOwnerUser != null ? "ACCOUNT" : "INVITATION");

        // 4. Emit SSE Event
        syncEventPublisher.publish(new SyncEvent("PROPERTY_OWNER_TRANSFERRED", tenantId, Map.of("propertyId", propertyId.toString())));
        syncEventPublisher.publish(new SyncEvent("OWNER_ACCESS_CHANGED", tenantId, Map.of("propertyId", propertyId.toString())));

        Map<String, Object> res = new java.util.HashMap<>();
        res.put("message", "Propiedad transferida exitosamente");
        res.put("inviteLink", link);
        res.put("linkedUserId", newOwnerUser != null ? newOwnerUser.getId() : null);
        return res;
    }
}
