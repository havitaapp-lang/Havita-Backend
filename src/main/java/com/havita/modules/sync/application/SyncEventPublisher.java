package com.havita.modules.sync.application;

import com.havita.modules.sync.domain.SyncEvent;

public interface SyncEventPublisher {
    void publish(SyncEvent event);
}

