package io.warden.onboarding.model;

public enum QuestionKind {
    SHORT_TEXT("short_text",       "Short text"),
    LONG_TEXT("long_text",         "Long text"),
    SINGLE_CHOICE("single_choice", "Single choice"),
    MULTI_CHOICE("multi_choice",   "Multi choice");

    private final String wire;
    private final String label;

    QuestionKind(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public String wire() { return wire; }

    /** Human-friendly label for user-visible surfaces. */
    public String label() { return label; }

    public static QuestionKind fromWire(String s) {
        for (QuestionKind k : values()) if (k.wire.equals(s)) return k;
        throw new IllegalArgumentException("Unknown question kind: " + s);
    }
}
