package com.havita.security;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SEC-2: reglas de lectura de una unidad para el rol RESIDENT. */
@ExtendWith(MockitoExtension.class)
class AccessValidatorTest {

    @Mock private ResidentRepository residentRepository;
    @Mock private MembershipRepository membershipRepository;

    @InjectMocks private AccessValidator validator;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private Property home;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_RESIDENT"))));
        home = new Property();
        home.setId(UUID.randomUUID());
        home.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void membership(MembershipStatus status) {
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantId))
                .thenReturn(Optional.of(Membership.builder().tenantId(tenantId).status(status).build()));
    }

    private Resident relation(Property property, ResidentStatus status) {
        User u = User.builder().build();
        u.setId(userId);
        Resident r = Resident.builder().property(property).user(u).status(status).type("Propietario").build();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        return r;
    }

    @Test
    @DisplayName("Relacion ACTIVE con la unidad y membresia ACTIVE dan acceso")
    void activeRelationGrantsAccess() {
        membership(MembershipStatus.ACTIVE);
        when(residentRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(List.of(relation(home, ResidentStatus.ACTIVE)));
        assertTrue(validator.isActiveResidentOfProperty(home.getId()));
    }

    @Test
    @DisplayName("Otra unidad de la misma comunidad no da acceso")
    void otherUnitIsDenied() {
        membership(MembershipStatus.ACTIVE);
        when(residentRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(List.of(relation(home, ResidentStatus.ACTIVE)));
        assertFalse(validator.isActiveResidentOfProperty(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Relacion INACTIVE no da acceso")
    void inactiveRelationIsDenied() {
        membership(MembershipStatus.ACTIVE);
        when(residentRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(List.of(relation(home, ResidentStatus.INACTIVE)));
        assertFalse(validator.isActiveResidentOfProperty(home.getId()));
    }

    @Test
    @DisplayName("Membresia INACTIVE o ausente no da acceso y no consulta el censo")
    void inactiveOrMissingMembershipIsDenied() {
        membership(MembershipStatus.INACTIVE);
        assertFalse(validator.isActiveResidentOfProperty(home.getId()));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(Optional.empty());
        assertFalse(validator.isActiveResidentOfProperty(home.getId()));
        verify(residentRepository, never()).findByUserIdAndTenantId(any(), any());
    }

    @Test
    @DisplayName("Sin autenticacion o sin tenant en contexto no hay acceso")
    void missingContextIsDenied() {
        SecurityContextHolder.clearContext();
        assertFalse(validator.isActiveResidentOfProperty(home.getId()));
        TenantContextHolder.clear();
        assertFalse(validator.canReadResident(UUID.randomUUID()));
        verifyNoInteractions(residentRepository, membershipRepository);
    }

    @Test
    @DisplayName("SEC-3 activePropertyIds: solo las unidades con relacion ACTIVE y nada si la membresia no esta ACTIVE")
    void activePropertyIdsOnlyActiveRelations() {
        Property other = new Property();
        other.setId(UUID.randomUUID());
        membership(MembershipStatus.ACTIVE);
        when(residentRepository.findByUserIdAndTenantId(userId, tenantId))
                .thenReturn(List.of(relation(home, ResidentStatus.ACTIVE), relation(other, ResidentStatus.INACTIVE)));
        assertEquals(java.util.Set.of(home.getId()), validator.activePropertyIds());

        membership(MembershipStatus.INACTIVE);
        assertTrue(validator.activePropertyIds().isEmpty());
    }

    @Test
    @DisplayName("SEC-3 isResidentCaller: distingue el rol RESIDENT de los administrativos")
    void residentCallerDetection() {
        assertTrue(validator.isResidentCaller());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_COMMUNITY_ADMIN"))));
        assertFalse(validator.isResidentCaller());
        SecurityContextHolder.clearContext();
        assertFalse(validator.isResidentCaller());
        assertNull(validator.currentUserId());
    }

    @Test
    @DisplayName("canReadResident: un registro de otra comunidad o inexistente no da acceso")
    void foreignResidentIsDenied() {
        UUID foreign = UUID.randomUUID();
        when(residentRepository.findByIdAndTenantId(foreign, tenantId)).thenReturn(Optional.empty());
        assertFalse(validator.canReadResident(foreign));
    }

    @Test
    @DisplayName("canReadResident: un familiar de su propia unidad es legible")
    void householdMemberIsReadable() {
        Resident relative = relation(home, ResidentStatus.ACTIVE);
        relative.setUser(null);
        when(residentRepository.findByIdAndTenantId(relative.getId(), tenantId)).thenReturn(Optional.of(relative));
        membership(MembershipStatus.ACTIVE);
        when(residentRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(List.of(relation(home, ResidentStatus.ACTIVE)));
        assertTrue(validator.canReadResident(relative.getId()));
    }
}
