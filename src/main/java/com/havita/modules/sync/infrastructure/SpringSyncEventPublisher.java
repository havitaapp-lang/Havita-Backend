package com.havita.modules.sync.infrastructure;

import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.modules.sync.domain.SyncEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringSyncEventPublisher implements SyncEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(SyncEvent event) {
        // Enviar a la infraestructura de eventos de Spring
        applicationEventPublisher.publishEvent(event);
    }
}

