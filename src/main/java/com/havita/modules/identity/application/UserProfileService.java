package com.havita.modules.identity.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.api.dto.ChangePasswordRequest;
import com.havita.modules.identity.api.dto.UpdateProfileRequest;
import com.havita.modules.identity.api.dto.UserProfileResponse;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.RefreshTokenRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ResidentRepository residentRepository;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = findUser(userId);
        return toResponse(user);
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = findUser(userId);
        String newFullName = request.fullName().trim();
        user.setFullName(newFullName);
        String newPhone = null;
        if (request.phone() != null) {
            String phoneClean = request.phone().trim();
            newPhone = phoneClean.isEmpty() ? null : phoneClean;
            user.setPhone(newPhone);
        }
        User saved = userRepository.save(user);

        // Sincronizar todos los registros de la tabla 'residents' vinculados a este usuario
        List<Resident> residentRecords = residentRepository.findAllByUserIdOrEmail(userId, user.getEmail());
        for (Resident r : residentRecords) {
            r.setName(newFullName);
            if (request.phone() != null) {
                r.setPhone(newPhone);
            }
            if (r.getUser() == null) {
                r.setUser(saved);
            }
        }
        if (!residentRecords.isEmpty()) {
            residentRepository.saveAll(residentRecords);
            log.info("Sincronizados {} registros en tabla residents para usuario {}", residentRecords.size(), userId);
        }

        log.info("Perfil actualizado para usuario {}", userId);
        return toResponse(saved);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        // 1. Validar que las nuevas contraseñas coincidan
        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw new BusinessRuleConflictException(
                    "PASSWORD_MISMATCH",
                    "Las contraseñas nuevas no coinciden.");
        }

        // 2. Validar que la nueva contraseña no sea igual a la actual (en texto plano contra hash)
        User user = findUser(userId);
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessRuleConflictException(
                    "SAME_PASSWORD",
                    "La nueva contraseña no puede ser igual a la contraseña actual.");
        }

        // 3. Validar contraseña actual
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleConflictException(
                    "WRONG_CURRENT_PASSWORD",
                    "La contraseña actual no es correcta.");
        }

        // 4. Actualizar contraseña con hash BCrypt
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // 5. Revocar todos los refresh tokens del usuario (fuerza re-login)
        refreshTokenRepository.revokeAllByUserId(userId);

        log.info("Contraseña cambiada y sesiones revocadas para usuario {}", userId);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", userId));
    }

    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getAvatarUrl(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
