package cn.flying.verifier.web;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Small synchronized fixed-window limiter with a hard cap on remembered client addresses.
 */
final class FixedWindowRateLimiter {

    private final int requests;
    private final long windowMillis;
    private final int maxClients;
    private final Map<String, Window> windows = new HashMap<>();

    /** Creates one bounded limiter and rejects unusable policy values. */
    FixedWindowRateLimiter(VerifierProperties.RateLimit policy) {
        this.requests = policy.requests();
        this.windowMillis = requirePositiveMillis(policy.window());
        this.maxClients = policy.maxClients();
    }

    /** Consumes one request for a direct peer if its active window still has capacity. */
    synchronized boolean tryAcquire(String client, Instant now) {
        long nowMillis = now.toEpochMilli();
        Window existing = windows.get(client);
        if (existing != null && nowMillis - existing.startedAtMillis() < windowMillis) {
            if (existing.count() >= requests) {
                return false;
            }
            windows.put(client, new Window(existing.startedAtMillis(), existing.count() + 1));
            return true;
        }

        removeExpired(nowMillis);
        if (!windows.containsKey(client) && windows.size() >= maxClients) {
            return false;
        }
        windows.put(client, new Window(nowMillis, 1));
        return true;
    }

    /** Returns the operator window in whole seconds for Retry-After. */
    long retryAfterSeconds() {
        return Math.max(1L, (windowMillis + 999L) / 1000L);
    }

    /** Removes expired addresses before admitting a new map key. */
    private void removeExpired(long nowMillis) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().getValue();
            if (nowMillis - window.startedAtMillis() >= windowMillis) {
                iterator.remove();
            }
        }
    }

    /** Converts one duration into a strictly positive millisecond window. */
    private long requirePositiveMillis(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Verifier rate-limit window must be positive");
        }
        long millis = duration.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("Verifier rate-limit window must be at least one millisecond");
        }
        return millis;
    }

    /** One immutable peer request counter and its window origin. */
    private record Window(long startedAtMillis, int count) {
    }
}
