package com.havita.modules.identity.application;

import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resuelve cuentas de usuario dentro de los limites de un tenant.
 *
 * Los usuarios son globales (no tienen tenant_id); su pertenencia a una comunidad se expresa con una
 * Membership. Cualquier operacion administrativa que reciba un userId debe resolverlo aqui para no poder
 * referenciar cuentas de otras comunidades. Nunca crea membresias: vincular una cuenta a otra comunidad
 * solo ocurre mediante el flujo de invitacion.
 */
@Service
@RequiredArgsConstructor
public class TenantUserResolver {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;

    /**
     * Devuelve el usuario solo si tiene una membresia (en cualquier estado) en el tenant indicado.
     * Si no existe o pertenece a otra comunidad responde 404, sin revelar cual de los dos casos ocurrio.
     */
    @Transactional(readOnly = true)
    public User requireUserInTenant(UUID userId, UUID tenantId) {
        if (userId == null || tenantId == null || membershipRepository.findByUserIdAndTenantId(userId, tenantId).isEmpty()) {
            throw new ResourceNotFoundException("User", userId);
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}
