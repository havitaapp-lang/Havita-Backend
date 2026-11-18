package com.havita.modules.identity;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.identity.api.dto.LoginRequest;
import com.havita.modules.identity.application.AuthService;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.RefreshTokenRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceLoginTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private ResidentRepository residentRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    /** Fase 2 / E1: el login obtiene las unidades del resolvedor central (mismo orden y criterio que el portal). */
    @Mock
    private com.havita.security.ResidentUnitResolver unitResolver;

    // P8-B: evento de revocacion de sesiones (Web Push).
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AuthService authService;

    private com.havita.security.ResidentUnitResolver.UnitOption unitOption(Property p) {
        return new com.havita.security.ResidentUnitResolver.UnitOption(p.getId(), tenantId, p.getNumber(), p.getNumber(),
                "Apartamento", "OCCUPIED", null, null, null, null, List.of("Residente"), false, p.getNumber(), p.getNumber(),
                UUID.randomUUID());
    }

    private User user;
    private UUID tenantId;
    private Membership residentMembership;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        user = User.builder()
                .email("revocado@ejemplo.com")
                .passwordHash("hashedPass")
                .fullName("Usuario Revocado")
                .build();
        user.setId(UUID.randomUUID());

        residentMembership = Membership.builder()
                .user(user)
                .tenantId(tenantId)
                .role(RoleType.RESIDENT)
                .build();

        // SEC-9 (P2): el login solo considera membresias de comunidades existentes; la comunidad de la prueba existe.
        com.havita.modules.organization.domain.Tenant tenant = com.havita.modules.organization.domain.Tenant.builder()
                .name("Comunidad de prueba").slug("comunidad-prueba").subdomain("comunidadprueba").build();
        tenant.setId(tenantId);
        lenient().when(tenantRepository.findAllById(any())).thenReturn(List.of(tenant));
    }

    @Test
    @DisplayName("Debe rechazar el login con ACCESS_REVOKED si el residente tiene todas sus residencias INACTIVE")
    void login_whenResidentRevoked_throwsAccessRevoked() {
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass123", "hashedPass")).thenReturn(true);
        when(membershipRepository.findByUserId(user.getId())).thenReturn(List.of(residentMembership));

        // Solo relaciones INACTIVE: sin unidades activas y con historial.
        when(unitResolver.activeUnits(user.getId(), user.getEmail(), tenantId)).thenReturn(List.of());
        when(unitResolver.hasOnlyInactiveUnits(user.getId(), user.getEmail(), tenantId)).thenReturn(true);

        LoginRequest request = new LoginRequest(user.getEmail(), "pass123", tenantId);

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                authService.login(request, null));

        assertEquals("ACCESS_REVOKED", ex.getCode());
        assertTrue(ex.getMessage().contains("revocado"));
    }

    @Test
    @DisplayName("Debe rechazar el login con NO_ACTIVE_RESIDENCE si el usuario no tiene residencias registradas")
    void login_whenNoResidences_throwsNoActiveResidence() {
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass123", "hashedPass")).thenReturn(true);
        when(membershipRepository.findByUserId(user.getId())).thenReturn(List.of(residentMembership));

        when(unitResolver.activeUnits(user.getId(), user.getEmail(), tenantId)).thenReturn(List.of());
        when(unitResolver.hasOnlyInactiveUnits(user.getId(), user.getEmail(), tenantId)).thenReturn(false);

        LoginRequest request = new LoginRequest(user.getEmail(), "pass123", tenantId);

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                authService.login(request, null));

        assertEquals("NO_ACTIVE_RESIDENCE", ex.getCode());
    }

    @Test
    @DisplayName("Debe permitir el login exitoso si el residente tiene una residencia ACTIVE")
    void login_whenResidentActive_succeeds() {
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass123", "hashedPass")).thenReturn(true);
        when(membershipRepository.findByUserId(user.getId())).thenReturn(List.of(residentMembership));

        Property property = Property.builder()
                .number("Apto 301")
                .build();
        property.setId(UUID.randomUUID());

        when(unitResolver.activeUnits(user.getId(), user.getEmail(), tenantId)).thenReturn(List.of(unitOption(property)));
        when(jwtService.generateAccessToken(any(), any(), any(), any())).thenReturn("mock.jwt.token");

        LoginRequest request = new LoginRequest(user.getEmail(), "pass123", tenantId);

        var response = authService.login(request, null);

        assertNotNull(response);
        assertEquals("mock.jwt.token", response.accessToken());
        assertEquals("Apto 301", response.user().propertyUnit());
    }

    @Test
    @DisplayName("Fase 2 / E1: con varias unidades el login no elige ninguna (residentPropertyId null)")
    void login_whenSeveralUnits_doesNotPickOne() {
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass123", "hashedPass")).thenReturn(true);
        when(membershipRepository.findByUserId(user.getId())).thenReturn(List.of(residentMembership));
        Property p1 = Property.builder().number("Apto 101").build();
        p1.setId(UUID.randomUUID());
        Property p2 = Property.builder().number("Apto 102").build();
        p2.setId(UUID.randomUUID());
        when(unitResolver.activeUnits(user.getId(), user.getEmail(), tenantId)).thenReturn(List.of(unitOption(p1), unitOption(p2)));
        when(jwtService.generateAccessToken(any(), any(), any(), any())).thenReturn("mock.jwt.token");

        var response = authService.login(new LoginRequest(user.getEmail(), "pass123", tenantId), null);

        assertEquals("mock.jwt.token", response.accessToken());
        assertNull(response.user().residentPropertyId());
        assertNull(response.user().propertyUnit());
    }
}
