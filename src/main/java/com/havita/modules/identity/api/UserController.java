package com.havita.modules.identity.api;

import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.api.dto.CreateUserRequest;
import com.havita.common.exception.BusinessRuleConflictException;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.validation.Valid;
import com.havita.common.api.ApiResponse;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final com.havita.security.AccessValidator accessValidator;

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.havita.modules.identity.application.GlobalEmailValidationService globalEmailValidationService;

    public record UserWithMembershipsDto(
            UUID id,
            String name,
            String email,
            UserStatus status,
            List<MembershipDetailDto> memberships
    ) {}

    public record MembershipDetailDto(
            UUID id,
            UUID tenantId,
            String tenantName,
            String role
    ) {}

    private List<UserWithMembershipsDto> mapUsers(List<User> users) {
        return users.stream().map(u -> {
            List<Membership> memberships = membershipRepository.findByUserId(u.getId());
            List<MembershipDetailDto> memDtos = memberships.stream().map(m -> {
                String tenantName = "Global / Super Admin";
                if (m.getTenantId() != null) {
                    tenantName = tenantRepository.findById(m.getTenantId())
                            .map(Tenant::getName)
                            .orElse("Desconocido");
                }
                return new MembershipDetailDto(m.getId(), m.getTenantId(), tenantName, m.getRole().name());
            }).collect(Collectors.toList());

            return new UserWithMembershipsDto(
                    u.getId(),
                    u.getFullName(),
                    u.getEmail(),
                    u.getStatus(),
                    memDtos
            );
        }).collect(Collectors.toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Transactional
    public ResponseEntity<ApiResponse<UserWithMembershipsDto>> createUser(@Valid @RequestBody CreateUserRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        globalEmailValidationService.validateEmailUniqueness(request.email());

        User user = User.builder()
                .email(request.email().toLowerCase())
                .fullName(request.fullName())
                .phone(request.phone())
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();
        
        user = userRepository.save(user);

        Membership membership = Membership.builder()
                .user(user)
                .tenantId(null) // Global role for now, can be modified via other endpoints
                .role(request.role())
                .build();
        
        membershipRepository.save(membership);

        return ResponseEntity.ok(ApiResponse.ok(mapUsers(List.of(user)).get(0), "Usuario creado exitosamente"));
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<UserWithMembershipsDto>>> getAllUsers() {
        List<User> users = userRepository.findAll();
        return ResponseEntity.ok(ApiResponse.ok(mapUsers(users), "Todos los usuarios recuperados"));
    }

    @GetMapping("/internal")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<UserWithMembershipsDto>>> getInternalUsers() {
        List<User> users = userRepository.findByMembershipRolesIn(List.of(RoleType.SUPER_ADMIN, RoleType.PLATFORM_ADMIN));
        return ResponseEntity.ok(ApiResponse.ok(mapUsers(users), "Usuarios internos recuperados"));
    }

    @GetMapping("/tenant-admins")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<UserWithMembershipsDto>>> getTenantAdmins() {
        List<User> users = userRepository.findByMembershipRolesIn(List.of(RoleType.COMMUNITY_ADMIN));
        return ResponseEntity.ok(ApiResponse.ok(mapUsers(users), "Admins de copropiedades recuperados"));
    }
}
