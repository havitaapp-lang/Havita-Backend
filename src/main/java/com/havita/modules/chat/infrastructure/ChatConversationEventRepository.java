package com.havita.modules.chat.infrastructure;

import com.havita.modules.chat.domain.ChatConversationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ChatConversationEventRepository extends JpaRepository<ChatConversationEvent, UUID> {

    @Query("select e from ChatConversationEvent e left join fetch e.actor left join fetch e.fromUser"
            + " left join fetch e.toUser where e.conversation.id = :conversationId and e.tenantId = :tenantId"
            + " order by e.createdAt asc")
    List<ChatConversationEvent> findHistory(@Param("conversationId") UUID conversationId, @Param("tenantId") UUID tenantId);
}
