package com.cheq.support.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared readiness flag. Set true only at the END of successful ingestion; the
 * {@code analyze_support_tickets} tool refuses to serve until it is ready.
 */
@Component
public class ReadinessGate {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    public boolean isReady() {
        return ready.get();
    }

    public void markReady() {
        ready.set(true);
    }
}
