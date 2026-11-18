package com.havita.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * SEC-9: lectura, desde servicios, de los permisos efectivos que el filtro resolvio contra PostgreSQL para esta
 * peticion. Se apoya en el {@code SecurityContext} (no en una cache), asi que vale exactamente para la peticion en curso.
 */
public final class AuthorizationScope {

    private AuthorizationScope() {
    }

    /** true si la peticion actua con una membresia global valida (sin comunidad). */
    public static boolean isGlobal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> EffectiveAccess.GLOBAL_SCOPE.equals(a.getAuthority()));
    }

    /** Permisos efectivos resueltos por el filtro, si la peticion los tiene. */
    public static Optional<EffectiveAccess> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof EffectiveAccess access ? Optional.of(access) : Optional.empty();
    }
}
