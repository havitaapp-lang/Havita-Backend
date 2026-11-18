package com.havita.modules.assembly.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fase 2 / P2: guardia sin efecto mientras los poderes estan desactivados (configuracion por defecto de todos los
 * perfiles existentes). No consulta {@code assembly_proxies}: esas tablas pueden no existir.
 */
@Component
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpProxyRepresentationGuard implements ProxyRepresentationGuard {

    @Override
    public void assertDelegationAllowed(java.util.UUID propertyId, UUID tenantId, UUID actorUserId) {
        // sin poderes: nada que comprobar
    }

    @Override
    public void assertTraditionalAttendanceAllowed(UUID assemblyId, UUID propertyId, UUID tenantId, UUID actorUserId, boolean byAdministration) {
        // sin poderes: nada que comprobar
    }
}
