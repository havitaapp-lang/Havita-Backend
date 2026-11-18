package com.havita.modules.intercom;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P8-A2 — mismo ciclo de vida que {@link IntercomLifecycleIT}, sobre PostgreSQL aislado y RealtimeKit SIMULADO, pero con
 * {@code havita.intercom.parallel-participant-creation=true}. Hereda las 10 pruebas:
 * <ul>
 *   <li>aceptar, rechazar, cancelar y perder;</li>
 *   <li>caducidades;</li>
 *   <li>carreras y llamadas simultáneas;</li>
 *   <li>revocación y aislamiento entre comunidades;</li>
 *   <li>limpieza, sin tokens en los registros.</li>
 * </ul>
 */
class IntercomParallelParticipantsIT extends IntercomLifecycleIT {

    @DynamicPropertySource
    static void parallelParticipants(DynamicPropertyRegistry registry) {
        registry.add("havita.intercom.parallel-participant-creation", () -> "true");
    }
}
