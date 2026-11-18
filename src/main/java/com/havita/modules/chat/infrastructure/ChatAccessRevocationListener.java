package com.havita.modules.chat.infrastructure;

import com.havita.modules.chat.application.ChatService;
import com.havita.modules.identity.domain.AccessChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fase 3 — si un portero pierde el acceso de porteria (SEC-9: membresia modificada o eliminada, cuenta inactiva), sus
 * conversaciones ACTIVE vuelven a PENDING (evento AUTO_RELEASED) para que otro portero las atienda.
 *
 * <p>Tras el commit y en su propia transaccion, como {@code PushAccessRevocationListener}. Aunque esta liberacion no
 * llegara a ejecutarse, el portero ya no puede operar: cada escritura vuelve a bloquear y comprobar su membresia.
 */
@Slf4j
@Component
public class ChatAccessRevocationListener {

    private final ChatService chatService;
    private final TransactionTemplate newTransaction;

    public ChatAccessRevocationListener(ChatService chatService, PlatformTransactionManager txManager) {
        this.chatService = chatService;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccessChanged(AccessChangedEvent event) {
        try {
            Integer released = newTransaction.execute(status -> chatService.releaseForLostAccess(event.userId()));
            if (released != null && released > 0) {
                log.info("[Chat] {} conversacion(es) liberadas: el responsable {} perdio el acceso de porteria",
                        released, event.userId());
            }
        } catch (Exception e) {
            // Nunca rompe el flujo que revoco el acceso.
            log.warn("[Chat] No se pudieron liberar conversaciones del usuario {}: {}", event.userId(),
                    e.getClass().getSimpleName());
        }
    }
}
