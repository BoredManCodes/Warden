package io.warden.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin client over the OpenAI-style /v1/responses endpoint with Bearer auth.
 * Endpoint configuration (api key, base url, model) is passed per call so
 * operators can change it on /dash/config without restarting the plugin, and
 * so the same client can target any OpenAI-Responses-compatible gateway.
 */
public final class ManifestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final Logger log;

    public ManifestClient(Logger log) {
        this.log = log;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Endpoint configuration for a single request. */
    public record Endpoint(String apiKey, String baseUrl, String model) {
        public Endpoint {
            if (apiKey == null) apiKey = "";
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://app.manifest.build/v1";
            if (model == null || model.isBlank()) model = "auto";
            baseUrl = stripTrailingSlash(baseUrl.trim());
        }

        private static String stripTrailingSlash(String s) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '/') end--;
            return s.substring(0, end);
        }
    }

    public static final class ManifestException extends Exception {
        private final int statusCode;
        public ManifestException(String message) { this(message, 0, null); }
        public ManifestException(String message, Throwable cause) { this(message, 0, cause); }
        public ManifestException(String message, int statusCode, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }
        public int statusCode() { return statusCode; }
    }

    public String requestText(Endpoint endpoint, String systemPrompt, String userPrompt) throws ManifestException {
        // 4 total attempts (1 initial + 3 retries), 500ms base, 8s cap.
        return requestText(endpoint, systemPrompt, userPrompt, 3, 500L, 8_000L);
    }

    /**
     * Send a Responses request with exponential backoff + jitter on transient failures.
     *
     * Retries only on:
     *   - 429 (rate limit), honoring Retry-After if present
     *   - 5xx (server error)
     *   - IO/timeout (network glitch)
     *
     * 4xx other than 429 fail fast - those are config bugs (bad key, bad model, bad
     * request shape) and retrying just wastes the user's quota and time.
     *
     * @param retries          additional attempts beyond the first (so retries=3 = 4 total)
     * @param baseDelayMs      initial backoff in ms (doubled each attempt with jitter)
     * @param maxDelayMs       cap on a single sleep
     */
    public String requestText(Endpoint endpoint, String systemPrompt, String userPrompt,
                              int retries, long baseDelayMs, long maxDelayMs) throws ManifestException {
        if (endpoint == null || endpoint.apiKey() == null || endpoint.apiKey().isBlank()) {
            throw new ManifestException("manifest_api_key_missing");
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", endpoint.model());
        String input = (safeTrim(systemPrompt) + "\n\n" + safeTrim(userPrompt)).trim();
        body.put("input", input);
        body.put("store", false);

        String requestBody;
        try {
            requestBody = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new ManifestException("manifest_request_build_failed", e);
        }

        int totalAttempts = Math.max(1, retries + 1);
        ManifestException last = null;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            HttpRequest req;
            try {
                req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint.baseUrl() + "/responses"))
                        .header("Authorization", "Bearer " + endpoint.apiKey())
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(45))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
            } catch (Exception e) {
                throw new ManifestException("manifest_request_build_failed", e);
            }

            long sleepHintMs = -1;
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code >= 200 && code < 300) {
                    JsonNode parsed = MAPPER.readTree(res.body());
                    String text = extractText(parsed);
                    if (text.isBlank()) throw new ManifestException("manifest_request_empty", code, null);
                    return text;
                }
                String snippet = res.body() == null ? "" :
                        res.body().replaceAll("\\s+", " ").trim();
                if (snippet.length() > 500) snippet = snippet.substring(0, 500);
                last = new ManifestException(
                        "manifest_request_failed_" + code + (snippet.isEmpty() ? "" : ": " + snippet),
                        code, null);
                if (!isTransient(code)) {
                    // Hard failure - don't retry.
                    throw last;
                }
                // 429 - honor server-issued cooldown if it sent one.
                if (code == 429) {
                    sleepHintMs = parseRetryAfterMs(res.headers().firstValue("Retry-After").orElse(null));
                }
            } catch (ManifestException e) {
                // Empty body from 2xx response - retry up to limit.
                last = e;
            } catch (Exception e) {
                // Network/timeout: assume transient.
                last = new ManifestException("manifest_request_io: " + e.getMessage(), 0, e);
            }

            if (attempt >= totalAttempts) break;
            logRetry(attempt, totalAttempts, last);
            long sleep = sleepHintMs > 0
                    ? Math.min(sleepHintMs, maxDelayMs)
                    : computeBackoffMs(baseDelayMs, maxDelayMs, attempt);
            try { Thread.sleep(sleep); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        if (last != null) throw last;
        throw new ManifestException("manifest_request_failed");
    }

    /** Status codes worth retrying: 429 (rate limit) and 5xx (server-side). */
    static boolean isTransient(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }

    /** Parse Retry-After (RFC 7231): either seconds or an HTTP-date. Returns -1 if unusable. */
    static long parseRetryAfterMs(String header) {
        if (header == null || header.isBlank()) return -1;
        String h = header.trim();
        try { return Long.parseLong(h) * 1000L; }
        catch (NumberFormatException ignored) { /* fall through */ }
        try {
            long epochMs = java.time.ZonedDateTime
                    .parse(h, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            long delta = epochMs - System.currentTimeMillis();
            return delta > 0 ? delta : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** Exponential backoff with full jitter: random in [base, base * 2^attempt], capped. */
    static long computeBackoffMs(long baseDelayMs, long maxDelayMs, int attempt) {
        long high = Math.min(maxDelayMs, baseDelayMs * (1L << Math.min(attempt, 16)));
        long low  = Math.min(baseDelayMs, high);
        if (high <= low) return high;
        return low + ThreadLocalRandom.current().nextLong(high - low + 1);
    }

    /**
     * Walks the OpenAI-shaped Responses payload until it finds the first text payload.
     */
    static String extractText(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) return "";
        JsonNode outputText = root.get("output_text");
        if (outputText != null && outputText.isTextual() && !outputText.asText().isBlank()) {
            return outputText.asText().trim();
        }
        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content == null || !content.isArray()) continue;
                for (JsonNode c : content) {
                    JsonNode t = c.get("text");
                    if (t != null && t.isTextual() && !t.asText().isBlank()) return t.asText().trim();
                    if (t != null && t.isObject()) {
                        JsonNode v = t.get("value");
                        if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText().trim();
                    }
                }
            }
        }
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode msg = choice.get("message");
                if (msg == null) continue;
                JsonNode content = msg.get("content");
                if (content != null && content.isTextual() && !content.asText().isBlank()) {
                    return content.asText().trim();
                }
            }
        }
        return "";
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private void logRetry(int attempt, int total, Throwable e) {
        String msg = e == null ? "(unknown)" : e.getMessage();
        log.log(Level.WARNING, "[manifest] attempt " + attempt + "/" + total
                + " failed, retrying: " + msg);
    }
}
