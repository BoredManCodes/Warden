package io.warden.tickets;

import java.util.Locale;

public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED;

    public String wire() {
        return switch (this) {
            case OPEN -> "open";
            case IN_PROGRESS -> "in_progress";
            case RESOLVED -> "resolved";
            case CLOSED -> "closed";
        };
    }

    public String label() {
        return switch (this) {
            case OPEN -> "Open";
            case IN_PROGRESS -> "In progress";
            case RESOLVED -> "Resolved";
            case CLOSED -> "Closed";
        };
    }

    public boolean terminal() {
        return this == RESOLVED || this == CLOSED;
    }

    public static TicketStatus fromWire(String s) {
        if (s == null) return OPEN;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "in_progress" -> IN_PROGRESS;
            case "resolved" -> RESOLVED;
            case "closed" -> CLOSED;
            default -> OPEN;
        };
    }
}
