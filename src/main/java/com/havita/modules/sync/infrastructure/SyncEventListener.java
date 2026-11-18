package com.havita.modules.sync.infrastructure;

import com.havita.modules.sync.application.SseService;
import com.havita.modules.sync.domain.SyncEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class SyncEventListener {

    private static final Logger log = LoggerFactory.getLogger(SyncEventListener.class);
    private final SseService sseService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSyncEvent(SyncEvent event) {
        log.info("AFTER_COMMIT: Dispatching SSE event {} for tenant {}", event.type(), event.tenantId());
        sseService.publishEvent(event);
    }
}

