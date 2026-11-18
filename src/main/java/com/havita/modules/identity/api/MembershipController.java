package com.havita.modules.identity.api;
import com.havita.modules.identity.domain.MembershipStatus;

import com.havita.common.api.ApiResponse;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/memberships")
@RequiredArgsConstructor
public class MembershipController {

    private final com.havita.security.AccessValidator accessValidator;

    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Transactional
    public ResponseEntity<ApiResponse<Membership>> assignMembership(@RequestBody Map<String, String> request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        String email = request.get("email");
        String role = request.get("role");
        String tenantIdStr = request.get("tenantId");

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            throw new RuntimeException("Usuario no encontrado con ese email.");
        }
        User user = userOpt.get();

        UUID tenantId = tenantIdStr != null && !tenantIdStr.isBlank() ? UUID.fromString(tenantIdStr) : null;
        
        // Prevent duplicate membership for the same tenant
        List<Membership> existing = membershipRepository.findByUserId(user.getId());
        for (Membership m : existing) {
            if ((tenantId == null && m.getTenantId() == null) || 
                (tenantId != null && tenantId.equals(m.getTenantId()))) {
                // Update role if exists
                m.setRole(RoleType.valueOf(role));
                membershipRepository.save(m);
                return ResponseEntity.ok(ApiResponse.ok(m, "Membresía actualizada"));
            }
        }

        // Si el usuario tiene una membresía "placeholder" sin tenant (creado como COMMUNITY_ADMIN sin asignación),
        // reutilizamos esa membresía y le asignamos el tenant en vez de crear una nueva.
        if (tenantId != null) {
            for (Membership m : existing) {
                if (m.getTenantId() == null && m.getRole() == RoleType.COMMUNITY_ADMIN) {
                    m.setTenantId(tenantId);
                    m.setRole(RoleType.valueOf(role));
                    membershipRepository.save(m);
                    return ResponseEntity.ok(ApiResponse.ok(m, "Membresía inicial asignada a la copropiedad"));
                }
            }
        }

        Membership newMembership = Membership.builder()
                .user(user)
                .tenantId(tenantId)
                .role(RoleType.valueOf(role))
                .status(MembershipStatus.ACTIVE)
                .build();
        
        membershipRepository.save(newMembership);
        return ResponseEntity.ok(ApiResponse.ok(newMembership, "Membresía asignada exitosamente"));
    }
    
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> removeMembership(@PathVariable UUID id) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockGlobalAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        membershipRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Membresía eliminada exitosamente"));
    }
}