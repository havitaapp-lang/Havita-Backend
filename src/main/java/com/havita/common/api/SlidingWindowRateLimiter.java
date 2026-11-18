package com.havita.common.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de frecuencia en memoria (ventana deslizante) para endpoints publicos. Es por instancia: con varias
 * instancias del backend el limite efectivo se multiplica (riesgo documentado; para entonces, un almacen compartido).
 * Acota la memoria: si hay demasiadas claves distintas, se purgan las caducadas y, si aun asi no hay sitio, se rechaza.
 */
public class SlidingWindowRateLimiter {

    private final int maxEvents;
    private final Duration window;
    private final int maxKeys;
    private final Clock clock;
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int maxEvents, Duration window, int maxKeys, Clock clock) {
        this.maxEvents = maxEvents;
        this.window = window;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    /** Registra un intento y devuelve true si esta dentro del limite. */
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        if (events.size() >= maxKeys && !events.containsKey(key)) {
            purge(now);
            if (events.size() >= maxKeys) {
                return false;
            }
        }
        Deque<Instant> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(window))) {
                q.pollFirst();
            }
            if (q.size() >= maxEvents) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    private void purge(Instant now) {
        events.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(now.minus(window));
            }
        });
    }
}
