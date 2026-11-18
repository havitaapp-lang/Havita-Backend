package com.havita.modules.resident;

import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.assembly.infrastructure.VotingDelegationRepository;
import com.havita.modules.identity.application.GlobalEmailValidationService;
import com.havita.modules.identity.application.TenantUserResolver;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.api.dto.AssignPropertiesRequest;
import com.havita.modules.resident.api.dto.CreateResidentRequest;
import com.havita.modules.resident.api.dto.ResidentResponse;
import com.havita.modules.resident.api.dto.UpdateResidentRequest;
import com.havita.modules.resident.application.ResidentService;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.security.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SEC-1: las operaciones administrativas del censo solo pueden referenciar residentes y cuentas de la
 * comunidad actual, nunca crean membresias y no modifican la cuenta global del usuario.
 */
@ExtendWith(MockitoExtension.class)
class ResidentTenantIsolationTest {

    @Mock private ResidentRepository residentRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private UserRepository userRepository;
    @Mock private TenantUserResolver tenantUserResolver;
    @Mock private SyncEventPublisher syncEventPublisher;
    @Mock private GlobalEmailValidationService globalEmailValidationService;
    @Mock private VotingDelegationRepository votingDelegationRepository;

    @Mock private com.havita.modules.resident.application.ResidentLifecycleAudit lifecycleAudit;
    @Mock private com.havita.security.AccessValidator accessValidator; // SEC-9 P1: comprobacion transaccional (FOR SHARE)

    @InjectMocks private ResidentService residentService;

