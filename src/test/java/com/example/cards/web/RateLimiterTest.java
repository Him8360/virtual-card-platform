package com.example.cards.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(0);

    @Test
    void allowsBurstUpToCapacity_thenRejects_thenRefills() {
        RateLimiter limiter = new RateLimiter(2, 1.0, nanos::get);

        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isFalse();

        nanos.addAndGet(1_000_000_000L);                 // one second -> one token
        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isFalse();
    }

    @Test
    void clientsAreIsolated() {
        RateLimiter limiter = new RateLimiter(1, 0.001, nanos::get);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();
    }

    @Test
    void idleClientsAreEvicted() {
        RateLimiter limiter = new RateLimiter(5, 1.0, nanos::get);
        limiter.tryAcquire("a");
        assertThat(limiter.trackedClients()).isEqualTo(1);

        nanos.addAndGet(11L * 60 * 1_000_000_000L);
        limiter.evictIdle();

        assertThat(limiter.trackedClients()).isZero();
    }
}
