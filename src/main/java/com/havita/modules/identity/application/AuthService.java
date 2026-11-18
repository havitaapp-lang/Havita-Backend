package com.havita.modules.identity.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.api.dto.AuthResponse;
import com.havita.modules.identity.api.dto.LoginRequest;
import com.havita.modules.identity.api.dto.RefreshTokenRequest;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RefreshToken;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.RefreshTokenRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final com.havita.modules.resident.infrastructure.ResidentRepository residentRepository;
    private final com.havita.modules.identity.infrastructure.PasswordResetTokenRepository passwordResetTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final com.havita.common.email.EmailService emailService;
    private final com.havita.security.ResidentUnitResolver unitResolver;
    // P8-B: al revocar todas las sesiones se avisa a quien mantenga canales con los dispositivos (Web Push).
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Membresias ACTIVE de comunidades existentes, y esas comunidades indexadas por id. */
    private record ValidMemberships(List<Membership> memberships, java.util.Map<UUID, Tenant> tenants) {}

    /**
     * SEC-9 (P2, SEC-9-6): membresias ACTIVE del usuario, en el orden de siempre, descartando las de comunidades
     * inexistentes (no hay clave foranea memberships.tenant_id -> tenants). No modifica ni borra ninguna membresia.
     * Las comunidades se cargan en una sola consulta, en lugar de una por membresia.
     */
    private ValidMemberships validActiveMemberships(UUID userId) {
        List<Membership> active = membershipRepository.findByUserId(userId).stream()
                .filter(m -> m.getStatus() == com.havita.modules.identity.domain.MembershipStatus.ACTIVE)
                .toList();
        java.util.Set<UUID> tenantIds = active.stream().map(Membership::getTenantId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        java.util.Map<UUID, Tenant> tenants = tenantIds.isEmpty() ? java.util.Map.of()
                : tenantRepository.findAllById(tenantIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Tenant::getId, t -> t));
        List<Membership> valid = active.stream()
                .filter(m -> m.getTenantId() == null || tenants.containsKey(m.getTenantId()))
                .toList();
        return new ValidMemberships(valid, tenants);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        // SEC-9 (P2, SEC-9-8): los intentos fallidos se registran sin el correo ni la contrasena. La trazabilidad la dan
        // el motivo, el id de la cuenta cuando existe y el traceId de la peticion (CorrelationIdFilter, MDC).
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> {
                    log.warn("Inicio de sesion fallido: motivo=UNKNOWN_ACCOUNT");
                    return new BusinessRuleConflictException("INVALID_CREDENTIALS", "Credenciales de acceso no válidas.");
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Inicio de sesion fallido: motivo=BAD_CREDENTIALS userId={}", user.getId());
            throw new BusinessRuleConflictException("INVALID_CREDENTIALS", "Credenciales de acceso no válidas.");
        }

        // SEC-9: una cuenta INACTIVE no obtiene sesion, y solo las membresias ACTIVE entran en el token. Aun asi, la
        // autorizacion de cada peticion se decide contra la base (AuthorizationResolver), no con este contenido.
        if (user.getStatus() != com.havita.modules.identity.domain.UserStatus.ACTIVE) {
            log.warn("Inicio de sesion rechazado: motivo=ACCOUNT_INACTIVE userId={}", user.getId());
            throw new BusinessRuleConflictException("ACCOUNT_INACTIVE", "La cuenta no está activa. Contacta a la administración.");
        }
        ValidMemberships valid = validActiveMemberships(user.getId());
        List<Membership> memberships = valid.memberships();

        Membership activeMembership = null;
        if (request.tenantId() != null) {
            activeMembership = memberships.stream()
                    .filter(m -> {
                        if (m.getRole() == RoleType.SUPER_ADMIN && m.getTenantId() == null) return true;
                        return m.getTenantId() != null && m.getTenantId().equals(request.tenantId());
                    })
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleConflictException("TENANT_MEMBERSHIP_REQUIRED", "El usuario no pertenece a la copropiedad especificada."));
        } else if (!memberships.isEmpty()) {
            activeMembership = memberships.get(0);
        }

        String role = activeMembership != null ? activeMembership.getRole().name() : RoleType.RESIDENT.name();
        UUID tenantId = activeMembership != null ? activeMembership.getTenantId() : null;

        // Validar acceso activo para roles de tipo RESIDENT: si el acceso fue revocado (o no tiene residencia activa), bloquear login
        String propertyUnit = null;
        UUID residentPropertyId = null;

        if (activeMembership != null && activeMembership.getRole() == RoleType.RESIDENT) {
            if (tenantId != null) {
                // Fase 2 / E1: mismas unidades y orden que el resolvedor central. Con una sola unidad se devuelve esa;
                // con varias no se elige ninguna (D1): el cliente debe seleccionar una (antes: findFirst() sin orden).
                List<com.havita.security.ResidentUnitResolver.UnitOption> units =
                        unitResolver.activeUnits(user.getId(), user.getEmail(), tenantId);

                if (units.isEmpty()) {
                    if (unitResolver.hasOnlyInactiveUnits(user.getId(), user.getEmail(), tenantId)) {
                        throw new BusinessRuleConflictException("ACCESS_REVOKED",
                                "Tu acceso a esta copropiedad ha sido revocado. Contacta al propietario o a la administración para reactivar tu cuenta.");
                    } else {
                        throw new BusinessRuleConflictException("NO_ACTIVE_RESIDENCE",
                                "No tienes una residencia activa registrada en esta copropiedad.");
                    }
                }

                if (units.size() == 1) {
                    propertyUnit = units.get(0).number();
                    residentPropertyId = units.get(0).propertyId();
                }
            } else {
                List<com.havita.modules.resident.domain.Resident> allRes =
                        residentRepository.findAllByUserIdOrEmail(user.getId(), user.getEmail());

                com.havita.modules.resident.domain.Resident activeRes = allRes.stream()
                        .filter(r -> r.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE)
                        .findFirst()
                        .orElse(null);

                if (activeRes == null) {
                    if (!allRes.isEmpty()) {
                        throw new BusinessRuleConflictException("ACCESS_REVOKED",
                                "Tu acceso a la copropiedad ha sido revocado. Contacta al propietario o a la administración para reactivar tu cuenta.");
                    } else {
                        throw new BusinessRuleConflictException("NO_ACTIVE_RESIDENCE",
                                "No cuentas con una residencia activa registrada en el sistema.");
                    }
                }

                // Fase 2 / E1: membresia RESIDENT sin comunidad (anomala): solo se deduce comunidad y unidad si hay una
                // unica residencia ACTIVE; con varias no se elige ninguna arbitrariamente.
                long activeCount = allRes.stream()
                        .filter(r -> r.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE)
                        .count();
                if (activeCount == 1) {
                    tenantId = activeRes.getTenantId();
                    if (activeRes.getProperty() != null) {
                        propertyUnit = activeRes.getProperty().getNumber();
                        residentPropertyId = activeRes.getProperty().getId();
                    }
                }
            }
        }

        
        List<AuthResponse.MembershipDto> membershipDtos = memberships.stream().map(m -> {
            if (m.getTenantId() == null) return new AuthResponse.MembershipDto(null, m.getRole().name(), "Global", "");
            Tenant t = valid.tenants().get(m.getTenantId());
            return new AuthResponse.MembershipDto(m.getTenantId(), m.getRole().name(), t != null ? t.getName() : "Unknown", t != null ? t.getSubdomain() : "");
        }).toList();

        String accessToken = jwtService.generateAccessToken(
                user.getId(),
                user.getEmail(),
                memberships,
                propertyUnit
        );

        String refreshTokenStr = UUID.randomUUID().toString();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(refreshTokenStr)
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .build();
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                accessToken,
                refreshTokenStr,
                "Bearer",
                15 * 60,
                new AuthResponse.UserDto(
                        user.getId(),
                        user.getEmail(),
                        user.getFullName(),
                        role,
                        tenantId,
                        propertyUnit,
                        residentPropertyId,
                        user.getHasCompletedOnboarding(),
                        membershipDtos
                )
        );
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenAndRevokedFalse(request.refreshToken())
                .orElseThrow(() -> new BusinessRuleConflictException("INVALID_REFRESH_TOKEN", "Token de refresco no válido o revocado."));

        if (refreshToken.isExpired()) {
            refreshToken.setRevoked(true);
            throw new BusinessRuleConflictException("EXPIRED_REFRESH_TOKEN", "El token de refresco ha expirado. Inicie sesión nuevamente.");
        }

        User user = refreshToken.getUser();
        // SEC-9: una cuenta INACTIVE no obtiene sesion, y solo las membresias ACTIVE entran en el token. Aun asi, la
        // autorizacion de cada peticion se decide contra la base (AuthorizationResolver), no con este contenido.
        if (user.getStatus() != com.havita.modules.identity.domain.UserStatus.ACTIVE) {
            throw new BusinessRuleConflictException("ACCOUNT_INACTIVE", "La cuenta no está activa. Contacta a la administración.");
        }
        ValidMemberships valid = validActiveMemberships(user.getId());
        List<Membership> memberships = valid.memberships();
        Membership activeMembership = memberships.isEmpty() ? null : memberships.get(0);

        String role = activeMembership != null ? activeMembership.getRole().name() : RoleType.RESIDENT.name();
        UUID tenantId = activeMembership != null ? activeMembership.getTenantId() : null;

        String propertyUnit = null;
        UUID residentPropertyId = null;

        if (activeMembership != null && activeMembership.getRole() == RoleType.RESIDENT) {
            if (tenantId != null) {
                // Fase 2 / E1: misma regla que el login (unidad solo si es la unica; ninguna arbitraria).
                List<com.havita.security.ResidentUnitResolver.UnitOption> units =
                        unitResolver.activeUnits(user.getId(), user.getEmail(), tenantId);

                if (units.isEmpty()) {
                    refreshToken.setRevoked(true);
                    throw new BusinessRuleConflictException("ACCESS_REVOKED",
                            "Tu sesión ha expirado porque tu acceso a la propiedad fue revocado.");
                }

                if (units.size() == 1) {
                    propertyUnit = units.get(0).number();
                    residentPropertyId = units.get(0).propertyId();
                }
            }
        }

        
        List<AuthResponse.MembershipDto> membershipDtos = memberships.stream().map(m -> {
            if (m.getTenantId() == null) return new AuthResponse.MembershipDto(null, m.getRole().name(), "Global", "");
            Tenant t = valid.tenants().get(m.getTenantId());
            return new AuthResponse.MembershipDto(m.getTenantId(), m.getRole().name(), t != null ? t.getName() : "Unknown", t != null ? t.getSubdomain() : "");
        }).toList();

        String newAccessToken = jwtService.generateAccessToken(

                user.getId(),
                user.getEmail(),
                memberships,
                propertyUnit
        );

        return new AuthResponse(
                newAccessToken,
                refreshToken.getToken(),
                "Bearer",
                15 * 60,
                new AuthResponse.UserDto(
                        user.getId(),
                        user.getEmail(),
                        user.getFullName(),
                        role,
                        tenantId,
                        propertyUnit,
                        residentPropertyId,
                        user.getHasCompletedOnboarding(),
                        membershipDtos
                )
        );
    }

    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
        eventPublisher.publishEvent(new com.havita.modules.identity.domain.UserSessionsRevokedEvent(userId));
    }

    @Transactional
    public void forgotPassword(com.havita.modules.identity.api.dto.ForgotPasswordRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        User user = userRepository.findByEmailIgnoreCase(request.email()).orElse(null);
        if (user == null) {
            // Silently return to prevent email enumeration
            return;
        }

        String token = UUID.randomUUID().toString();
        com.havita.modules.identity.domain.PasswordResetToken resetToken = com.havita.modules.identity.domain.PasswordResetToken.builder()
                .user(user)
                .token(token)
                .expiresAt(OffsetDateTime.now().plusMinutes(15))
                .used(false)
                .build();
        passwordResetTokenRepository.save(resetToken);

        // Fetch tenant context if available
        UUID tenantId = null;
        String tenantHeader = httpRequest.getHeader("X-Tenant-ID");
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            try {
                tenantId = java.util.UUID.fromString(tenantHeader);
            } catch (IllegalArgumentException ignored) {}
        }
        if (tenantId == null) {
            tenantId = com.havita.security.TenantContextHolder.getTenantId();
        }
        String tenantName = "Havita";
        String primaryColor = "#6d28d9"; // Violet-700
        String logoUrl = "";

        if (tenantId != null) {
            com.havita.modules.organization.domain.Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
            if (tenant != null) {
                tenantName = tenant.getName();
                if (tenant.getPrimaryColor() != null) primaryColor = tenant.getPrimaryColor();
                if (tenant.getLogoUrl() != null) logoUrl = tenant.getLogoUrl();
            }
        }

        String origin = httpRequest.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            origin = httpRequest.getHeader("Referer");
            if (origin != null && origin.endsWith("/")) {
                origin = origin.substring(0, origin.length() - 1);
            }
        }
        if (origin == null || origin.isBlank()) {
            origin = "https://havita.com.co";
        }
        
        String resetLink = origin + "/reset-password?token=" + token;
        
        String htmlContent = String.format("""
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; border: 1px solid #e0e0e0; border-radius: 8px; overflow: hidden;">
                <div style="background-color: %s; padding: 20px; text-align: center;">
                    %s
                </div>
                <div style="padding: 30px; color: #333333;">
                    <h2 style="color: %s; margin-top: 0;">Recuperación de Contraseña</h2>
                    <p>Hola %s,</p>
                    <p>Hemos recibido una solicitud para restablecer tu contraseña en <strong>%s</strong>.</p>
                    <p>Haz clic en el siguiente botón para crear una nueva contraseña. Este enlace es válido por 15 minutos.</p>
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="%s" style="background-color: %s; color: #ffffff; padding: 12px 24px; text-decoration: none; border-radius: 4px; font-weight: bold; display: inline-block;">Restablecer Contraseña</a>
                    </div>
                    <p style="font-size: 14px; color: #666666;">Si no solicitaste este cambio, puedes ignorar este correo con seguridad. Tu contraseña actual seguirá siendo válida.</p>
                </div>
                <div style="background-color: #f8f9fa; padding: 15px; text-align: center; font-size: 12px; color: #999999;">
                    <p>Potenciado por <strong>Havita</strong></p>
                </div>
            </div>
            """,
            primaryColor,
            (logoUrl != null && !logoUrl.isBlank()) ? "<img src=\"" + logoUrl + "\" alt=\"" + tenantName + "\" style=\"max-height: 50px; border-radius: 4px;\" />" : "<h1 style=\"color: #ffffff; margin: 0;\">" + tenantName + "</h1>",
            primaryColor,
            user.getFullName(),
            tenantName,
            resetLink,
            primaryColor
        );

        emailService.sendEmail(user.getEmail(), "Recuperación de contraseña - " + tenantName, htmlContent);
        log.info("Correo de recuperacion de contrasena enviado: userId={}", user.getId());
    }

    @Transactional
    public void resetPassword(com.havita.modules.identity.api.dto.ResetPasswordRequest request) {
        com.havita.modules.identity.domain.PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(request.token())
                .orElseThrow(() -> new BusinessRuleConflictException("INVALID_TOKEN", "El token es inválido o no existe."));

        if (resetToken.isUsed() || resetToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BusinessRuleConflictException("EXPIRED_TOKEN", "El token ha expirado o ya fue utilizado.");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);
    }
}



