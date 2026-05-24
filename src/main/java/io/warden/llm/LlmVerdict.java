package io.warden.llm;

/**
 * Parsed verdict from a Manifest triage call. Same shape returned by the LLM's
 * JSON output and used by the policy + persistence layers.
 */
public record LlmVerdict(Decision decision, double confidence, String reasoning) {
    public enum Decision { APPROVE, DENY, ESCALATE;
        public String wire() { return name().toLowerCase(); }
        public static Decision fromWire(String s) {
            if (s == null) return ESCALATE;
            return switch (s.trim().toLowerCase()) {
                case "approve" -> APPROVE;
                case "deny" -> DENY;
                default -> ESCALATE;
            };
        }
    }
}
