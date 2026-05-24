package io.warden.onboarding;

public enum OnboardingState {
    PENDING_LINK("pending_link",     "Waiting to start"),
    LINKED("linked",                 "Started"),
    AWAITING_ANSWERS("awaiting_answers", "Answering questions"),
    AWAITING_REVIEW("awaiting_review",   "Awaiting review"),
    APPROVED("approved",             "Approved"),
    DENIED("denied",                 "Denied");

    private final String wireName;
    private final String label;

    OnboardingState(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    public String wireName() {
        return wireName;
    }

    /** Human-friendly label - safe for user-visible surfaces. */
    public String label() {
        return label;
    }

    public static OnboardingState fromWire(String s) {
        if (s == null) return PENDING_LINK;
        for (OnboardingState v : values()) {
            if (v.wireName.equals(s)) return v;
        }
        throw new IllegalArgumentException("Unknown onboarding state: " + s);
    }

    /** Look up the friendly label from a wire string, falling back to the input. */
    public static String labelFromWire(String s) {
        if (s == null || s.isBlank()) return "Waiting to start";
        for (OnboardingState v : values()) if (v.wireName.equals(s)) return v.label;
        return s;
    }
}
