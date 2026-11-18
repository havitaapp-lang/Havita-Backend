package com.havita.common.exception;

import org.springframework.http.HttpStatus;

public class TenantAccessDeniedException extends DomainException {
    public TenantAccessDeniedException(String message) {
        super("TENANT_ACCESS_DENIED", message, HttpStatus.FORBIDDEN);
    }

    public static TenantAccessDeniedException defaultViolation() {
        return new TenantAccessDeniedException("Acceso no autorizado: la operación solicitada no pertenece a la copropiedad activa.");
    }
}
