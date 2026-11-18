package com.havita.security;

import com.havita.modules.identity.domain.AccessChangedEvent;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.RefreshTokenRepository;
import com.havita.modules.sync.application.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SEC-9 (P1): efectos de una perdida de permisos, una vez confirmada.
 *
 * <ul>
 *   <li><b>Sesiones:</b> los refresh tokens no tienen comunidad, asi que solo se pueden revocar todos los del usuario.
 *       Se revocan unicamente cuando ya no le queda ningun acceso (cuenta inactiva o sin membresias ACTIVE). Si conserva
 *       acceso legitimo en otra comunidad no se cierra nada: sus tokens ya no conceden el permiso perdido, porque la
 *       autorizacion se decide en cada peticion contra PostgreSQL.</li>
 *   <li><b>SSE:</b> se revalidan de inmediato sus conexiones abiertas; se cierran las que dependian del permiso
 *       perdido y siguen las de otras comunidades.</li>
 * </ul>
 */
@Slf4j
@Component
public class AccessRevocationService {

    private final MembershipRepository membershipRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ObjectProvider<SseService> sseService;
    private final TransactionTemplate newTransaction;

    public AccessRevocationService(MembershipRepository membershipRepository, RefreshTokenRepository refreshTokenRepository,
                                   ObjectProvider<SseService> sseService, PlatformTransactionManager txManager) {
        this.membershipRepository = membershipRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.sseService = sseService;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccessChanged(AccessChangedEvent event) {
        Boolean revoked = newTransaction.execute(status -> {
            if (!membershipRepository.findEffectiveByUserId(event.userId()).isEmpty()) {
                return false;
            }
            refreshTokenRepository.revokeAllByUserId(event.userId());
            return true;
        });
        if (Boolean.TRUE.equals(revoked)) {
            log.info("Sesiones del usuario {} revocadas: ya no tiene ningun acceso vigente", event.userId());
        }
        sseService.ifAvailable(sse -> sse.revalidateUser(event.userId()));
    }
}
