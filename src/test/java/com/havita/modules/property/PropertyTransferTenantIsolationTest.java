package com.havita.modules.property;

import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.identity.application.TenantUserResolver;
import com.havita.modules.property.api.dto.TransferOwnershipRequest;
import com.havita.modules.property.application.PropertyTransferService;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.application.ResidentInvitationService;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.security.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** SEC-1: la transferencia de propiedad no puede vincular cuentas de otra comunidad. */
@ExtendWith(MockitoExtension.class)
class PropertyTransferTenantIsolationTest {

    @Mock private PropertyRepository propertyRepository;
    @Mock private ResidentRepository residentRepository;
    @Mock private TenantUserResolver tenantUserResolver;
    @Mock private AssemblyAttendeeRepository assemblyAttendeeRepository;
    @Mock private SyncEventPublisher syncEventPublisher;
    @Mock private ResidentInvitationService invitationService;
    @Mock private com.havita.modules.resident.application.ResidentLifecycleAudit lifecycleAudit;
    @Mock private com.havita.modules.identity.infrastructure.UserRepository userRepository;
    @Mock private com.havita.security.AccessValidator accessValidator;

    @InjectMocks private PropertyTransferService transferService;

    private final UUID tenantA = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Transferir a una cuenta de otra comunidad responde 404 antes de desactivar al propietario actual")
    void rejectsForeignNewOwnerBeforeAnyChange() {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setTenantId(tenantA);
        UUID foreignUser = UUID.randomUUID();
        when(propertyRepository.findByIdAndTenantIdForUpdate(property.getId(), tenantA)).thenReturn(Optional.of(property));
        when(tenantUserResolver.requireUserInTenant(foreignUser, tenantA))
                .thenThrow(new ResourceNotFoundException("User", foreignUser));

        // SEC-8: un unico destinatario (la cuenta); con cuenta y correo a la vez la solicitud es ambigua (400).
        TransferOwnershipRequest request = new TransferOwnershipRequest("Nuevo Dueno", foreignUser,
                null, "3001112233", "123", OffsetDateTime.now(), "VENTA");

        assertThrows(ResourceNotFoundException.class,
                () -> transferService.transferOwnership(property.getId(), request, UUID.randomUUID()));

        verifyNoInteractions(residentRepository);
        verify(propertyRepository, never()).save(any());
        verifyNoInteractions(invitationService);
    }

    @Test
    @DisplayName("SEC-8: sin administracion de la comunidad (comprobada en la base) se rechaza antes de leer o modificar nada")
    void rejectsNonAdministratorInServiceLayer() {
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("no"))
                .when(accessValidator).lockCommunityAdministrator();
        TransferOwnershipRequest request = new TransferOwnershipRequest("Nuevo Dueno", null,
                "nuevo@example.test", "3001112233", "123", OffsetDateTime.now(), "VENTA");

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> transferService.transferOwnership(UUID.randomUUID(), request, UUID.randomUUID()));

        verifyNoInteractions(propertyRepository, residentRepository, invitationService, tenantUserResolver);
    }
}
