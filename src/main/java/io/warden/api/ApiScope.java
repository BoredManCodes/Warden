package io.warden.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coarse-grained capabilities a key can carry. Each /api/v1 endpoint declares
 * the scopes it accepts and the {@link ApiKeyService} matches them against the
 * key's stored scope list. Keys with no scopes can authenticate but cannot
 * reach any module endpoint, so the dashboard form requires at least one box
 * to be checked before creating a key.
 */
public enum ApiScope {
    READ_MEMBERS         ("read:members",          "Read members and Discord identities"),
    READ_AUDIT           ("read:audit",            "Read the audit log"),
    READ_ANALYTICS       ("read:analytics",        "Read analytics, retention, geo and overview metrics"),
    READ_ONBOARDING      ("read:onboarding",       "Read pending and historical onboarding applications"),
    WRITE_ONBOARDING     ("write:onboarding",      "Approve, deny, and reonboard users"),
    READ_MODERATION      ("read:moderation",       "Read warnings, mod actions and automod config"),
    WRITE_MODERATION     ("write:moderation",      "Issue warnings and clear warnings"),
    READ_LEVELS          ("read:levels",           "Read level standings and configuration"),
    WRITE_LEVELS         ("write:levels",          "Adjust user XP and level"),
    READ_TICKETS         ("read:tickets",          "Read tickets and transcripts"),
    WRITE_TICKETS        ("write:tickets",         "Reply, change status, assign, and add notes on tickets"),
    READ_FEEDBACK        ("read:feedback",         "Read feedback submissions"),
    WRITE_FEEDBACK       ("write:feedback",        "Change feedback status, post responses, delete entries"),
    READ_REACTION_ROLES  ("read:reaction-roles",   "Read reaction-role groups"),
    READ_ENGAGEMENT      ("read:engagement",       "Read polls and giveaways"),
    WRITE_ENGAGEMENT     ("write:engagement",      "Create polls and giveaways, close polls, draw or cancel giveaways"),
    READ_AUTORESPONDERS  ("read:autoresponders",   "Read autoresponder definitions"),
    WRITE_AUTORESPONDERS ("write:autoresponders",  "Toggle and delete autoresponders"),
    READ_ALERTS          ("read:alerts",           "Read alert rules"),
    WRITE_ALERTS         ("write:alerts",          "Toggle and delete alert rules"),
    READ_SCHEDULER       ("read:scheduler",        "Read scheduled events and RSVPs"),
    WRITE_SCHEDULER      ("write:scheduler",       "Cancel scheduled events"),
    READ_TIMEZONES       ("read:timezones",        "Read per-user timezones"),
    WRITE_TIMEZONES      ("write:timezones",       "Set or clear a user's timezone"),
    READ_HEALTH          ("read:health",           "Read plugin health and module status"),
    READ_MC_PLAYERS      ("read:mc-players",       "Read Minecraft player stats and Discord-link lookups");

    private final String key;
    private final String label;

    ApiScope(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() { return key; }
    public String label() { return label; }

    public static ApiScope fromKey(String k) {
        if (k == null) return null;
        for (ApiScope s : values()) if (s.key.equals(k)) return s;
        return null;
    }

    /** Stable order for the dashboard form and listing. */
    public static List<ApiScope> ordered() {
        return List.of(values());
    }

    /** Group scopes by module prefix for the create form. */
    public static Map<String, List<ApiScope>> grouped() {
        Map<String, List<ApiScope>> out = new LinkedHashMap<>();
        for (ApiScope s : values()) {
            String[] parts = s.key.split(":", 2);
            String module = parts.length == 2 ? parts[1] : parts[0];
            out.computeIfAbsent(module, k -> new java.util.ArrayList<>()).add(s);
        }
        return out;
    }

    public static Set<String> validKeys() {
        Set<String> out = new java.util.HashSet<>();
        for (ApiScope s : values()) out.add(s.key);
        return out;
    }
}
