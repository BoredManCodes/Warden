package io.warden.web.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;
import io.warden.Services;
import io.warden.data.Json;
import io.warden.data.dao.AuditDao;
import io.warden.onboarding.model.UserRecord;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DashAuditHandlers {

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final Services services;

    public DashAuditHandlers(Services services) {
        this.services = services;
    }

    public void list(Context ctx) throws Exception {
        String actor = ctx.queryParam("actor");
        String action = ctx.queryParam("action");
        String target = ctx.queryParam("target");
        int limit;
        try {
            limit = Math.min(1000, Math.max(10, Integer.parseInt(
                    ctx.queryParamAsClass("limit", String.class).getOrDefault("200"))));
        } catch (NumberFormatException e) {
            limit = 200;
        }
        List<AuditDao.Entry> rows = services.auditDao.listFiltered(actor, action, target, limit);

        Map<String, String> nameCache = new HashMap<>();
        Map<String, String> mcCache = new HashMap<>();
        var srv = services.discordSrv();
        boolean srvPresent = srv != null && srv.isPresent();

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Audit · Warden", "audit", ctx));
        h.append("<h1 class=\"h3 mb-3\">Audit log</h1>");

        h.append("<form method=get class=\"row g-2 mb-4 align-items-end\">");
        h.append(filterField("actor", "actor", actor, "bot|llm|web-mod|..."));
        h.append(filterField("action", "action contains", action, ""));
        h.append(filterField("target", "target discord id", target, ""));
        h.append("<div class=\"col-sm-6 col-md-2\"><label class=\"form-label small mb-1\">limit</label>")
                .append("<input class=\"form-control form-control-sm\" name=limit type=number min=10 max=1000 value=\"")
                .append(limit).append("\"></div>");
        h.append("<div class=\"col-sm-12 col-md-auto\">")
                .append("<button class=\"btn btn-primary btn-sm\" type=submit>filter</button>");
        if (actor != null || action != null || target != null) {
            h.append(" <a class=\"btn btn-link btn-sm\" href=/dash/audit>clear</a>");
        }
        h.append("</div></form>");

        h.append("<div class=\"table-responsive\"><table class=\"table table-hover table-sm align-top\">")
                .append("<thead><tr>")
                .append("<th>when</th><th>who</th><th>did</th><th>about</th><th></th>")
                .append("</tr></thead><tbody>");
        for (var r : rows) {
            JsonNode payload = parse(r.payloadJson());
            String who = friendlyWho(r.actor(), r.action(), r.targetDiscordId(), payload, nameCache);
            String did = friendlyAction(r.action(), payload);
            String about = friendlyTarget(r.targetDiscordId(), payload, nameCache);
            String mc = (srvPresent && r.targetDiscordId() != null && !r.targetDiscordId().isBlank())
                    ? resolveMc(r.targetDiscordId(), srv, mcCache)
                    : null;

            h.append("<tr>")
                    .append("<td class=\"text-nowrap small\"><time>").append(FMT.format(Instant.ofEpochMilli(r.at()))).append("</time></td>")
                    .append("<td>").append(esc(who)).append("</td>")
                    .append("<td>").append(esc(did)).append("</td>")
                    .append("<td>").append(esc(about));
            if (mc != null) h.append(" <span class=mc-chip>&#9935; ").append(esc(mc)).append("</span>");
            h.append("</td>")
                    .append("<td><details class=raw-payload><summary>raw</summary><code>")
                    .append(esc(r.actor())).append(" · ").append(esc(r.action()))
                    .append(" · ").append(esc(r.payloadJson()))
                    .append("</code></details></td>")
                    .append("</tr>");
        }
        h.append("</tbody></table></div>");
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private static String filterField(String name, String label, String value, String placeholder) {
        return "<div class=\"col-sm-6 col-md-3\">"
                + "<label class=\"form-label small mb-1\">" + esc(label) + "</label>"
                + "<input class=\"form-control form-control-sm\" name=" + name
                + " value=\"" + esc(value) + "\""
                + (placeholder == null || placeholder.isBlank() ? "" : " placeholder=\"" + esc(placeholder) + "\"")
                + "></div>";
    }

    /* ---------- humanisation ---------- */

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try { return Json.MAPPER.readTree(json); }
        catch (Exception e) { return null; }
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return "";
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return "";
        return v.isTextual() ? v.asText() : v.toString();
    }

    /**
     * Friendly label for the actor column. For events where the "true" actor is a human
     * (applicant or moderator), surface the human's name instead of the system actor
     * that wrote the row. Falls back to the generic actor label for system-driven events.
     */
    private String friendlyWho(String actor, String action, String targetDiscordId,
                               JsonNode payload, Map<String, String> nameCache) {
        // Decision events: prefer the moderator who decided. Auto-policy / LLM decisions
        // stay attributed to the LLM / bot.
        if ("application_approved".equals(action) || "application_denied".equals(action)) {
            String modName = text(payload, "modName");
            String modId   = text(payload, "modId");
            if (!modName.isEmpty()) {
                return modName + (modId.isEmpty() ? "" : " (" + shortId(modId) + ")");
            }
            String decidedBy = text(payload, "decidedBy");
            if (!decidedBy.isEmpty()) {
                if ("llm".equals(decidedBy)) return "Manifest LLM";
                if ("bot".equals(decidedBy)) return "Warden bot";
                if ("web-mod".equals(decidedBy)) return "Dashboard";
                if (decidedBy.matches("\\d{15,20}")) {
                    String name = resolveName(decidedBy, nameCache);
                    return (name == null ? shortId(decidedBy) : name + " (" + shortId(decidedBy) + ")");
                }
                return decidedBy;
            }
        }

        // Player-driven events: the applicant did this, the bot just logged it.
        if (PLAYER_DRIVEN_ACTIONS.contains(action) && targetDiscordId != null && !targetDiscordId.isBlank()) {
            String name = resolveName(targetDiscordId, nameCache);
            if (name != null) return name + " (" + shortId(targetDiscordId) + ")";
            return shortId(targetDiscordId);
        }

        // Generic mod-action payload (existing behavior for settings_updated, etc).
        String modName = text(payload, "modName");
        String modId   = text(payload, "modId");
        if (!modName.isEmpty()) {
            return modName + (modId.isEmpty() ? "" : " (" + shortId(modId) + ")");
        }

        if (actor == null) return "?";
        return switch (actor) {
            case "bot" -> "Warden bot";
            case "llm" -> "Manifest LLM";
            case "system" -> "System";
            case "web", "web-mod" -> "Dashboard";
            default -> actor.startsWith("ingame:") ? "/warden " + actor.substring("ingame:".length()) : actor;
        };
    }

    private static final java.util.Set<String> PLAYER_DRIVEN_ACTIONS = java.util.Set.of(
            "answer_recorded",
            "application_submitted",
            "rules_accepted",
            "rules_declined",
            "onboarding_linked"
    );

    /** A short, human verb phrase for the action + the salient bits of its payload. */
    private static String friendlyAction(String action, JsonNode payload) {
        if (action == null) return "(unknown)";
        return switch (action) {
            case "settings_updated" -> {
                List<String> changed = readChanged(payload).stream()
                        .map(DashAuditHandlers::friendlySettingsKey)
                        .toList();
                if (changed.isEmpty()) yield "saved settings (no changes)";
                if (changed.size() <= 4) yield "updated settings: " + String.join(", ", changed);
                yield "updated " + changed.size() + " settings ("
                        + String.join(", ", changed.subList(0, 4)) + ", ...)";
            }
            case "question_saved" -> {
                String id = text(payload, "id");
                String prompt = text(payload, "prompt");
                String tail = id.equals("new") ? "added a question" : "saved question #" + id;
                if (!prompt.isEmpty()) tail += ": " + trim(prompt, 80);
                yield tail;
            }
            case "question_deleted" -> "deleted question #" + text(payload, "id");
            case "mod_role_set" -> "set mod role to " + text(payload, "roleId");
            case "reonboard_reset" -> "reset onboarding";
            case "reonboard_replayed" -> "replayed onboarding delivery (" + friendlyVia(text(payload, "via")) + ")";
            case "reonboard_replay_skipped" -> "replay skipped: " + text(payload, "reason");
            case "replay_no_delivery" -> "replay: delivery is off in flow config";
            case "replay_no_entry_method" -> "replay: no entry method enabled";
            case "oauth_login" -> "signed in"
                    + (booleanText(payload, "mod") ? " as mod" : "")
                    + (booleanText(payload, "ownerOrAdmin") ? " (owner/admin)" : "");
            case "application_approved" -> {
                String app = text(payload, "applicationId");
                String by  = text(payload, "decidedBy");
                yield "approved application #" + app + (by.isEmpty() ? "" : " (decided by " + decidedByLabel(by) + ")");
            }
            case "application_denied" -> {
                String app = text(payload, "applicationId");
                String by  = text(payload, "decidedBy");
                yield "denied application #" + app + (by.isEmpty() ? "" : " (decided by " + decidedByLabel(by) + ")");
            }
            case "application_escalated" -> "escalated application #" + text(payload, "applicationId")
                    + maybe(payload, "reasoning", " : ");
            case "application_submitted" -> "submitted application #" + text(payload, "applicationId")
                    + " (" + text(payload, "answerCount") + " answers)";
            case "answer_recorded" -> "answered question #" + text(payload, "questionId");
            case "rules_accepted" -> "accepted the rules";
            case "rules_declined" -> "declined the rules";
            case "onboarding_linked" -> "started onboarding via " + friendlyVia(text(payload, "via"));
            case "onboarding_dm_sent" -> "received the onboarding DM";
            case "onboarding_dm_failed" -> "onboarding DM failed: " + text(payload, "error");
            case "onboarding_channel_sent" -> "got the public welcome post";
            case "onboarding_channel_failed" -> "public welcome post failed: " + text(payload, "error");
            case "onboarding_channel_missing" -> "delivery channel missing (id " + text(payload, "channelId") + ")";
            case "join_recorded_no_delivery" -> "joined (delivery off; no onboarding sent)";
            case "join_no_entry_method" -> "joined (no entry method enabled)";
            case "gated_role_assigned" -> "got the gated role";
            case "gated_role_missing" -> "gated role configured but missing in Discord (id " + text(payload, "roleId") + ")";
            case "gated_role_failed" -> "gated role assignment failed: " + text(payload, "error");
            case "link_code_failed" -> "link code generation failed: " + text(payload, "error");
            case "auto_approve_by_policy" -> "auto-approved (mode: "
                    + io.warden.onboarding.model.TriageMode.labelFromWire(text(payload, "triageMode")) + ")";
            case "escalate_by_policy" -> "escalated to mods (mode: "
                    + io.warden.onboarding.model.TriageMode.labelFromWire(text(payload, "triageMode")) + ")";
            case "manifest_call_failed" -> "Manifest call failed: " + text(payload, "error");
            case "triage_failed" -> "triage failed: " + text(payload, "error");
            case "config_reloaded" -> "reloaded config";
            default -> action.replace('_', ' ');
        };
    }

    /** Friendly label for the "via" payload field on onboarding/reonboard rows. */
    static String friendlyVia(String via) {
        if (via == null || via.isBlank()) return "unknown";
        return switch (via) {
            case "discord_button"  -> "Discord button";
            case "web_code"        -> "web code";
            case "web_oauth"       -> "web OAuth";
            case "dm"              -> "DM";
            case "channel"         -> "public channel";
            case "dm+channel"      -> "DM and public channel";
            case "setup_wizard"    -> "setup wizard";
            default                -> via.replace('_', ' ');
        };
    }

    /**
     * Translate a settings-diff wire key (e.g. {@code "flow.delivery_via_dm"} or
     * {@code "gated_role_id"}) into a friendly label for the audit row. Falls back
     * to dropping the prefix and unscoring if no specific mapping exists.
     */
    static String friendlySettingsKey(String key) {
        if (key == null || key.isBlank()) return "";
        String label = SETTINGS_KEY_LABELS.get(key);
        if (label != null) return label;
        // Fallback: strip "flow." and turn underscores into spaces so we never leak
        // a raw identifier into the audit row.
        String stripped = key.startsWith("flow.") ? key.substring("flow.".length()) : key;
        return stripped.replace('_', ' ');
    }

    private static final Map<String, String> SETTINGS_KEY_LABELS = Map.ofEntries(
            Map.entry("rules_markdown",           "rules markdown"),
            Map.entry("gated_role_id",            "gated role"),
            Map.entry("full_role_id",             "full member role"),
            Map.entry("mod_role_id",              "mod role"),
            Map.entry("welcome_channel_id",       "welcome channel"),
            Map.entry("mod_review_channel_id",    "mod review channel"),
            Map.entry("llm_system_prompt",        "LLM system prompt"),
            Map.entry("llm_auto_approve_threshold", "auto-approve threshold"),
            Map.entry("llm_auto_deny_threshold",  "auto-deny threshold"),
            Map.entry("llm_auto_deny_enabled",    "auto-deny enabled"),
            Map.entry("flow.delivery_via_dm",            "DM delivery"),
            Map.entry("flow.delivery_via_channel",       "channel delivery"),
            Map.entry("flow.delivery_channel_id",        "delivery channel"),
            Map.entry("flow.delivery_message_template",  "delivery message template"),
            Map.entry("flow.entry_via_discord_button",   "Discord button entry"),
            Map.entry("flow.entry_via_web_code",         "web code entry"),
            Map.entry("flow.entry_via_web_oauth",        "web OAuth entry"),
            Map.entry("flow.gating_enabled",             "gating"),
            Map.entry("flow.triage_mode",                "triage mode"),
            Map.entry("flow.approve_dm_enabled",         "approve DM"),
            Map.entry("flow.approve_dm_template",        "approve DM template"),
            Map.entry("flow.approve_channel_announce",   "approve channel announce"),
            Map.entry("flow.approve_channel_template",   "approve channel template"),
            Map.entry("flow.approve_extra_roles",        "extra approve roles"),
            Map.entry("flow.deny_dm_enabled",            "deny DM"),
            Map.entry("flow.deny_dm_template",           "deny DM template"),
            Map.entry("flow.deny_action",                "deny action")
    );

    /** Target column: resolve target discord id to username when we have one. */
    private String friendlyTarget(String discordId, JsonNode payload, Map<String, String> cache) {
        if (discordId == null || discordId.isBlank()) return "";
        String name = resolveName(discordId, cache);
        if (name == null) return shortId(discordId);
        return name + " (" + shortId(discordId) + ")";
    }

    private static String resolveMc(String discordId, io.warden.discord.DiscordSrvBridge srv,
                                    Map<String, String> cache) {
        if (cache.containsKey(discordId)) return cache.get(discordId);
        String mc = srv.mcNameFor(discordId).orElse(null);
        cache.put(discordId, mc);
        return mc;
    }

    private String resolveName(String discordId, Map<String, String> cache) {
        if (discordId == null || discordId.isBlank()) return null;
        if (cache.containsKey(discordId)) return cache.get(discordId);
        String name = null;
        try {
            UserRecord u = services.userDao.findByDiscordId(discordId).orElse(null);
            if (u != null && u.username() != null && !u.username().isBlank()) name = u.username();
        } catch (SQLException e) {
            // best-effort; leave null
        }
        cache.put(discordId, name);
        return name;
    }

    private static String decidedByLabel(String by) {
        if (by == null || by.isBlank()) return "?";
        if (by.equals("llm")) return "LLM";
        if (by.equals("bot")) return "bot";
        if (by.equals("web-mod")) return "dashboard mod";
        return shortId(by);
    }

    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() > 8 ? id.substring(0, 6) + "…" : id;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String maybe(JsonNode payload, String field, String prefix) {
        String v = text(payload, field);
        if (v.isEmpty()) return "";
        return prefix + trim(v, 120);
    }

    private static boolean booleanText(JsonNode payload, String field) {
        if (payload == null) return false;
        JsonNode v = payload.get(field);
        if (v == null) return false;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual()) return "true".equalsIgnoreCase(v.asText());
        return false;
    }

    private static List<String> readChanged(JsonNode payload) {
        if (payload == null) return List.of();
        JsonNode arr = payload.get("changed");
        if (arr == null || !arr.isArray()) return List.of();
        List<String> out = new java.util.ArrayList<>(arr.size());
        for (JsonNode el : arr) out.add(el.isTextual() ? el.asText() : el.toString());
        return out;
    }

    private static String esc(String s) {
        return Layout.escape(s);
    }
}
