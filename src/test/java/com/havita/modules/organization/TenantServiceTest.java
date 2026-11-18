package com.havita.modules.organization;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.audit.application.AuditService;
import com.havita.modules.identity.application.GlobalEmailValidationService;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.api.dto.TenantResponse;
import com.havita.modules.organization.api.dto.UpdateTenantRequest;
import com.havita.modules.organization.application.TenantService;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.domain.TenantStatus;
import com.havita.modules.organization.infrastructure.OrganizationRepository;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.subscription.infrastructure.SaaSPlanRepository;
import com.havita.modules.subscription.infrastructure.SaaSSubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private PropertyRepository propertyRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private SaaSPlanRepository saasPlanRepository;
    @Mock
    private SaaSSubscriptionRepository saasSubscriptionRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private GlobalEmailValidationService globalEmailValidationService;

    @Mock private com.havita.security.AccessValidator accessValidator; // SEC-9 P1: comprobacion transaccional (FOR SHARE)

    @InjectMocks
    private TenantService tenantService;

    private UUID tenantId;
    private Tenant existingTenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        existingTenant = Tenant.builder()
                .name("Condominio Las Palmas")
                .slug("las-palmas")
                .subdomain("las-palmas")
                .status(TenantStatus.ACTIVE)
                .build();
        existingTenant.setId(tenantId);
        lenient().when(tenantRepository.save(any(Tenant.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        com.havita.security.TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(String role) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        // SEC-9: un SUPER_ADMIN global lleva SCOPE_GLOBAL; la administracion de comunidad actua en su propia comunidad.
        List<SimpleGrantedAuthority> authorities = "ROLE_SUPER_ADMIN".equals(role)
                ? List.of(new SimpleGrantedAuthority(role), new SimpleGrantedAuthority("SCOPE_GLOBAL"))
                : List.of(new SimpleGrantedAuthority(role));
        if (!"ROLE_SUPER_ADMIN".equals(role)) {
            com.havita.security.TenantContextHolder.setTenantId(tenantId);
        }
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(),
                "credentials",
                authorities
        );
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void shouldThrowAccessDeniedWhenCommunityAdminTriesToChangeSubdomain() {
        setSecurityContext("ROLE_COMMUNITY_ADMIN");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(existingTenant));

        UpdateTenantRequest request = new UpdateTenantRequest(
                "Condominio Las Palmas Editado",
                "nuevo-subdominio",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                tenantService.update(tenantId, request)
        );

        assertTrue(ex.getMessage().contains("Solo un SUPER_ADMIN"));
        assertEquals("las-palmas", existingTenant.getSubdomain());
    }

    @Test
    void shouldAllowCommunityAdminWhenSubdomainIsUnchanged() {
        setSecurityContext("ROLE_COMMUNITY_ADMIN");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(existingTenant));

        UpdateTenantRequest request = new UpdateTenantRequest(
                "Condominio Las Palmas Renombrado",
                "las-palmas", // same subdomain
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        TenantResponse response = tenantService.update(tenantId, request);

        assertNotNull(response);
        assertEquals("Condominio Las Palmas Renombrado", existingTenant.getName());
        assertEquals("las-palmas", existingTenant.getSubdomain());
    }

    @Test
    void shouldAllowCommunityAdminWhenSubdomainIsNull() {
        setSecurityContext("ROLE_COMMUNITY_ADMIN");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(existingTenant));

        UpdateTenantRequest request = new UpdateTenantRequest(
                "Condominio Las Palmas Renombrado",
                null, // subdomain omitted
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        TenantResponse response = tenantService.update(tenantId, request);

        assertNotNull(response);
        assertEquals("Condominio Las Palmas Renombrado", existingTenant.getName());
        assertEquals("las-palmas", existingTenant.getSubdomain());
    }

    @Test
    void shouldAllowSuperAdminToUpdateSubdomain() {
        setSecurityContext("ROLE_SUPER_ADMIN");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(existingTenant));
        when(tenantRepository.existsBySubdomain("nuevo-subdominio")).thenReturn(false);

        UpdateTenantRequest request = new UpdateTenantRequest(
                null,
                "nuevo-subdominio",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        TenantResponse response = tenantService.update(tenantId, request);

        assertNotNull(response);
        assertEquals("nuevo-subdominio", existingTenant.getSubdomain());
    }

    @Test
    void shouldThrowConflictWhenSuperAdminUpdatesSubdomainToExistingOne() {
        setSecurityContext("ROLE_SUPER_ADMIN");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(existingTenant));
        when(tenantRepository.existsBySubdomain("subdominio-ocupado")).thenReturn(true);

        UpdateTenantRequest request = new UpdateTenantRequest(
                null,
                "subdominio-ocupado",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class, () ->
                tenantService.update(tenantId, request)
        );

        assertEquals("TENANT_SUBDOMAIN_EXISTS", ex.getCode());
    }

    @Test
    void shouldNotFindOrUpdateAnotherTenantAsCommunityAdmin() {
        // SEC-9: la administracion de comunidad no ve ni modifica otra comunidad (404, sin cambios).
        setSecurityContext("ROLE_COMMUNITY_ADMIN");
        UUID otherId = UUID.randomUUID();
        com.havita.modules.organization.domain.Tenant other = new com.havita.modules.organization.domain.Tenant();
        other.setId(otherId);
        other.setName("Otra comunidad");
        when(tenantRepository.findById(otherId)).thenReturn(Optional.of(other));

        UpdateTenantRequest request = new UpdateTenantRequest(
                "Renombrada", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        assertThrows(com.havita.common.exception.ResourceNotFoundException.class, () -> tenantService.update(otherId, request));
        assertThrows(com.havita.common.exception.ResourceNotFoundException.class, () -> tenantService.findById(otherId));
        assertEquals("Otra comunidad", other.getName());
    }
}
