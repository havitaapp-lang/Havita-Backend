package com.havita.modules.chat.infrastructure;

import com.havita.modules.chat.domain.ChatConversation;
import com.havita.modules.chat.domain.ChatConversationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    /** Lectura con unidad, torre, residente y responsable (una consulta). Siempre acotada a la comunidad. */
    @Query("select c from ChatConversation c join fetch c.property p left join fetch p.structure"
            + " join fetch c.residentUser left join fetch c.assignedTo"
            + " where c.id = :id and c.tenantId = :tenantId")
    Optional<ChatConversation> findDetailed(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /**
     * Bloqueo de la fila (SELECT … FOR UPDATE) para toda operacion que cambie la conversacion: asignacion atomica (un
     * solo responsable), orden de los mensajes sin huecos y contadores coherentes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChatConversation c where c.id = :id and c.tenantId = :tenantId")
    Optional<ChatConversation> lockById(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("select c.id from ChatConversation c where c.tenantId = :tenantId and c.property.id = :propertyId"
            + " and c.residentUser.id = :residentUserId")
    Optional<UUID> findIdByResidentUnit(@Param("tenantId") UUID tenantId, @Param("propertyId") UUID propertyId,
                                        @Param("residentUserId") UUID residentUserId);

    /**
     * Crea el hilo del residente en la unidad si no existe (concurrencia: dos primeros mensajes simultaneos no
     * duplican el hilo ni fallan; el segundo encuentra el creado por el primero).
     */
    @Modifying
    @Query(value = "insert into chat_conversations (id, tenant_id, property_id, resident_user_id, status,"
            + " last_message_seq, resident_unread, staff_unread, resident_last_read_seq, staff_last_read_seq,"
            + " version, created_at, updated_at)"
            + " values (:id, :tenantId, :propertyId, :residentUserId, 'PENDING', 0, 0, 0, 0, 0, 0, now(), now())"
            + " on conflict (tenant_id, property_id, resident_user_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("propertyId") UUID propertyId,
                       @Param("residentUserId") UUID residentUserId);

    /** Conversaciones del residente en las unidades indicadas (las que hoy tiene activas). */
    @Query("select c from ChatConversation c join fetch c.property p left join fetch p.structure"
            + " join fetch c.residentUser left join fetch c.assignedTo"
            + " where c.tenantId = :tenantId and c.residentUser.id = :residentUserId and p.id in :propertyIds"
            + " order by coalesce(c.lastMessageAt, c.createdAt) desc")
    List<ChatConversation> findForResident(@Param("tenantId") UUID tenantId, @Param("residentUserId") UUID residentUserId,
                                           @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * Bandeja compartida de porteria: filtro por estados, opcionalmente solo las del portero, y busqueda por residente,
     * torre/estructura, numero o nombre de la unidad ({@code q} ya en minusculas y con comodines; "%" = todas).
     */
    @Query(value = "select c from ChatConversation c join fetch c.property p left join fetch p.structure s"
            + " join fetch c.residentUser ru left join fetch c.assignedTo a"
            + " where c.tenantId = :tenantId and c.status in :statuses"
            + " and (:mineOnly = false or a.id = :me)"
            + " and (lower(ru.fullName) like :q or lower(p.number) like :q or lower(coalesce(p.name, '')) like :q"
            + "      or lower(coalesce(s.name, '')) like :q)"
            + " order by coalesce(c.lastMessageAt, c.createdAt) desc",
            countQuery = "select count(c) from ChatConversation c join c.property p left join p.structure s"
                    + " join c.residentUser ru left join c.assignedTo a"
                    + " where c.tenantId = :tenantId and c.status in :statuses"
                    + " and (:mineOnly = false or a.id = :me)"
                    + " and (lower(ru.fullName) like :q or lower(p.number) like :q or lower(coalesce(p.name, '')) like :q"
                    + "      or lower(coalesce(s.name, '')) like :q)")
    Page<ChatConversation> inbox(@Param("tenantId") UUID tenantId,
                                 @Param("statuses") Collection<ChatConversationStatus> statuses,
                                 @Param("mineOnly") boolean mineOnly, @Param("me") UUID me,
                                 @Param("q") String q, Pageable pageable);

    /**
     * Conversaciones ACTIVE en las que el residente espera respuesta (ultimo mensaje suyo) desde antes de {@code cutoff}
     * y que el responsable tomo antes de {@code cutoff}: [id, tenantId]. Todas las comunidades (trabajo programado);
     * cada una se vuelve a comprobar bajo bloqueo antes de liberarla.
     */
    @Query("select c.id, c.tenantId from ChatConversation c"
            + " where c.status = com.havita.modules.chat.domain.ChatConversationStatus.ACTIVE"
            + " and c.lastMessageAuthorRole = com.havita.modules.chat.domain.ChatAuthorRole.RESIDENT"
            + " and c.lastMessageAt < :cutoff and c.assignedAt < :cutoff")
    List<Object[]> findInactiveAssigned(@Param("cutoff") java.time.OffsetDateTime cutoff);

    /** Conversaciones ACTIVE de un portero (liberacion automatica cuando pierde el acceso). */
    @Query("select c.id from ChatConversation c where c.assignedTo.id = :userId"
            + " and c.status = com.havita.modules.chat.domain.ChatConversationStatus.ACTIVE")
    List<UUID> findActiveIdsAssignedTo(@Param("userId") UUID userId);
}
