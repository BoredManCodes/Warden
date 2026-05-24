package io.warden.moderation;

/** Parses "10m", "1h", "7d", "30s". Returns -1 on failure. */
public final class DurationParser {

    private DurationParser() {}

    public static int parse(String raw) {
        if (raw == null) return -1;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return -1;
        int total = 0;
        int numStart = 0;
        boolean hadDigit = false;
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : '\0';
            if (Character.isDigit(c)) {
                hadDigit = true;
                continue;
            }
            if (!hadDigit) {
                if (c == '\0') break;
                return -1;
            }
            int n;
            try { n = Integer.parseInt(s.substring(numStart, i)); }
            catch (NumberFormatException e) { return -1; }
            int mult;
            switch (c) {
                case 's' -> mult = 1;
                case 'm' -> mult = 60;
                case 'h' -> mult = 3600;
                case 'd' -> mult = 86400;
                case 'w' -> mult = 604800;
                case '\0' -> mult = 60;
                default -> { return -1; }
            }
            total += n * mult;
            numStart = i + 1;
            hadDigit = false;
            if (c == '\0') break;
        }
        return total > 0 ? total : -1;
    }
}
