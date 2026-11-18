package com.havita.modules.chat.application;

import com.havita.modules.chat.domain.ChatConversation;
import com.havita.modules.chat.domain.ChatConversationMessage;
import com.havita.modules.push.application.PushNotificationService;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.modules.sync.domain.SyncAudience;
import com.havita.modules.sync.domain.SyncEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fase 3 — tiempo real y avisos del chat.
 *
 * <ul>
 *   <li><b>SSE</b> (la conexion unica que ya mantiene la app; sin conexiones por conversacion): audiencia
 *       {@link SyncAudience#users} con destinatarios calculados aqui, en el servidor. La porteria (cuentas SECURITY
 *       activas de la comunidad) recibe la vista del personal; el residente, solo si hoy puede usar ese hilo, la suya.
 *       {@code SyncEventListener} los entrega DESPUES del commit, y {@code SseService} revalida el acceso de cada
 *       conexion.</li>
 *   <li><b>Web Push</b>: tambien despues del commit, con texto fijo (sin el mensaje, nombres ni unidad: puede verse
 *       en la pantalla bloqueada). {@code PushNotificationService} vuelve a comprobar el acceso de cada suscripcion.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatNotifier {

    public static final String EVENT_MESSAGE = "CHAT_MESSAGE";
    public static final String EVENT_CONVERSATION = "CHAT_CONVERSATION_UPDATED";
    /** Tipo del push (y su tag en el service worker: un aviso de chat sustituye al anterior). */
    public static final String PUSH_TYPE = "CHAT_MESSAGE";

    private final SyncEventPublisher syncEventPublisher;
    private final ApplicationEventPublisher applicationEvents;
    private final PushNotificationService pushNotificationService;

    /**
     * Publica el cambio de una conversacion (y el mensaje nuevo, si lo hay) a la porteria y al residente.
     *
     * @param staffIds          porteros activos de la comunidad
     * @param residentReachable el residente puede usar hoy el hilo (si no, no se le envia nada)
     */
    public void conversationChanged(ChatConversation c, ChatConversationMessage message, Collection<UUID> staffIds,
                                    boolean residentReachable) {
        String type = message != null ? EVENT_MESSAGE : EVENT_CONVERSATION;
        if (!staffIds.isEmpty()) {
            Map<String, Object> staffPayload = new HashMap<>();
            staffPayload.put("conversation", ChatViews.staff(c));
            if (message != null) staffPayload.put("message", ChatViews.staff(message, null));
            syncEventPublisher.publish(new SyncEvent(type, c.getTenantId(), staffPayload, SyncAudience.users(staffIds)));
        }
        if (residentReachable) {
            UUID residentId = c.getResidentUser().getId();
            Map<String, Object> residentPayload = new HashMap<>();
            residentPayload.put("conversation", ChatViews.resident(c));
            if (message != null) residentPayload.put("message", ChatViews.resident(message, null));
            syncEventPublisher.publish(new SyncEvent(type, c.getTenantId(), residentPayload,
                    SyncAudience.users(List.of(residentId))));
        }
    }

    /** Solo al residente (p. ej. sincronizar no leidos entre sus dispositivos). */
    public void residentOnly(ChatConversation c) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversation", ChatViews.resident(c));
        syncEventPublisher.publish(new SyncEvent(EVENT_CONVERSATION, c.getTenantId(), payload,
                SyncAudience.users(List.of(c.getResidentUser().getId()))));
    }

    /** Solo a la porteria. */
    public void staffOnly(ChatConversation c, Collection<UUID> staffIds) {
        if (staffIds.isEmpty()) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversation", ChatViews.staff(c));
        syncEventPublisher.publish(new SyncEvent(EVENT_CONVERSATION, c.getTenantId(), payload, SyncAudience.users(staffIds)));
    }

    /** Push al residente (texto fijo). Se envia tras el commit. */
    public void pushToResident(ChatConversation c) {
        applicationEvents.publishEvent(new ChatPushRequested(c.getTenantId(), List.of(c.getResidentUser().getId()),
                "Tienes un mensaje nuevo de portería", "/resident/chat?c=" + c.getId()));
    }

    /** Push a porteros (texto fijo, sin nombre del residente ni unidad). Se envia tras el commit. */
    public void pushToStaff(ChatConversation c, Collection<UUID> recipients, String text) {
        if (recipients.isEmpty()) return;
        applicationEvents.publishEvent(new ChatPushRequested(c.getTenantId(), List.copyOf(recipients), text,
                "/security/chat?c=" + c.getId()));
    }

    /** Peticion de push interna; se despacha tras el commit (si la transaccion se deshace, no se avisa). */
    public record ChatPushRequested(UUID tenantId, List<UUID> userIds, String text, String url) {}

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPushRequested(ChatPushRequested request) {
        for (UUID userId : request.userIds()) {
            try {
                // Asincrono (@Async) y con PushRecipientPolicy: solo suscripciones con acceso vigente a la comunidad.
                pushNotificationService.sendPushToUser(request.tenantId(), userId, "Havita", request.text(),
                        PUSH_TYPE, request.url());
            } catch (Exception e) {
                log.warn("[Chat] No se pudo encolar el push para un destinatario: {}", e.getClass().getSimpleName());
            }
        }
    }
}
