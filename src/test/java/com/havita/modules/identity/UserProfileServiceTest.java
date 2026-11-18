package com.havita.modules.identity;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.identity.api.dto.ChangePasswordRequest;
import com.havita.modules.identity.api.dto.UpdateProfileRequest;
import com.havita.modules.identity.api.dto.UserProfileResponse;
import com.havita.modules.identity.application.UserProfileService;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.identity.infrastructure.RefreshTokenRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private com.havita.modules.resident.infrastructure.ResidentRepository residentRepository;

    // Usamos una instancia REAL de BCrypt para las pruebas de contraseña
    private final PasswordEncoder realEncoder = new BCryptPasswordEncoder();

    private UserProfileService service;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        service = new UserProfileService(userRepository, refreshTokenRepository, realEncoder, residentRepository);
        userId = UUID.randomUUID();
        user = new User();
        user.setFullName("Juan Sebastián");
        user.setEmail("juan@test.com");
        user.setPhone("3001234567");
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(realEncoder.encode("Password123!"));
    }

    @Test
    @DisplayName("getProfile: retorna datos sin passwordHash")
    void getProfile_returnsDataWithoutPasswordHash() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        UserProfileResponse response = service.getProfile(userId);
        assertNotNull(response);
        assertEquals("Juan Sebastián", response.fullName());
        assertEquals("juan@test.com", response.email());
        // Verificar que el record no tiene campo passwordHash
        // (UserProfileResponse no tiene ese campo por diseño)
    }

    @Test
    @DisplayName("updateProfile: actualiza nombre y teléfono")
    void updateProfile_updatesNameAndPhone() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        UpdateProfileRequest req = new UpdateProfileRequest("Nuevo Nombre", "3009876543");
        UserProfileResponse response = service.updateProfile(userId, req);
        assertEquals("Nuevo Nombre", response.fullName());
        assertEquals("3009876543", response.phone());
    }

    @Test
    @DisplayName("changePassword: contraseñas nuevas no coinciden → PASSWORD_MISMATCH")
    void changePassword_mismatch_throws() {
        ChangePasswordRequest req = new ChangePasswordRequest("Password123!", "NewPass456!", "Diferente789!");
        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class,
                () -> service.changePassword(userId, req));
        assertEquals("PASSWORD_MISMATCH", ex.getCode());
    }

    @Test
    @DisplayName("changePassword: nueva igual a la actual → SAME_PASSWORD")
    void changePassword_sameAsCurrentPassword_throws() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        ChangePasswordRequest req = new ChangePasswordRequest("Password123!", "Password123!", "Password123!");
        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class,
                () -> service.changePassword(userId, req));
        assertEquals("SAME_PASSWORD", ex.getCode());
    }

    @Test
    @DisplayName("changePassword: contraseña actual incorrecta → WRONG_CURRENT_PASSWORD")
    void changePassword_wrongCurrentPassword_throws() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        ChangePasswordRequest req = new ChangePasswordRequest("Incorrecta!", "NuevaPass456!", "NuevaPass456!");
        BusinessRuleConflictException ex = assertThrows(BusinessRuleConflictException.class,
                () -> service.changePassword(userId, req));
        assertEquals("WRONG_CURRENT_PASSWORD", ex.getCode());
    }

    @Test
    @DisplayName("changePassword: exitoso → revoca todos los refresh tokens")
    void changePassword_success_revokesAllTokens() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        doNothing().when(refreshTokenRepository).revokeAllByUserId(any());
        ChangePasswordRequest req = new ChangePasswordRequest("Password123!", "NuevaClave456!", "NuevaClave456!");
        assertDoesNotThrow(() -> service.changePassword(userId, req));
        verify(refreshTokenRepository).revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("changePassword: el nuevo passwordHash nunca está en texto plano")
    void changePassword_newPasswordIsHashed() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User savedUser = invocation.getArgument(0);
            // El hash guardado nunca debe ser igual a la contraseña en texto plano
            assertNotEquals("NuevaClave456!", savedUser.getPasswordHash());
            assertTrue(realEncoder.matches("NuevaClave456!", savedUser.getPasswordHash()));
            return savedUser;
        });
        doNothing().when(refreshTokenRepository).revokeAllByUserId(any());
        ChangePasswordRequest req = new ChangePasswordRequest("Password123!", "NuevaClave456!", "NuevaClave456!");
        service.changePassword(userId, req);
        verify(userRepository).save(any(User.class));
    }
}
