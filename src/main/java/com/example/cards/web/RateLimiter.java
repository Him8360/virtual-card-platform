package com.example.cards.web;

import com.example.cards.config.AppProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** In-memory token bucket per client key. Cheap and dependency-free; see README for the distributed option. */
@Component
public class RateLimiter {

    private static final long IDLE_EVICTION_NANOS = 10L * 60 * 1_000_000_000L;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoTime;

    @Autowired
    public RateLimiter(AppProperties props) {
        this(props.rateLimit().capacity(), props.rateLimit().refillPerSecond(), System::nanoTime);
    }

    RateLimiter(int capacity, double refillPerSecond, LongSupplier nanoTime) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoTime = nanoTime;
    }

    public boolean tryAcquire(String clientKey) {
        long now = nanoTime.getAsLong();
        return buckets.computeIfAbsent(clientKey, k -> new Bucket(capacity, now)).tryConsume(now, capacity, refillPerSecond);
    }

    public long retryAfterSeconds() {
        return refillPerSecond <= 0 ? 60 : Math.max(1, (long) Math.ceil(1.0 / refillPerSecond));
    }

    /** Stops the map growing forever when many distinct clients each show up once. */
    @Scheduled(fixedDelay = 60_000)
    public void evictIdle() {
        long now = nanoTime.getAsLong();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastSeen() > IDLE_EVICTION_NANOS);
    }

    int trackedClients() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
        }

        synchronized boolean tryConsume(long now, int capacity, double refillPerSecond) {
            double elapsedSeconds = (now - lastRefill) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefill = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized long lastSeen() {
            return lastRefill;
        }
    }
}
