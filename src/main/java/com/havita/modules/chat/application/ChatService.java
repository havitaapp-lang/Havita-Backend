package com.havita.modules.chat.application;

import com.havita.common.api.SlidingWindowRateLimiter;
import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.DomainException;
import com.havita.common.exception.InvalidRequestException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.chat.api.dto.ChatAgentView;
import com.havita.modules.chat.api.dto.ChatConversationView;
import com.havita.modules.chat.api.dto.ChatEventView;
import com.havita.modules.chat.api.dto.ChatMessageView;
import com.havita.modules.chat.api.dto.ChatMessagesPage;
import com.havita.modules.chat.api.dto.ChatResidentOption;
import com.havita.modules.chat.api.dto.ChatSendResult;
import com.havita.modules.chat.domain.ChatAuthorRole;
import com.havita.modules.chat.domain.ChatConversation;
import com.havita.modules.chat.domain.ChatConversationEvent;
import com.havita.modules.chat.domain.ChatConversationMessage;
import com.havita.modules.chat.domain.ChatConversationStatus;
import com.havita.modules.chat.domain.ChatEventType;
import com.havita.modules.chat.infrastructure.ChatConversationEventRepository;
import com.havita.modules.chat.infrastructure.ChatConversationMessageRepository;
import com.havita.modules.chat.infrastructure.ChatConversationRepository;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.security.TenantContextHolder;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fase 3 — chat interno residente ↔ porteria (solo texto).
 *
 * <h2>Maquina de estados</h2>
 * <pre>
 *   (nuevo) ──residente escribe──▶ PENDING ──portero toma / responde──▶ ACTIVE(portero)
 *   (nuevo) ──portero inicia────▶ ACTIVE(portero)
 *   ACTIVE ──liberar / responsable pierde acceso──▶ PENDING
 *   ACTIVE ──transferir──▶ ACTIVE(otro portero)
 *   PENDING|ACTIVE ──cerrar──▶ CLOSED
 *   CLOSED ──residente escribe──▶ PENDING (REOPENED)
 *   CLOSED ──portero escribe────▶ ACTIVE(portero) (REOPENED + CLAIMED)
 * </pre>
 * Solo el responsable responde, libera, transfiere o cierra una conversacion ACTIVE. Una PENDING la puede tomar (o
 * cerrar) cualquier portero; responderla la toma. Un mensaje enviado mientras otro la cerraba no se pierde: la reabre.
 *
 * <h2>Concurrencia</h2>
 * Toda escritura: (1) bloquea la membresia del llamante (SEC-9), (2) bloquea la fila de la conversacion
 * ({@code SELECT … FOR UPDATE}). Asi la asignacion es atomica (un solo responsable), los mensajes se numeran sin huecos
 * y los contadores son coherentes. SSE y push se emiten despues del commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    static final int DEFAULT_PAGE = 30;
    static final int MAX_PAGE = 100;
    static final int PREVIEW_LENGTH = 160;
    /** Antispam por cuenta: 30 mensajes por minuto (por instancia). */
    static final int MAX_MESSAGES_PER_MINUTE = 30;

    /** Filtros de la bandeja de porteria. */
    public enum InboxFilter { OPEN, PENDING, ACTIVE, MINE, CLOSED, ALL }

    private final ChatConversationRepository conversations;
    private final ChatConversationMessageRepository messages;
    private final ChatConversationEventRepository events;
    private final ChatAccessPolicy access;
    private final ChatNotifier notifier;
    private final UserRepository users;
    private final PropertyRepository properties;
    private final EntityManager em;

    private final SlidingWindowRateLimiter sendLimiter =
            new SlidingWindowRateLimiter(MAX_MESSAGES_PER_MINUTE, Duration.ofMinutes(1), 50_000, Clock.systemUTC());

    // ═════════════════════════════════════════════════════════════════════════
    // Residente
    // ═════════════════════════════════════════════════════════════════════════

    /** Conversaciones del residente en las unidades que hoy tiene activas. */
    @Transactional(readOnly = true)
    public List<ChatConversationView> listMine() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        access.requireResident(me.getId(), tenantId);
        Set<UUID> units = access.activeUnitIds(me, tenantId);
        if (units.isEmpty()) return List.of();
        return conversations.findForResident(tenantId, me.getId(), units).stream().map(ChatViews::resident).toList();
    }

    /**
     * El residente escribe a porteria desde la unidad de la peticion (cabecera X-Property-Id, validada contra SUS
     * unidades). Crea el hilo si no existe; si estaba cerrado, lo reabre.
     */
    @Transactional
    public ChatSendResult sendMine(String rawBody, UUID clientMessageId) {
        access.lockResident();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        String body = normalizeBody(rawBody);
        UUID propertyId = access.currentResidentUnit();

        GetOrCreate goc = getOrCreate(tenantId, propertyId, me, me);
        ChatConversation c = lock(goc.id(), tenantId);
        if (clientMessageId != null) {
            var existing = messages.findByClientId(c.getId(), me.getId(), clientMessageId);
            if (existing.isPresent()) {
                return new ChatSendResult(ChatViews.resident(c), ChatViews.resident(existing.get(), me.getId()), true);
            }
        }
        rateLimit(me.getId());

        boolean reopened = false;
        if (c.getStatus() == ChatConversationStatus.CLOSED) {
            c.unassign(ChatConversationStatus.PENDING, now());
            record(c, ChatEventType.REOPENED, me, null, null);
            reopened = true;
        }
        ChatConversationMessage m = append(c, me, ChatAuthorRole.RESIDENT, body, clientMessageId);

        List<UUID> staff = access.activeStaffIds(tenantId);
        notifier.conversationChanged(c, m, access.inboxReaderIds(tenantId), true);
        // Un aviso por "tanda": solo cuando la porteria pasa de 0 a 1 no leidos (o el hilo es nuevo o se reabrio).
        if (c.getStaffUnread() == 1 || goc.created() || reopened) {
            List<UUID> recipients = c.getStatus() == ChatConversationStatus.ACTIVE && c.getAssignedTo() != null
                    ? List.of(c.getAssignedTo().getId()) : staff;
            notifier.pushToStaff(c, recipients, "Mensaje nuevo de un residente");
        }
        return new ChatSendResult(ChatViews.resident(c), ChatViews.resident(m, me.getId()), false);
    }

    @Transactional(readOnly = true)
    public ChatMessagesPage messagesMine(UUID conversationId, Long beforeSeq, Long afterSeq, Integer size) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        ChatConversation c = ownConversation(conversationId, tenantId, me);
        return page(c, beforeSeq, afterSeq, size, false, me.getId());
    }

    @Transactional
    public ChatConversationView readMine(UUID conversationId, long upToSeq) {
        access.lockResident();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        ownConversation(conversationId, tenantId, me);
        ChatConversation c = lock(conversationId, tenantId);
        long ptr = Math.min(Math.max(c.getResidentLastReadSeq(), upToSeq), c.getLastMessageSeq());
        if (ptr != c.getResidentLastReadSeq()) {
            c.setResidentLastReadSeq(ptr);
            c.setResidentUnread((int) messages.countFromAfter(c.getId(), ptr, ChatAuthorRole.STAFF));
            notifier.residentOnly(c); // sincroniza el contador en sus otros dispositivos
        }
        return ChatViews.resident(c);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Porteria
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Page<ChatConversationView> inbox(InboxFilter filter, String q, int page, int size) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID me = access.currentUserId();
        access.requireInboxReader(me, tenantId);
        InboxFilter f = filter != null ? filter : InboxFilter.OPEN;
        Set<ChatConversationStatus> statuses = switch (f) {
            case OPEN, MINE -> EnumSet.of(ChatConversationStatus.PENDING, ChatConversationStatus.ACTIVE);
            case PENDING -> EnumSet.of(ChatConversationStatus.PENDING);
            case ACTIVE -> EnumSet.of(ChatConversationStatus.ACTIVE);
            case CLOSED -> EnumSet.of(ChatConversationStatus.CLOSED);
            case ALL -> EnumSet.allOf(ChatConversationStatus.class);
        };
        String pattern = q == null || q.isBlank() ? "%" : "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        int pageSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 50));
        return conversations.inbox(tenantId, statuses, f == InboxFilter.MINE, me, pattern,
                        PageRequest.of(Math.max(page, 0), pageSize))
                .map(ChatViews::staff);
    }

    @Transactional(readOnly = true)
    public ChatConversationView getForStaff(UUID conversationId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        access.requireInboxReader(access.currentUserId(), tenantId);
        return ChatViews.staff(detailed(conversationId, tenantId));
    }

    @Transactional(readOnly = true)
    public ChatMessagesPage messagesForStaff(UUID conversationId, Long beforeSeq, Long afterSeq, Integer size) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID me = access.currentUserId();
        access.requireInboxReader(me, tenantId);
        return page(detailed(conversationId, tenantId), beforeSeq, afterSeq, size, true, me);
    }

    @Transactional(readOnly = true)
    public List<ChatEventView> history(UUID conversationId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        access.requireInboxReader(access.currentUserId(), tenantId);
        detailed(conversationId, tenantId);
        return events.findHistory(conversationId, tenantId).stream().map(ChatViews::event).toList();
    }

    @Transactional(readOnly = true)
    public List<ChatAgentView> agents() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        access.requireStaff(access.currentUserId(), tenantId);
        return access.activeStaff(tenantId).stream().map(u -> new ChatAgentView(u.getId(), u.getFullName())).toList();
    }

    /**
     * Residentes con cuenta y relacion ACTIVE para iniciar una conversacion (busqueda por nombre, torre o unidad).
     * Maximo 20. Incluye el id de la conversacion si ya existe.
     */
    @Transactional(readOnly = true)
    public List<ChatResidentOption> residentDirectory(String q) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        access.requireStaff(access.currentUserId(), tenantId);
        String pattern = q == null || q.isBlank() ? "%" : "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        List<Resident> rows = em.createQuery("select r from Resident r join fetch r.property p left join fetch p.structure s"
                        + " join fetch r.user u where r.tenantId = :t and r.status = :rs"
                        + " and u.status = com.havita.modules.identity.domain.UserStatus.ACTIVE"
                        + " and (lower(u.fullName) like :q or lower(r.name) like :q or lower(p.number) like :q"
                        + "      or lower(coalesce(s.name, '')) like :q)"
                        + " order by s.name, p.number, u.fullName", Resident.class)
                .setParameter("t", tenantId).setParameter("rs", ResidentStatus.ACTIVE).setParameter("q", pattern)
                .setMaxResults(40)
                .getResultList();
        Map<String, ChatResidentOption> out = new LinkedHashMap<>();
        for (Resident r : rows) {
            String key = r.getUser().getId() + ":" + r.getProperty().getId();
            if (out.containsKey(key) || out.size() >= 20) continue;
            UUID existing = conversations.findIdByResidentUnit(tenantId, r.getProperty().getId(), r.getUser().getId())
                    .orElse(null);
            out.put(key, new ChatResidentOption(r.getUser().getId(), r.getUser().getFullName(), r.getProperty().getId(),
                    ChatViews.unitLabel(r.getProperty()), existing));
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Porteria inicia (o retoma) la conversacion con un residente. Queda asignada a quien la inicia si estaba libre o
     * cerrada; si otro portero la atiende, se devuelve tal cual (se ve quien la atiende).
     */
    @Transactional
    public ChatConversationView start(UUID propertyId, UUID residentUserId) {
        access.lockStaff();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        properties.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));
        User resident = users.findById(residentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", residentUserId));
        if (!access.residentCanUse(resident, tenantId, propertyId)) {
            throw new BusinessRuleConflictException("CHAT_RESIDENT_UNAVAILABLE",
                    "El residente no tiene acceso activo a esa unidad.");
        }
        GetOrCreate goc = getOrCreate(tenantId, propertyId, resident, me);
        ChatConversation c = lock(goc.id(), tenantId);
        if (c.getStatus() == ChatConversationStatus.CLOSED) {
            record(c, ChatEventType.REOPENED, me, null, null);
            claim(c, me);
        } else if (c.getStatus() == ChatConversationStatus.PENDING) {
            claim(c, me);
        }
        notifier.conversationChanged(c, null, access.inboxReaderIds(tenantId), true);
        return ChatViews.staff(c);
    }

    @Transactional
    public ChatSendResult sendAsStaff(UUID conversationId, String rawBody, UUID clientMessageId) {
        access.lockStaff();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        String body = normalizeBody(rawBody);
        ChatConversation c = lock(conversationId, tenantId);
        if (clientMessageId != null) {
            var existing = messages.findByClientId(c.getId(), me.getId(), clientMessageId);
            if (existing.isPresent()) {
                return new ChatSendResult(ChatViews.staff(c), ChatViews.staff(existing.get(), me.getId()), true);
            }
        }
        if (!access.residentCanUse(c.getResidentUser(), tenantId, c.getProperty().getId())) {
            throw new BusinessRuleConflictException("CHAT_RESIDENT_UNAVAILABLE",
                    "El residente ya no tiene acceso a esta unidad; no puede recibir mensajes.");
        }
        switch (c.getStatus()) {
            case ACTIVE -> {
                if (!c.isAssignedTo(me.getId())) throw assignedToOther(c);
            }
            case PENDING -> claim(c, me);
            case CLOSED -> {
                record(c, ChatEventType.REOPENED, me, null, null);
                claim(c, me);
            }
        }
        rateLimit(me.getId());
        ChatConversationMessage m = append(c, me, ChatAuthorRole.STAFF, body, clientMessageId);

        notifier.conversationChanged(c, m, access.inboxReaderIds(tenantId), true);
        // Un aviso por CADA mensaje de porteria (como un chat): el residente puede tener el celular bloqueado. Todos
        // comparten etiqueta en el service worker, de modo que el aviso nuevo sustituye al anterior.
        notifier.pushToResident(c);
        return new ChatSendResult(ChatViews.staff(c), ChatViews.staff(m, me.getId()), false);
    }

    /** Tomar una conversacion PENDING. Atomico: con dos porteros a la vez, uno la obtiene y el otro recibe 409. */
    @Transactional
    public ChatConversationView claim(UUID conversationId) {
        access.lockStaff();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        ChatConversation c = lock(conversationId, tenantId);
        switch (c.getStatus()) {
            case ACTIVE -> {
                if (c.isAssignedTo(me.getId())) return ChatViews.staff(c); // idempotente
                throw assignedToOther(c);
            }
            case CLOSED -> throw new BusinessRuleConflictException("CHAT_CLOSED",
                    "La conversación está cerrada. Escribe un mensaje para reabrirla.");
            case PENDING -> claim(c, me);
        }
        notifier.conversationChanged(c, null, access.inboxReaderIds(tenantId), true);
        return ChatViews.staff(c);
    }

    @Transactional
    public ChatConversationView release(UUID conversationId) {
        access.lockStaff();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        ChatConversation c = lock(conversationId, tenantId);
        requireAssignee(c, me);
        c.unassign(ChatConversationStatus.PENDING, now());
        record(c, ChatEventType.RELEASED, me, me, null);
        List<UUID> staff = access.activeStaffIds(tenantId);
        notifier.conversationChanged(c, null, access.inboxReaderIds(tenantId), true);
        if (c.getStaffUnread() > 0) notifier.pushToStaff(c, staff, "Conversación pendiente de atención");
        return ChatViews.staff(c);
    }

    @Transactional
    public ChatConversationView transfer(UUID conversationId, UUID toUserId) {
        access.lockStaff();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        ChatConversation c = lock(conversationId, tenantId);
        requireAssignee(c, me);
        if (me.getId().equals(toUserId) || !access.isActiveStaff(toUserId, tenantId)) {
            throw new BusinessRuleConflictException("CHAT_TRANSFER_TARGET_INVALID",
                    "Solo se puede transferir a otro portero activo de la comunidad.");
        }
        User target = users.findById(toUserId).orElseThrow(() -> new ResourceNotFoundException("User", toUserId));
        c.assign(target, now());
        record(c, ChatEventType.TRANSFERRED, me, me, target);
        notifier.conversationChanged(c, null, access.inboxReaderIds(tenantId), true);
        notifier.pushToStaff(c, List.of(target.getId()), "Te transfirieron una conversación");
        return ChatViews.staff(c);
    }

    @Transactional
    public ChatConversationView close(UUID conversationId) {
        access.lockStaff();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        User me = access.currentUser();
        ChatConversation c = lock(conversationId, tenantId);
        if (c.getStatus() == ChatConversationStatus.CLOSED) return ChatViews.staff(c); // idempotente
        if (c.getStatus() == ChatConversationStatus.ACTIVE && !c.isAssignedTo(me.getId())) throw assignedToOther(c);
        User previous = c.getAssignedTo();
        c.unassign(ChatConversationStatus.CLOSED, now());
        c.setStaffUnread(0);
        c.setStaffLastReadSeq(c.getLastMessageSeq());
        record(c, ChatEventType.CLOSED, me, previous, null);
        notifier.conversationChanged(c, null, access.inboxReaderIds(tenantId),
                access.residentCanUse(c.getResidentUser(), tenantId, c.getProperty().getId()));
        return ChatViews.staff(c);
    }

    /**
     * Marca como leido para la porteria. Solo cuenta la lectura del RESPONSABLE: un portero que solo mira una
     * conversacion PENDING (o de otro) no apaga el aviso de no leidos de la bandeja compartida.
     */
    @Transactional
    public ChatConversationView readAsStaff(UUID conversationId, long upToSeq) {
        access.lockStaff();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID me = access.currentUserId();
        ChatConversation c = lock(conversationId, tenantId);
        if (c.getStatus() == ChatConversationStatus.ACTIVE && c.isAssignedTo(me)) {
            long ptr = Math.min(Math.max(c.getStaffLastReadSeq(), upToSeq), c.getLastMessageSeq());
            if (ptr != c.getStaffLastReadSeq()) {
                c.setStaffLastReadSeq(ptr);
                c.setStaffUnread((int) messages.countFromAfter(c.getId(), ptr, ChatAuthorRole.RESIDENT));
                notifier.staffOnly(c, access.inboxReaderIds(tenantId));
            }
        }
        return ChatViews.staff(c);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Sistema
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * El portero perdio el acceso (membresia, cuenta o comunidad): sus conversaciones ACTIVE en comunidades donde ya
     * no es porteria vuelven a PENDING (AUTO_RELEASED) y la bandeja se entera en tiempo real.
     *
     * @return conversaciones liberadas
     */
    @Transactional
    public int releaseForLostAccess(UUID userId) {
        int released = 0;
        for (UUID id : conversations.findActiveIdsAssignedTo(userId)) {
            ChatConversation c = conversations.findById(id).orElse(null);
            if (c == null) continue;
            UUID tenantId = c.getTenantId();
            if (access.isActiveStaff(userId, tenantId)) continue;
            c = lock(id, tenantId);
            if (c.getStatus() != ChatConversationStatus.ACTIVE || !c.isAssignedTo(userId)) continue;
            User previous = c.getAssignedTo();
            c.unassign(ChatConversationStatus.PENDING, now());
            record(c, ChatEventType.AUTO_RELEASED, null, previous, null);
            List<UUID> staff = access.activeStaffIds(tenantId);
            notifier.conversationChanged(c, null, access.inboxReaderIds(tenantId),
                    access.residentCanUse(c.getResidentUser(), tenantId, c.getProperty().getId()));
            notifier.pushToStaff(c, staff, "Conversación pendiente de atención");
            released++;
        }
        return released;
    }

    /**
     * Responsable sin responder: una conversacion ACTIVE cuyo ultimo mensaje es del residente y a la que el responsable
     * no ha contestado desde {@code cutoff} (ni la tomo despues) vuelve a PENDING (AUTO_RELEASED) para que la atienda
     * otro portero. El plazo cuenta desde el mensaje del residente o desde que la tomo, lo mas reciente.
     * Una conversacion ya respondida no se libera aunque pase el tiempo (no llena la bandeja de pendientes).
     *
     * @return conversaciones liberadas
     */
    @Transactional
    public int releaseInactive(OffsetDateTime cutoff) {
        int released = 0;
        for (Object[] row : conversations.findInactiveAssigned(cutoff)) {
            UUID id = (UUID) row[0];
            UUID tenantId = (UUID) row[1];
            ChatConversation c = lock(id, tenantId);
            boolean stillWaiting = c.getStatus() == ChatConversationStatus.ACTIVE
                    && c.getLastMessageAuthorRole() == ChatAuthorRole.RESIDENT
                    && c.getLastMessageAt() != null && c.getLastMessageAt().isBefore(cutoff)
                    && c.getAssignedAt() != null && c.getAssignedAt().isBefore(cutoff);
            if (!stillWaiting) continue;
            User previous = c.getAssignedTo();
            c.unassign(ChatConversationStatus.PENDING, now());
            record(c, ChatEventType.AUTO_RELEASED, null, previous, null);
            notifier.conversationChanged(c, null, access.inboxReaderIds(tenantId),
                    access.residentCanUse(c.getResidentUser(), tenantId, c.getProperty().getId()));
            notifier.pushToStaff(c, access.activeStaffIds(tenantId), "Conversación pendiente de atención");
            released++;
        }
        return released;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Internos
    // ═════════════════════════════════════════════════════════════════════════

    private record GetOrCreate(UUID id, boolean created) {}

    private GetOrCreate getOrCreate(UUID tenantId, UUID propertyId, User resident, User actor) {
        var existing = conversations.findIdByResidentUnit(tenantId, propertyId, resident.getId());
        if (existing.isPresent()) return new GetOrCreate(existing.get(), false);
        int inserted = conversations.insertIfAbsent(UUID.randomUUID(), tenantId, propertyId, resident.getId());
        UUID id = conversations.findIdByResidentUnit(tenantId, propertyId, resident.getId())
                .orElseThrow(() -> new IllegalStateException("conversación no creada"));
        if (inserted == 1) {
            ChatConversationEvent e = new ChatConversationEvent();
            e.setTenantId(tenantId);
            e.setConversation(conversations.getReferenceById(id));
            e.setType(ChatEventType.CREATED);
            e.setActor(actor);
            events.save(e);
        }
        return new GetOrCreate(id, inserted == 1);
    }

    private ChatConversation lock(UUID id, UUID tenantId) {
        return conversations.lockById(id, tenantId).orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
    }

    private ChatConversation detailed(UUID id, UUID tenantId) {
        return conversations.findDetailed(id, tenantId).orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
    }

    /** Conversacion del propio residente, en una unidad que hoy tiene activa. Si no, 404 (no revela que existe). */
    private ChatConversation ownConversation(UUID id, UUID tenantId, User me) {
        access.requireResident(me.getId(), tenantId);
        ChatConversation c = conversations.findDetailed(id, tenantId)
                .filter(x -> x.getResidentUser().getId().equals(me.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
        if (!access.activeUnitIds(me, tenantId).contains(c.getProperty().getId())) {
            throw new ResourceNotFoundException("Conversation", id);
        }
        return c;
    }

    private void claim(ChatConversation c, User me) {
        c.assign(me, now());
        record(c, ChatEventType.CLAIMED, me, null, me);
    }

    private void requireAssignee(ChatConversation c, User me) {
        if (c.getStatus() != ChatConversationStatus.ACTIVE || !c.isAssignedTo(me.getId())) {
            throw new BusinessRuleConflictException("CHAT_NOT_ASSIGNED_TO_YOU",
                    "Solo el portero que atiende la conversación puede hacerlo.");
        }
    }

    private BusinessRuleConflictException assignedToOther(ChatConversation c) {
        String who = c.getAssignedTo() != null ? c.getAssignedTo().getFullName() : "otro portero";
        return new BusinessRuleConflictException("CHAT_ASSIGNED_TO_OTHER", "La conversación la está atendiendo " + who + ".");
    }

    private ChatConversationMessage append(ChatConversation c, User author, ChatAuthorRole role, String body,
                                           UUID clientMessageId) {
        long seq = c.getLastMessageSeq() + 1;
        ChatConversationMessage m = new ChatConversationMessage();
        m.setTenantId(c.getTenantId());
        m.setConversation(c);
        m.setSeq(seq);
        m.setAuthor(author);
        m.setAuthorRole(role);
        m.setAuthorName(author.getFullName() != null ? author.getFullName() : "");
        m.setBody(body);
        m.setClientMessageId(clientMessageId);
        messages.save(m);

        c.setLastMessageSeq(seq);
        c.setLastMessageAt(m.getCreatedAt() != null ? m.getCreatedAt() : now());
        c.setLastMessagePreview(body.length() > PREVIEW_LENGTH ? body.substring(0, PREVIEW_LENGTH - 1) + "…" : body);
        c.setLastMessageAuthorRole(role);
        if (role == ChatAuthorRole.RESIDENT) {
            c.setStaffUnread(c.getStaffUnread() + 1);
            c.setResidentLastReadSeq(seq); // quien escribe ya leyo lo anterior
            c.setResidentUnread(0);
        } else {
            c.setResidentUnread(c.getResidentUnread() + 1);
            if (c.isAssignedTo(author.getId())) {
                c.setStaffLastReadSeq(seq);
                c.setStaffUnread(0);
            }
        }
        return m;
    }

    private void record(ChatConversation c, ChatEventType type, User actor, User from, User to) {
        ChatConversationEvent e = new ChatConversationEvent();
        e.setTenantId(c.getTenantId());
        e.setConversation(c);
        e.setType(type);
        e.setActor(actor);
        e.setFromUser(from);
        e.setToUser(to);
        events.save(e);
    }

    private ChatMessagesPage page(ChatConversation c, Long beforeSeq, Long afterSeq, Integer size, boolean staffView,
                                  UUID viewerId) {
        int n = Math.max(1, Math.min(size == null || size <= 0 ? DEFAULT_PAGE : size, MAX_PAGE));
        List<ChatConversationMessage> rows;
        boolean hasMore;
        if (afterSeq != null) {
            rows = messages.findAfter(c.getId(), afterSeq, PageRequest.of(0, n + 1));
            hasMore = rows.size() > n;
            if (hasMore) rows = rows.subList(0, n);
        } else {
            long before = beforeSeq != null ? beforeSeq : Long.MAX_VALUE;
            rows = new ArrayList<>(messages.findBefore(c.getId(), before, PageRequest.of(0, n + 1)));
            hasMore = rows.size() > n;
            if (hasMore) rows = rows.subList(0, n);
            rows = new ArrayList<>(rows);
            Collections.reverse(rows);
        }
        List<ChatMessageView> views = rows.stream()
                .map(m -> staffView ? ChatViews.staff(m, viewerId) : ChatViews.resident(m, viewerId))
                .toList();
        return new ChatMessagesPage(views, hasMore, staffView ? ChatViews.staff(c) : ChatViews.resident(c));
    }

    /**
     * Texto plano: CRLF → LF, sin caracteres de control (salvo salto de linea y tabulador), sin espacios sobrantes en
     * los extremos, no vacio y como maximo 1000 caracteres. No se interpreta HTML: el cliente lo muestra como texto.
     */
    static String normalizeBody(String raw) {
        if (raw == null) throw new InvalidRequestException("CHAT_EMPTY_MESSAGE", "El mensaje no puede estar vacío.");
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t' || !Character.isISOControl(cp)) b.appendCodePoint(cp);
        });
        String out = b.toString().strip();
        if (out.isEmpty()) throw new InvalidRequestException("CHAT_EMPTY_MESSAGE", "El mensaje no puede estar vacío.");
        if (out.length() > ChatConversationMessage.MAX_BODY_LENGTH) {
            throw new InvalidRequestException("CHAT_MESSAGE_TOO_LONG",
                    "El mensaje admite como máximo " + ChatConversationMessage.MAX_BODY_LENGTH + " caracteres.");
        }
        return out;
    }

    private void rateLimit(UUID userId) {
        if (!sendLimiter.tryAcquire(userId.toString())) {
            throw new ChatRateLimitedException();
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 429: demasiados mensajes seguidos. */
    static class ChatRateLimitedException extends DomainException {
        ChatRateLimitedException() {
            super("CHAT_RATE_LIMITED", "Estás enviando mensajes muy rápido. Espera un momento.", HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
