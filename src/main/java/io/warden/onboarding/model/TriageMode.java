package io.warden.onboarding.model;

public enum TriageMode {
    /** Manifest decides; borderline cases escalate to mods. */
    LLM_AUTO("llm_auto", "LLM auto"),
    /** Manifest decides; no escalation. */
    LLM_ONLY("llm_only", "LLM only"),
    /** Every submission goes to mods, no LLM call. */
    MOD_ONLY("mod_only", "Mods only"),
    /** Approve everyone who submits (still records the application). */
    AUTO_APPROVE("auto_approve", "Auto-approve");

    private final String wire;
    private final String label;

    TriageMode(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public String wire() { return wire; }

    /** Human-friendly label for UI text. Never expose the wire identifier to end users. */
    public String label() { return label; }

    public static TriageMode fromWire(String s) {
        if (s == null) return MOD_ONLY;
        for (TriageMode t : values()) if (t.wire.equals(s)) return t;
        return MOD_ONLY;
    }

    /** Look up the friendly label from a wire string, falling back to the wire if unknown. */
    public static String labelFromWire(String s) {
        if (s == null || s.isBlank()) return "Mods only";
        for (TriageMode t : values()) if (t.wire.equals(s)) return t.label;
        return s;
    }
}
