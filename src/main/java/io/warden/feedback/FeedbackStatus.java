package io.warden.feedback;

import java.awt.Color;
import java.util.Locale;

public enum FeedbackStatus {
    OPEN,
    UNDER_REVIEW,
    PLANNED,
    IN_PROGRESS,
    DONE,
    DECLINED,
    DUPLICATE;

    public String wire() {
        return switch (this) {
            case OPEN -> "open";
            case UNDER_REVIEW -> "under_review";
            case PLANNED -> "planned";
            case IN_PROGRESS -> "in_progress";
            case DONE -> "done";
            case DECLINED -> "declined";
            case DUPLICATE -> "duplicate";
        };
    }

    public String label() {
        return switch (this) {
            case OPEN -> "Open";
            case UNDER_REVIEW -> "Under review";
            case PLANNED -> "Planned";
            case IN_PROGRESS -> "In progress";
            case DONE -> "Done";
            case DECLINED -> "Declined";
            case DUPLICATE -> "Duplicate";
        };
    }

    /** Color for the Discord embed accent. */
    public Color color() {
        return switch (this) {
            case OPEN -> new Color(0x5865F2);
            case UNDER_REVIEW -> new Color(0x3A86FF);
            case PLANNED -> new Color(0x9B59B6);
            case IN_PROGRESS -> new Color(0xE67E22);
            case DONE -> new Color(0x2ECC71);
            case DECLINED -> new Color(0x95A5A6);
            case DUPLICATE -> new Color(0x607D8B);
        };
    }

    public boolean terminal() {
        return this == DONE || this == DECLINED || this == DUPLICATE;
    }

    public static FeedbackStatus fromWire(String s) {
        if (s == null) return OPEN;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "under_review" -> UNDER_REVIEW;
            case "planned" -> PLANNED;
            case "in_progress" -> IN_PROGRESS;
            case "done" -> DONE;
            case "declined" -> DECLINED;
            case "duplicate" -> DUPLICATE;
            default -> OPEN;
        };
    }
}
