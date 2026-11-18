package com.havita.modules.identity.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.identity.domain.RoleType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles", description = "Endpoints para consulta de roles asignables")
public class RoleController {

    @Data
    @Builder
    public static class RoleDto {
        private String code;
        private String name;
        private String context;
        private boolean assignable;
    }

    @GetMapping("/assignable")
    @Operation(summary = "Obtener roles asignables según contexto y permisos")
    public ApiResponse<List<RoleDto>> getAssignableRoles(Authentication authentication) {
        List<RoleDto> roles = new ArrayList<>();
        
        if (authentication == null) {
            return ApiResponse.ok(roles, "Roles obtenidos");
        }
        
        // SEC-9: solo un SUPER_ADMIN global (membresia sin comunidad) ve y asigna roles globales.
        boolean isSuperAdmin = com.havita.security.AuthorizationScope.isGlobal() && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(a -> a.equals("ROLE_SUPER_ADMIN"));

        boolean isCommunityAdmin = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(a -> a.equals("ROLE_COMMUNITY_ADMIN"));

        if (isSuperAdmin) {
            roles.add(RoleDto.builder().code(RoleType.SUPER_ADMIN.name()).name("Super Administrador Global").context("GLOBAL").assignable(true).build());
            roles.add(RoleDto.builder().code(RoleType.PLATFORM_ADMIN.name()).name("Administrador de Plataforma").context("GLOBAL").assignable(true).build());
            roles.add(RoleDto.builder().code(RoleType.COMMUNITY_ADMIN.name()).name("Administrador de Copropiedad").context("TENANT").assignable(true).build());
            roles.add(RoleDto.builder().code(RoleType.READ_ONLY.name()).name("Auditor / Solo Lectura").context("GLOBAL/TENANT").assignable(true).build());
        }
        
        if (isCommunityAdmin && !isSuperAdmin) {
            // SEC-9 (decision 2): la administracion de comunidad solo asigna STAFF o SECURITY al personal.
            roles.add(RoleDto.builder().code(RoleType.SECURITY.name()).name("Seguridad y Control de Acceso").context("TENANT").assignable(true).build());
            roles.add(RoleDto.builder().code(RoleType.STAFF.name()).name("Personal (Mantenimiento, Aseo, etc)").context("TENANT").assignable(true).build());
        }
        if (isSuperAdmin) {
            roles.add(RoleDto.builder().code(RoleType.SECURITY.name()).name("Seguridad y Control de Acceso").context("TENANT").assignable(true).build());
            roles.add(RoleDto.builder().code(RoleType.STAFF.name()).name("Personal (Mantenimiento, Aseo, etc)").context("TENANT").assignable(true).build());
            roles.add(RoleDto.builder().code(RoleType.ACCOUNTANT.name()).name("Contador").context("TENANT").assignable(true).build());
            roles.add(RoleDto.builder().code(RoleType.RESIDENT.name()).name("Residente").context("TENANT").assignable(true).build());
        }

        return ApiResponse.ok(roles, "Roles obtenidos");
    }
}
