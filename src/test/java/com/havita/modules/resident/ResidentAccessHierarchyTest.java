package com.havita.modules.resident;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.application.ResidentInvitationService;
import com.havita.modules.resident.application.ResidentService;
import com.havita.modules.resident.domain.InvitationStatus;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentInvitation;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentInvitationRepository;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResidentAccessHierarchyTest {

    @Mock private ResidentRepository residentRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private UserRepository userRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private ResidentInvitationRepository invitationRepository;
    @Mock private SyncEventPublisher syncEventPublisher;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private com.havita.modules.identity.application.GlobalEmailValidationService globalEmailValidationService;
    @Mock private com.havita.modules.identity.infrastructure.MembershipRepository membershipRepository;
    @Mock private com.havita.modules.assembly.infrastructure.VotingDelegationRepository votingDelegationRepository;
    @Mock private com.havita.modules.identity.application.LegalConsentService legalConsentService;

    @Mock private com.havita.modules.resident.application.ResidentLifecycleAudit lifecycleAudit;
    @InjectMocks private ResidentService residentService;
    @InjectMocks private ResidentInvitationService invitationService;

    private UUID tenantId;
    private UUID propertyId;
    private UUID ownerUserId;
    private UUID tenantUserId;
    private Property property;
    private User ownerUser;
    private Resident ownerRes;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        ownerUserId = UUID.randomUUID();
        tenantUserId = UUID.randomUUID();

        TenantContextHolder.setTenantId(tenantId);

        property = new Property();
        property.setId(propertyId);
        property.setTenantId(tenantId);
        property.setNumber("101");
        property.setName("Apto 101");

        ownerUser = User.builder().fullName("Juan Propietario").email("owner@example.com").build();
        ownerUser.setId(ownerUserId);

        ownerRes = Resident.builder()
                .property(property)
                .user(ownerUser)
                .type("Propietario")
                .status(ResidentStatus.ACTIVE)
                .build();
        ownerRes.setId(UUID.randomUUID());
        ownerRes.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void mockSecurityUser(UUID userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userId.toString());
        SecurityContext context = mock(SecurityContext.class);
        when(context.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(context);
    }

    // =========================================================================
    // 1. REGLA: MÁXIMO UN ARRENDATARIO ACTIVO POR UNIDAD
    // =========================================================================

    @Test
    @DisplayName("Invitar segundo arrendatario cuando ya existe uno activo debe fallar con SECOND_ACTIVE_TENANT")
    void shouldRejectSecondActiveTenantInvite() {
        mockSecurityUser(ownerUserId);

        Resident ownerRel = Resident.builder()
                .property(property)
                .type("Propietario")
                .status(ResidentStatus.ACTIVE)
                .build();
        ownerRel.setTenantId(tenantId);

        when(residentRepository.findByUserIdAndTenantId(ownerUserId, tenantId))
                .thenReturn(List.of(ownerRel));
        when(residentRepository.existsByPropertyIdAndTypeIgnoreCaseAndStatus(propertyId, "Arrendatario", ResidentStatus.ACTIVE))
                .thenReturn(true);

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                invitationService.generateHouseholdInvitation(propertyId, "test@example.com", "Pedro", "3001234567", "Arrendatario")
        );

        assertEquals("SECOND_ACTIVE_TENANT", ex.getCode());
    }

    @Test
    @DisplayName("Invitar arrendatario cuando ya existe una invitación PENDING debe fallar con PENDING_TENANT_INVITATION")
    void shouldRejectTenantInviteWhenAnotherIsPending() {
        mockSecurityUser(ownerUserId);

        Resident ownerRel = Resident.builder()
                .property(property)
                .type("Propietario")
                .status(ResidentStatus.ACTIVE)
                .build();
        ownerRel.setTenantId(tenantId);

        when(residentRepository.findByUserIdAndTenantId(ownerUserId, tenantId))
                .thenReturn(List.of(ownerRel));
        when(residentRepository.existsByPropertyIdAndTypeIgnoreCaseAndStatus(propertyId, "Arrendatario", ResidentStatus.ACTIVE))
                .thenReturn(false);
        when(invitationRepository.existsByPropertyIdAndInvitationTypeIgnoreCaseAndStatus(propertyId, "Arrendatario", InvitationStatus.PENDING))
                .thenReturn(true);

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                invitationService.generateHouseholdInvitation(propertyId, "test@example.com", "Pedro", "3001234567", "Arrendatario")
        );

        assertEquals("PENDING_TENANT_INVITATION", ex.getCode());
    }

    // =========================================================================
    // 2. AUTORIZACIÓN: QUIÉN PUEDE REMOVER A QUIÉN
    // =========================================================================

    @Test
    @DisplayName("Arrendatario intentando remover al Propietario debe fallar con TENANT_CANNOT_REMOVE_OWNER")
    void tenantCannotRemoveOwner() {
        UUID ownerResId = UUID.randomUUID();
        Resident ownerRes = Resident.builder()
                .property(property)
                .type("Propietario")
                .status(ResidentStatus.ACTIVE)
                .build();
        ownerRes.setId(ownerResId);
        ownerRes.setTenantId(tenantId);

        Resident tenantRes = Resident.builder()
                .property(property)
                .type("Arrendatario")
                .status(ResidentStatus.ACTIVE)
                .build();
        tenantRes.setTenantId(tenantId);

        User tenantUser = new User();
        tenantUser.setId(tenantUserId);

        when(residentRepository.findByIdAndTenantId(ownerResId, tenantId)).thenReturn(Optional.of(ownerRes));
        when(userRepository.findById(tenantUserId)).thenReturn(Optional.of(tenantUser));
        when(residentRepository.findByUserIdAndTenantId(tenantUserId, tenantId)).thenReturn(List.of(tenantRes));

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                residentService.terminateResident(ownerResId, propertyId, tenantUserId)
        );

        assertEquals("TENANT_CANNOT_REMOVE_OWNER", ex.getCode());
    }

    @Test
    @DisplayName("Arrendatario intentando remover a un residente creado por el Propietario debe fallar con UNAUTHORIZED_ACCESS_REVOCATION")
    void tenantCannotRemoveOwnersResident() {
        UUID ownerResId = UUID.randomUUID();
        Resident ownerRes = Resident.builder()
                .property(property)
                .type("Propietario")
                .status(ResidentStatus.ACTIVE)
                .build();
        ownerRes.setId(ownerResId);

        UUID resCreatedByOwnerId = UUID.randomUUID();
        Resident resCreatedByOwner = Resident.builder()
                .property(property)
                .type("Residente")
                .parentResident(ownerRes) // Creado por propietario
                .status(ResidentStatus.ACTIVE)
                .build();
        resCreatedByOwner.setId(resCreatedByOwnerId);
        resCreatedByOwner.setTenantId(tenantId);

        UUID tenantResId = UUID.randomUUID();
        Resident tenantRes = Resident.builder()
                .property(property)
                .type("Arrendatario")
                .status(ResidentStatus.ACTIVE)
                .build();
        tenantRes.setId(tenantResId);
        tenantRes.setTenantId(tenantId);

        User tenantUser = new User();
        tenantUser.setId(tenantUserId);

        when(residentRepository.findByIdAndTenantId(resCreatedByOwnerId, tenantId)).thenReturn(Optional.of(resCreatedByOwner));
        when(userRepository.findById(tenantUserId)).thenReturn(Optional.of(tenantUser));
        when(residentRepository.findByUserIdAndTenantId(tenantUserId, tenantId)).thenReturn(List.of(tenantRes));

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                residentService.terminateResident(resCreatedByOwnerId, propertyId, tenantUserId)
        );

        assertEquals("UNAUTHORIZED_ACCESS_REVOCATION", ex.getCode());
    }

    // =========================================================================
    // 3. REVOCACIÓN EN CASCADA Y PRESERVACIÓN DE RESIDENTES DEL PROPIETARIO
    // =========================================================================

    @Test
    @DisplayName("Propietario remueve Arrendatario: se revoca Arrendatario y sus dependientes, pero NO los residentes propios")
    void ownerRemovesTenantCascadesCorrectly() {
        User ownerUser = new User();
        ownerUser.setId(ownerUserId);

        UUID ownerResId = UUID.randomUUID();
        Resident ownerRes = Resident.builder()
                .property(property)
                .type("Propietario")
                .user(ownerUser)
                .status(ResidentStatus.ACTIVE)
                .build();
        ownerRes.setId(ownerResId);
        ownerRes.setTenantId(tenantId);

        User tenantUser = new User();
        tenantUser.setId(tenantUserId);

        UUID tenantResId = UUID.randomUUID();
        Resident tenantRes = Resident.builder()
                .property(property)
                .type("Arrendatario")
                .user(tenantUser)
                .parentResident(ownerRes)
                .status(ResidentStatus.ACTIVE)
                .build();
        tenantRes.setId(tenantResId);
        tenantRes.setTenantId(tenantId);

        // Residente agregado por el Arrendatario
        Resident depOfTenant = Resident.builder()
                .property(property)
                .type("Residente")
                .parentResident(tenantRes)
                .status(ResidentStatus.ACTIVE)
                .build();
        depOfTenant.setId(UUID.randomUUID());
        depOfTenant.setTenantId(tenantId);

        // Residente agregado por el Propietario directamente
        Resident resOfOwner = Resident.builder()
                .property(property)
                .type("Residente")
                .parentResident(ownerRes)
                .status(ResidentStatus.ACTIVE)
                .build();
        resOfOwner.setId(UUID.randomUUID());
        resOfOwner.setTenantId(tenantId);

        when(residentRepository.findByIdAndTenantId(tenantResId, tenantId)).thenReturn(Optional.of(tenantRes));
        when(userRepository.findById(ownerUserId)).thenReturn(Optional.of(ownerUser));
        when(residentRepository.findByUserIdAndTenantId(ownerUserId, tenantId)).thenReturn(List.of(ownerRes));
        when(residentRepository.findByPropertyIdAndStatus(propertyId, ResidentStatus.ACTIVE))
                .thenReturn(List.of(tenantRes, depOfTenant, resOfOwner));

        // Ejecutar revocación
        residentService.terminateResident(tenantResId, propertyId, ownerUserId);

        // Verificaciones:
        // 1. Arrendatario pasa a INACTIVE
        assertEquals(ResidentStatus.INACTIVE, tenantRes.getStatus());
        assertEquals("TERMINATED_BY_PROPIETARIO", tenantRes.getEndReason());

        // 2. Residente del Arrendatario pasa a INACTIVE por cascada
        assertEquals(ResidentStatus.INACTIVE, depOfTenant.getStatus());
        assertEquals("CASCADED_TENANT_TERMINATION", depOfTenant.getEndReason());

        // 3. Residente del Propietario PERMANECE ACTIVE
        assertEquals(ResidentStatus.ACTIVE, resOfOwner.getStatus());
    }

    // ─── TESTS DE REACTIVACIÓN Y 1 APARTAMENTO ACTIVO POR RESIDENTE ────────────────

    @Test
    @DisplayName("Propietario puede reactivar a un residente inactivo si no tiene otra unidad activa")
    void ownerCanReactivateInactiveResident() {
        UUID resId = UUID.randomUUID();
        Resident inactiveRes = Resident.builder()
                .property(property)
                .type("Residente")
                .status(ResidentStatus.INACTIVE)
                .email("carlos@example.com")
                .build();
        inactiveRes.setId(resId);
        inactiveRes.setTenantId(tenantId);

        when(residentRepository.findByIdAndTenantId(resId, tenantId)).thenReturn(Optional.of(inactiveRes));
        when(userRepository.findById(ownerUserId)).thenReturn(Optional.of(ownerUser));
        when(residentRepository.findByUserIdAndTenantId(ownerUserId, tenantId)).thenReturn(List.of(ownerRes));
        when(residentRepository.findActiveNonOwnerResidences(null, "carlos@example.com", ResidentStatus.ACTIVE))
                .thenReturn(List.of());

        residentService.reactivateResident(resId, propertyId, ownerUserId);

        assertEquals(ResidentStatus.ACTIVE, inactiveRes.getStatus());
        assertNull(inactiveRes.getEndedAt());
        assertNull(inactiveRes.getEndedBy());
        assertNull(inactiveRes.getEndReason());
        assertEquals(ownerUser, inactiveRes.getGrantedBy());
    }

    @Test
    @DisplayName("No se puede reactivar a un residente si ya está activo en otra unidad")
    void cannotReactivateIfResidentAlreadyActiveInAnotherProperty() {
        UUID resId = UUID.randomUUID();
        Resident inactiveRes = Resident.builder()
                .property(property)
                .type("Residente")
                .status(ResidentStatus.INACTIVE)
                .email("carlos@example.com")
                .build();
        inactiveRes.setId(resId);
        inactiveRes.setTenantId(tenantId);

        Property otherProperty = Property.builder().name("Apto 202").build();
        otherProperty.setId(UUID.randomUUID());

        Resident activeInOtherUnit = Resident.builder()
                .property(otherProperty)
                .type("Residente")
                .status(ResidentStatus.ACTIVE)
                .email("carlos@example.com")
                .build();
        // P0-C: la unidad solo se nombra si la residencia que bloquea es de la misma comunidad (aqui lo es).
        activeInOtherUnit.setTenantId(tenantId);

        when(residentRepository.findByIdAndTenantId(resId, tenantId)).thenReturn(Optional.of(inactiveRes));
        when(userRepository.findById(ownerUserId)).thenReturn(Optional.of(ownerUser));
        when(residentRepository.findByUserIdAndTenantId(ownerUserId, tenantId)).thenReturn(List.of(ownerRes));
        when(residentRepository.findActiveNonOwnerResidences(null, "carlos@example.com", ResidentStatus.ACTIVE))
                .thenReturn(List.of(activeInOtherUnit));

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                residentService.reactivateResident(resId, propertyId, ownerUserId));

        assertEquals("ALREADY_ACTIVE_IN_ANOTHER_PROPERTY", ex.getCode());
        assertTrue(ex.getMessage().contains("Apto 202"));
    }

    @Test
    @DisplayName("P0-C (R5): si la residencia que bloquea es de OTRA comunidad, el mensaje no revela la unidad")
    void reactivationConflictInAnotherCommunityDoesNotRevealUnit() {
        UUID resId = UUID.randomUUID();
        Resident inactiveRes = Resident.builder()
                .property(property)
                .type("Residente")
                .status(ResidentStatus.INACTIVE)
                .email("carlos@example.com")
                .build();
        inactiveRes.setId(resId);
        inactiveRes.setTenantId(tenantId);

        Property foreignProperty = Property.builder().name("Torre Secreta 909").number("909").build();
        foreignProperty.setId(UUID.randomUUID());
        foreignProperty.setTenantId(UUID.randomUUID());
        Resident activeElsewhere = Resident.builder()
                .property(foreignProperty)
                .type("Arrendatario")
                .status(ResidentStatus.ACTIVE)
                .email("carlos@example.com")
                .build();
        activeElsewhere.setTenantId(foreignProperty.getTenantId());

        when(residentRepository.findByIdAndTenantId(resId, tenantId)).thenReturn(Optional.of(inactiveRes));
        when(userRepository.findById(ownerUserId)).thenReturn(Optional.of(ownerUser));
        when(residentRepository.findByUserIdAndTenantId(ownerUserId, tenantId)).thenReturn(List.of(ownerRes));
        when(residentRepository.findActiveNonOwnerResidences(null, "carlos@example.com", ResidentStatus.ACTIVE))
                .thenReturn(List.of(activeElsewhere));

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                residentService.reactivateResident(resId, propertyId, ownerUserId));

        assertEquals("ALREADY_ACTIVE_IN_ANOTHER_PROPERTY", ex.getCode());
        assertFalse(ex.getMessage().contains("Torre Secreta"));
        assertFalse(ex.getMessage().contains("909"));
        assertTrue(ex.getMessage().contains("otra unidad"));
        assertEquals(ResidentStatus.INACTIVE, inactiveRes.getStatus());
    }

    @Test
    @DisplayName("No se puede reactivar a un arrendatario si la unidad ya tiene otro arrendatario activo")
    void cannotReactivateSecondTenantIfPropertyHasActiveTenant() {
        UUID tenantResId = UUID.randomUUID();
        Resident inactiveTenant = Resident.builder()
                .property(property)
                .type("Arrendatario")
                .status(ResidentStatus.INACTIVE)
                .email("tenant2@example.com")
                .build();
        inactiveTenant.setId(tenantResId);
        inactiveTenant.setTenantId(tenantId);

        when(residentRepository.findByIdAndTenantId(tenantResId, tenantId)).thenReturn(Optional.of(inactiveTenant));
        when(userRepository.findById(ownerUserId)).thenReturn(Optional.of(ownerUser));
        when(residentRepository.findByUserIdAndTenantId(ownerUserId, tenantId)).thenReturn(List.of(ownerRes));
        when(residentRepository.existsByPropertyIdAndTypeIgnoreCaseAndStatus(propertyId, "Arrendatario", ResidentStatus.ACTIVE))
                .thenReturn(true);

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                residentService.reactivateResident(tenantResId, propertyId, ownerUserId));

        assertEquals("SECOND_ACTIVE_TENANT", ex.getCode());
    }
}