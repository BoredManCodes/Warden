package io.warden.web;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory sliding-window rate limiter, keyed by an opaque string (usually IP).
 * Counts requests within the last `windowMs` and rejects when more than `limit` have
 * landed in that window. Single-process; not designed for distributed deployments
 * (Warden runs as one Paper plugin = one JVM, so this is fine).
 *
 * Memory cleanup happens lazily on each tryAcquire() call (drops empty windows seen
 * during eviction), so an idle bucket disappears at the next call from that IP.
 */
public final class RateLimiter {

    private final int limit;
    private final long windowMs;
    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMs) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be > 0");
        this.limit = limit;
        this.windowMs = windowMs;
    }

    /** Returns true if the request is allowed; false if it should be rejected (429). */
    public boolean tryAcquire(String key) {
        if (key == null || key.isEmpty()) key = "_unknown_";
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;
        Deque<Long> bucket = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (bucket) {
            // Evict old entries.
            while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) bucket.pollFirst();
            if (bucket.size() >= limit) return false;
            bucket.addLast(now);
            return true;
        }
    }

    /** Periodic cleanup helper - safe to call from any thread. */
    public void prune() {
        long cutoff = System.currentTimeMillis() - windowMs;
        Iterator<Map.Entry<String, Deque<Long>>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Deque<Long> bucket = e.getValue();
            synchronized (bucket) {
                while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) bucket.pollFirst();
                if (bucket.isEmpty()) it.remove();
            }
        }
    }
}
