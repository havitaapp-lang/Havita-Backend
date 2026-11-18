package com.havita.modules.identity.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.identity.api.dto.AuthResponse;
import com.havita.modules.identity.api.dto.LoginRequest;
import com.havita.modules.identity.api.dto.RefreshTokenRequest;
import com.havita.modules.identity.application.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Endpoints de autenticación, login, refresh tokens y logout")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "Inicio de sesión para todos los roles (Super Admin, Admin, Portería, Residente)")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        AuthResponse response = authService.login(request, httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(response, "Inicio de sesión exitoso"));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotación y renovación de Access Token mediante Refresh Token")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refresh(request);
        return ResponseEntity.ok(ApiResponse.ok(response, "Token renovado exitosamente"));
    }

    @PostMapping("/logout")
    @Operation(summary = "Cierre de sesión y revocación de tokens activos")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal String userIdStr) {
        if (userIdStr != null && !userIdStr.isBlank()) {
            authService.logout(UUID.fromString(userIdStr));
        }
        return ResponseEntity.ok(ApiResponse.ok(null, "Sesión finalizada exitosamente"));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Solicitar recuperacion de contrasena")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody com.havita.modules.identity.api.dto.ForgotPasswordRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        authService.forgotPassword(request, httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(null, "Si el correo existe, se han enviado las instrucciones de recuperacion."));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Restablecer contrasena con token")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody com.havita.modules.identity.api.dto.ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok(null, "Contrasena restablecida exitosamente."));
    }
}

