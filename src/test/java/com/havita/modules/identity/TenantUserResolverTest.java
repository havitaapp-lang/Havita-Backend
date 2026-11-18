package com.havita.modules.identity;

import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.application.TenantUserResolver;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantUserResolverTest {

    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;

    @InjectMocks private TenantUserResolver resolver;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("Usuario con membresia en el tenant se resuelve")
    void resolvesUserWithMembershipInTenant() {
        User user = User.builder().email("a@example.test").fullName("Usuario A").build();
        user.setId(userId);
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantA)).thenReturn(Optional.of(new Membership()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertSame(user, resolver.requireUserInTenant(userId, tenantA));
    }

    @Test
    @DisplayName("Usuario de otra comunidad responde 404 sin consultar la cuenta ni crear membresias")
    void rejectsUserWithoutMembershipInTenant() {
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantA)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> resolver.requireUserInTenant(userId, tenantA));

        verify(userRepository, never()).findById(any());
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("Identificadores nulos responden 404")
    void rejectsNullIdentifiers() {
        assertThrows(ResourceNotFoundException.class, () -> resolver.requireUserInTenant(null, tenantA));
        assertThrows(ResourceNotFoundException.class, () -> resolver.requireUserInTenant(userId, null));
        verifyNoInteractions(userRepository);
    }
}
