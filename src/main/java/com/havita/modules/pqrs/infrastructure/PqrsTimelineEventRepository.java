package com.havita.modules.pqrs.infrastructure;

import com.havita.modules.pqrs.domain.PqrsTimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PqrsTimelineEventRepository extends JpaRepository<PqrsTimelineEvent, UUID> {

    List<PqrsTimelineEvent> findByPqrsIdOrderByCreatedAtAsc(UUID pqrsId);
}
