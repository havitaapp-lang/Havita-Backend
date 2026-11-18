package com.havita.security;

import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Decision unica, contra PostgreSQL, de si una identidad puede actuar ahora en una comunidad. La usan el filtro JWT
 * (cada peticion) y las URLs firmadas de archivos (SEC-9-9), para que ambas apliquen exactamente las mismas reglas:
 * <ol>
 *   <li>cuenta y membresia ACTIVE, rol y alcance ({@link AuthorizationResolver}; SEC-9);</li>
 *   <li>comunidad suspendida: bloquea a quien no es global;</li>
 *   <li>RESIDENT: relacion ACTIVE con al menos una unidad de esa comunidad (A-8).</li>
 * </ol>
 * Sin cache: una revocacion surte efecto en la siguiente evaluacion.
 */
@Component
@RequiredArgsConstructor
public class AccessEvaluator {

    private final AuthorizationResolver authorizationResolver;
    private final TenantRepository tenantRepository;
    private final ResidentAccessGate residentAccessGate;
    private final UserRepository userRepository;

    /** Resultado de la evaluacion. */
    public sealed interface Outcome permits Granted, Anonymous, TenantBlocked {
    }

    /** Acceso concedido con estos permisos efectivos. */
    public record Granted(EffectiveAccess access) implements Outcome {
    }

    /** Sin acceso: la peticion se trata como anonima (sin detalle para el cliente). */
    public record Anonymous(String reason) implements Outcome {
    }

    /** La comunidad existe pero esta bloqueada para quien no es global. */
    public record TenantBlocked(Tenant tenant) implements Outcome {
    }

    /**
     * @param email correo del token (para relacionar residentes por correo); si es null se lee de la cuenta.
     */
    @Transactional(readOnly = true)
    public Outcome evaluate(UUID userId, String email, UUID requestedTenantId) {
        Optional<EffectiveAccess> resolved = authorizationResolver.resolve(userId, requestedTenantId);
        if (resolved.isEmpty()) {
            return new Anonymous("NO_EFFECTIVE_MEMBERSHIP");
        }
        EffectiveAccess access = resolved.get();
        if (!access.global() && access.tenantId() != null) {
            Optional<Tenant> tenant = tenantRepository.findById(access.tenantId());
            if (tenant.isPresent() && tenant.get().isSuspended()) {
                return new TenantBlocked(tenant.get());
            }
        }
        if (access.role() == RoleType.RESIDENT) {
            String mail = email != null ? email : userRepository.findById(userId).map(u -> u.getEmail()).orElse(null);
            if (!residentAccessGate.hasActiveResidence(userId, mail, access.tenantId())) {
                return new Anonymous("RESIDENT_WITHOUT_ACTIVE_UNIT");
            }
        }
        return new Granted(access);
    }
}
