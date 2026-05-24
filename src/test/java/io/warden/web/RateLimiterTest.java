package io.warden.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsBurstUpToLimit() {
        RateLimiter rl = new RateLimiter(3, 60_000);
        for (int i = 0; i < 3; i++) assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"));
    }

    @Test
    void independentKeysHaveIndependentBuckets() {
        RateLimiter rl = new RateLimiter(2, 60_000);
        assertTrue(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("b"));
    }

    @Test
    void allowsAgainAfterWindowExpires() throws Exception {
        RateLimiter rl = new RateLimiter(1, 80);
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"));
        Thread.sleep(120);
        assertTrue(rl.tryAcquire("a"));
    }

    @Test
    void nullKeyTreatedAsSinglePool() {
        RateLimiter rl = new RateLimiter(1, 60_000);
        assertTrue(rl.tryAcquire(null));
        assertFalse(rl.tryAcquire(null));
    }
}
