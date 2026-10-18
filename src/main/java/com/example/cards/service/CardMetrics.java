package com.example.cards.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Thin wrapper so metric names and tags are defined in exactly one place. */
@Component
public class CardMetrics {

    private final MeterRegistry registry;

    public CardMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    /** outcome is one of: success, declined, replayed, rejected (business rule), error (unexpected). */
    public void record(String operation, String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder("cards.operation.duration")
                .tag("operation", operation).tag("outcome", outcome).register(registry));
        registry.counter("cards.operations", "operation", operation, "outcome", outcome).increment();
    }

    public void cardsExpired(int count) {
        registry.counter("cards.expired").increment(count);
    }
}
