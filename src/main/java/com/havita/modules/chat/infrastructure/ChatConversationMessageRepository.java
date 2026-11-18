package com.havita.modules.chat.infrastructure;

import com.havita.modules.chat.domain.ChatAuthorRole;
import com.havita.modules.chat.domain.ChatConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Nombre propio: {@code ChatMessageRepository} ya existe (chat anterior del modulo realtime). */
public interface ChatConversationMessageRepository extends JpaRepository<ChatConversationMessage, UUID> {

    /** Pagina hacia atras: los {@code size} mensajes anteriores a {@code beforeSeq} (mas recientes primero). */
    @Query("select m from ChatConversationMessage m where m.conversation.id = :conversationId and m.seq < :beforeSeq"
            + " order by m.seq desc")
    List<ChatConversationMessage> findBefore(@Param("conversationId") UUID conversationId,
                                             @Param("beforeSeq") long beforeSeq, Pageable pageable);

    /** Recuperacion tras desconexion: los mensajes posteriores a {@code afterSeq}, en orden. */
    @Query("select m from ChatConversationMessage m where m.conversation.id = :conversationId and m.seq > :afterSeq"
            + " order by m.seq asc")
    List<ChatConversationMessage> findAfter(@Param("conversationId") UUID conversationId,
                                            @Param("afterSeq") long afterSeq, Pageable pageable);

    /** Idempotencia: el mismo autor reintentando con el mismo id de cliente. */
    @Query("select m from ChatConversationMessage m where m.conversation.id = :conversationId"
            + " and m.author.id = :authorId and m.clientMessageId = :clientMessageId")
    Optional<ChatConversationMessage> findByClientId(@Param("conversationId") UUID conversationId,
                                                     @Param("authorId") UUID authorId,
                                                     @Param("clientMessageId") UUID clientMessageId);

    /** No leidos de un lado: mensajes del otro lado posteriores al puntero de lectura. */
    @Query("select count(m) from ChatConversationMessage m where m.conversation.id = :conversationId"
            + " and m.seq > :afterSeq and m.authorRole = :authorRole")
    long countFromAfter(@Param("conversationId") UUID conversationId, @Param("afterSeq") long afterSeq,
                        @Param("authorRole") ChatAuthorRole authorRole);
}
