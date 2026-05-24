package io.warden.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tolerantly extracts a {decision, confidence, reasoning} JSON object from a
 * Manifest text response. Handles plain JSON, ```json fenced blocks, and
 * prose-wrapped JSON.
 *
 * Returns the best-effort verdict, never throws - when nothing parses, defaults
 * to ESCALATE with confidence 0 so the application falls into the mod queue
 * instead of getting silently lost.
 */
public final class LlmVerdictParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private LlmVerdictParser() {}

    public static LlmVerdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return fallback("empty response");
        }
        String cleaned = raw.trim();

        List<String> candidates = new ArrayList<>();
        candidates.add(cleaned);

        Matcher fence = FENCE.matcher(cleaned);
        if (fence.find()) {
            candidates.add(fence.group(1).trim());
        }

        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            candidates.add(cleaned.substring(firstBrace, lastBrace + 1));
        }

        for (String c : candidates) {
            try {
                JsonNode node = MAPPER.readTree(c);
                if (!node.isObject()) continue;
                LlmVerdict.Decision d = LlmVerdict.Decision.fromWire(textOrNull(node.get("decision")));
                JsonNode confNode = node.get("confidence");
                double conf = 0.0;
                if (confNode != null && confNode.isNumber()) {
                    conf = clamp01(confNode.asDouble());
                }
                String reasoning = textOrNull(node.get("reasoning"));
                if (reasoning == null) reasoning = "(no reasoning provided)";
                if (reasoning.length() > 2000) reasoning = reasoning.substring(0, 2000) + "…";
                return new LlmVerdict(d, conf, reasoning);
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        return fallback("unparseable response: " + clip(cleaned, 200));
    }

    private static LlmVerdict fallback(String why) {
        return new LlmVerdict(LlmVerdict.Decision.ESCALATE, 0.0, "Parser fell back to escalate: " + why);
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
