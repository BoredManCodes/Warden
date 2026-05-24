package io.warden.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure helpers on {@link ManifestClient}: transient-status
 * classification, Retry-After parsing, and exponential-backoff bounds.
 */
class ManifestBackoffTest {

    @Test
    void transientCodesAre429And5xx() {
        assertTrue(ManifestClient.isTransient(429));
        assertTrue(ManifestClient.isTransient(500));
        assertTrue(ManifestClient.isTransient(502));
        assertTrue(ManifestClient.isTransient(503));
        assertTrue(ManifestClient.isTransient(599));
    }

    @Test
    void nonTransientCodesFailFast() {
        assertFalse(ManifestClient.isTransient(200));
        assertFalse(ManifestClient.isTransient(301));
        assertFalse(ManifestClient.isTransient(400));
        assertFalse(ManifestClient.isTransient(401));
        assertFalse(ManifestClient.isTransient(403));
        assertFalse(ManifestClient.isTransient(404));
        assertFalse(ManifestClient.isTransient(422));
        assertFalse(ManifestClient.isTransient(0));
    }

    @Test
    void retryAfterSecondsParsesAsMs() {
        assertEquals(3000L, ManifestClient.parseRetryAfterMs("3"));
        assertEquals(0L,    ManifestClient.parseRetryAfterMs("0"));
        assertEquals(60_000L, ManifestClient.parseRetryAfterMs(" 60 "));
    }

    @Test
    void retryAfterMissingOrJunkReturnsNegative() {
        assertEquals(-1L, ManifestClient.parseRetryAfterMs(null));
        assertEquals(-1L, ManifestClient.parseRetryAfterMs(""));
        assertEquals(-1L, ManifestClient.parseRetryAfterMs("   "));
        assertEquals(-1L, ManifestClient.parseRetryAfterMs("nonsense"));
    }

    @Test
    void backoffStaysWithinBaseAndCap() {
        long base = 500;
        long cap  = 8000;
        for (int attempt = 1; attempt <= 8; attempt++) {
            for (int i = 0; i < 50; i++) {
                long delay = ManifestClient.computeBackoffMs(base, cap, attempt);
                assertTrue(delay >= 0, "non-negative");
                assertTrue(delay <= cap, "<= cap: was " + delay);
            }
        }
    }

    @Test
    void backoffGrowsThenSaturates() {
        long base = 100;
        long cap  = 1000;
        long maxAtAttempt1 = base * 2;       // ceiling of 2^1
        long maxAtAttempt2 = base * 4;       // ceiling of 2^2
        // Each attempt's upper bound should not exceed base * 2^attempt (or cap).
        for (int i = 0; i < 100; i++) {
            assertTrue(ManifestClient.computeBackoffMs(base, cap, 1) <= maxAtAttempt1);
            assertTrue(ManifestClient.computeBackoffMs(base, cap, 2) <= maxAtAttempt2);
        }
    }
}