    private final UUID tenantA = UUID.randomUUID();
    private Property propertyA;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantA);
        propertyA = new Property();
        propertyA.setId(UUID.randomUUID());
        propertyA.setTenantId(tenantA);
        propertyA.setNumber("T1-101");
        propertyA.setName("Apto 101");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private Resident residentOf(UUID tenantId, Property property, User user) {
        Resident r = Resident.builder()
                .property(property).user(user)
                .name("Perfil Comunidad").email("perfil@example.test").phone("3000000001")
                .documentId("CENSO-1").type("Propietario").status(ResidentStatus.ACTIVE)
                .build();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        return r;
    }

    // ── assignProperties ────────────────────────────────────────────────────────

    @Test
    @DisplayName("assignProperties con un id de otra comunidad responde 404 y no escribe nada")
    void assignRejectsForeignIdentifier() {
        UUID foreignId = UUID.randomUUID();
        when(residentRepository.findByIdAndTenantId(foreignId, tenantA)).thenReturn(Optional.empty());
        when(tenantUserResolver.requireUserInTenant(foreignId, tenantA))
                .thenThrow(new ResourceNotFoundException("User", foreignId));

        assertThrows(ResourceNotFoundException.class, () -> residentService.assignProperties(foreignId,
                new AssignPropertiesRequest(List.of(propertyA.getId()), "Propietario", false)));

        verify(residentRepository, never()).findById(any());
        verify(userRepository, never()).findById(any());
        verify(residentRepository, never()).save(any());
        verify(propertyRepository, never()).save(any());
    }

    @Test
    @DisplayName("assignProperties con un residente del censo copia el perfil de la comunidad, no la cuenta global")
    void assignCopiesCommunityProfile() {
        User account = User.builder().email("global@example.test").fullName("Nombre Global").phone("3999999999").build();
        account.setId(UUID.randomUUID());
        Property otherUnit = new Property();
        otherUnit.setId(UUID.randomUUID());
        otherUnit.setTenantId(tenantA);
        Resident existing = residentOf(tenantA, otherUnit, account);

        when(residentRepository.findByIdAndTenantId(existing.getId(), tenantA)).thenReturn(Optional.of(existing));
        when(residentRepository.findByUserIdAndTenantId(account.getId(), tenantA)).thenReturn(List.of(existing));
        when(propertyRepository.findByIdAndTenantId(propertyA.getId(), tenantA)).thenReturn(Optional.of(propertyA));
        when(residentRepository.save(any(Resident.class))).thenAnswer(inv -> inv.getArgument(0));

        List<ResidentResponse> out = residentService.assignProperties(existing.getId(),
                new AssignPropertiesRequest(List.of(propertyA.getId()), "Propietario", false));

        assertEquals(1, out.size());
        ArgumentCaptor<Resident> saved = ArgumentCaptor.forClass(Resident.class);
        verify(residentRepository).save(saved.capture());
        assertEquals(tenantA, saved.getValue().getTenantId());
        assertEquals("Perfil Comunidad", saved.getValue().getName());
        assertEquals("perfil@example.test", saved.getValue().getEmail());
        assertEquals("3000000001", saved.getValue().getPhone());
        verifyNoInteractions(tenantUserResolver);
    }

    @Test
    @DisplayName("assignProperties con una cuenta que ya pertenece a la comunidad la vincula sin crear membresias")
    void assignAcceptsUserOfSameTenant() {
        User account = User.builder().email("vecino@example.test").fullName("Vecino").build();
        account.setId(UUID.randomUUID());
        when(residentRepository.findByIdAndTenantId(account.getId(), tenantA)).thenReturn(Optional.empty());
        when(tenantUserResolver.requireUserInTenant(account.getId(), tenantA)).thenReturn(account);
        when(residentRepository.findByUserIdAndTenantId(account.getId(), tenantA)).thenReturn(List.of());
        when(propertyRepository.findByIdAndTenantId(propertyA.getId(), tenantA)).thenReturn(Optional.of(propertyA));
        when(residentRepository.save(any(Resident.class))).thenAnswer(inv -> inv.getArgument(0));

        residentService.assignProperties(account.getId(),
                new AssignPropertiesRequest(List.of(propertyA.getId()), "Propietario", true));

        ArgumentCaptor<Resident> saved = ArgumentCaptor.forClass(Resident.class);
        verify(residentRepository).save(saved.capture());
        assertSame(account, saved.getValue().getUser());
        assertEquals(tenantA, saved.getValue().getTenantId());
    }

    @Test
    @DisplayName("assignProperties rechaza unidades de otra comunidad")
    void assignRejectsForeignProperty() {
        Resident existing = residentOf(tenantA, propertyA, null);
        UUID foreignProperty = UUID.randomUUID();
        when(residentRepository.findByIdAndTenantId(existing.getId(), tenantA)).thenReturn(Optional.of(existing));
        when(propertyRepository.findByIdAndTenantId(foreignProperty, tenantA)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> residentService.assignProperties(existing.getId(),
                new AssignPropertiesRequest(List.of(foreignProperty), "Propietario", false)));
        verify(residentRepository, never()).save(any());
    }

    // ── create ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create con userId de otra comunidad responde 404 y no guarda el residente")
    void createRejectsForeignUser() {
        UUID foreignUser = UUID.randomUUID();
        when(propertyRepository.findByIdAndTenantId(propertyA.getId(), tenantA)).thenReturn(Optional.of(propertyA));
        when(tenantUserResolver.requireUserInTenant(foreignUser, tenantA))
                .thenThrow(new ResourceNotFoundException("User", foreignUser));

        CreateResidentRequest request = new CreateResidentRequest(propertyA.getId(), foreignUser, "Intruso",
                null, null, "Propietario", "3001112233", "intruso@example.test", false, null, null);

        assertThrows(ResourceNotFoundException.class, () -> residentService.create(request));
        verify(residentRepository, never()).save(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("create sin userId registra solo el censo en la comunidad actual")
    void createWithoutUserStaysInTenant() {
        when(propertyRepository.findByIdAndTenantId(propertyA.getId(), tenantA)).thenReturn(Optional.of(propertyA));
        when(residentRepository.save(any(Resident.class))).thenAnswer(inv -> inv.getArgument(0));

        residentService.create(new CreateResidentRequest(propertyA.getId(), null, " Nueva Persona ",
                null, null, "Arrendatario", "3001112233", "nueva@example.test", false, null, null));

        ArgumentCaptor<Resident> saved = ArgumentCaptor.forClass(Resident.class);
        verify(residentRepository).save(saved.capture());
        assertEquals(tenantA, saved.getValue().getTenantId());
        assertNull(saved.getValue().getUser());
        assertEquals("Nueva Persona", saved.getValue().getName());
        verifyNoInteractions(tenantUserResolver);
    }

    // ── update ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update de un residente de otra comunidad responde 404")
    void updateRejectsForeignResident() {
        UUID foreignResident = UUID.randomUUID();
        when(residentRepository.findByIdAndTenantId(foreignResident, tenantA)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> residentService.update(foreignResident,
                new UpdateResidentRequest(null, null, "X", null, null, null, null, null, null, null, null, null)));
        verify(residentRepository, never()).save(any());
    }

    @Test
    @DisplayName("update con userId de otra comunidad responde 404 y no vincula la cuenta")
    void updateRejectsForeignUser() {
        Resident target = residentOf(tenantA, propertyA, null);
        UUID foreignUser = UUID.randomUUID();
        when(residentRepository.findByIdAndTenantId(target.getId(), tenantA)).thenReturn(Optional.of(target));
        when(tenantUserResolver.requireUserInTenant(foreignUser, tenantA))
                .thenThrow(new ResourceNotFoundException("User", foreignUser));

        assertThrows(ResourceNotFoundException.class, () -> residentService.update(target.getId(),
                new UpdateResidentRequest(null, foreignUser, null, null, null, null, null, null, null, null, null, null)));
        assertNull(target.getUser());
        verify(residentRepository, never()).save(any());
    }

    @Test
    @DisplayName("update del censo no modifica el nombre ni el telefono de la cuenta global")
    void updateDoesNotTouchGlobalAccount() {
        User account = User.builder().email("cuenta@example.test").fullName("Nombre Cuenta").phone("3111111111").build();
        account.setId(UUID.randomUUID());
        Resident target = residentOf(tenantA, propertyA, account);
        when(residentRepository.findByIdAndTenantId(target.getId(), tenantA)).thenReturn(Optional.of(target));
        when(residentRepository.save(any(Resident.class))).thenAnswer(inv -> inv.getArgument(0));
        when(votingDelegationRepository.findByPropertyIdAndStatusAndTenantId(eq(propertyA.getId()), any(), eq(tenantA)))
                .thenReturn(Optional.empty());

        ResidentResponse out = residentService.update(target.getId(), new UpdateResidentRequest(
                null, null, "Nombre en el Censo", null, null, null, "3222222222", null, null, null, null, ResidentStatus.INACTIVE));

        assertEquals("Nombre en el Censo", out.name());
        assertEquals("3222222222", out.phone());
        assertEquals(ResidentStatus.INACTIVE, out.status());
        assertEquals("Nombre Cuenta", account.getFullName());
        assertEquals("3111111111", account.getPhone());
        verify(userRepository, never()).save(any());
    }

    // ── reactivate (defensa en profundidad) ────────────────────────────────────

    @Test
    @DisplayName("reactivar un residente de otra comunidad responde 404")
    void reactivateRejectsForeignResident() {
        UUID foreignResident = UUID.randomUUID();
        when(residentRepository.findByIdAndTenantId(foreignResident, tenantA)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> residentService.reactivateResident(foreignResident, propertyA.getId(), UUID.randomUUID()));
        verify(residentRepository, never()).findById(any());
        verify(residentRepository, never()).save(any());
    }
}
