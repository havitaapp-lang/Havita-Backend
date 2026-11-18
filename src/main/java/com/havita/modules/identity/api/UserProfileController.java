package com.havita.modules.identity.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.identity.api.dto.ChangePasswordRequest;
import com.havita.modules.identity.api.dto.UpdateProfileRequest;
import com.havita.modules.identity.api.dto.UserProfileResponse;
import com.havita.modules.identity.application.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
@Tag(name = "User Profile", description = "Gestión de perfil personal del usuario autenticado")
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping
    @Operation(summary = "Obtener perfil del usuario autenticado")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile(
            @AuthenticationPrincipal String userIdStr) {
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(ApiResponse.ok(userProfileService.getProfile(userId)));
    }

    @PatchMapping
    @Operation(summary = "Actualizar información personal del usuario autenticado")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(ApiResponse.ok(
                userProfileService.updateProfile(userId, request),
                "Perfil actualizado exitosamente"));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Cambiar contraseña del usuario autenticado")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody ChangePasswordRequest request) {
        UUID userId = UUID.fromString(userIdStr);
        userProfileService.changePassword(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(null, "Contraseña actualizada. Por favor inicia sesión nuevamente."));
    }
}
