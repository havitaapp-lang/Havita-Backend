package com.havita.security;

import com.havita.modules.identity.domain.RoleType;

import java.util.UUID;

/**
 * SEC-9: permisos efectivos de la peticion actual, resueltos contra PostgreSQL (usuario y membresia ACTIVE), no
 * tomados del JWT. {@code global} solo es true para una membresia sin comunidad con rol global; una membresia con
 * comunidad nunca concede alcance global, aunque su rol se llame SUPER_ADMIN, PLATFORM_ADMIN o READ_ONLY.
 */
public record EffectiveAccess(UUID userId, RoleType role, UUID tenantId, boolean global) {

    /** Autoridad que acompana a una membresia global valida; la exigen los endpoints de administracion global. */
    public static final String GLOBAL_SCOPE = "SCOPE_GLOBAL";

    /** Nombre del rol, para el codigo de {@code common} (que no debe depender de los modulos). */
    public String roleName() {
        return role.name();
    }
}
