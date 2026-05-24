package io.warden.onboarding.model;

public enum DenyAction {
    /** Application denied, role unchanged. They keep gated role if it was applied. */
    LEAVE_GATED("leave_gated"),
    /** Strip the gated role (so they fall back to whatever the @everyone view is). */
    STRIP_GATED("strip_gated"),
    /** Kick the user from the guild. */
    KICK("kick"),
    /** Ban the user from the guild. */
    BAN("ban");

    private final String wire;
    DenyAction(String wire) { this.wire = wire; }
    public String wire() { return wire; }

    public static DenyAction fromWire(String s) {
        if (s == null) return LEAVE_GATED;
        for (DenyAction d : values()) if (d.wire.equals(s)) return d;
        return LEAVE_GATED;
    }
}
