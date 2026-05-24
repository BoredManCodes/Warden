package io.warden.web.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.warden.Services;
import io.warden.llm.ManifestClient;
import io.warden.onboarding.model.Settings;
import io.warden.tickets.Ticket;
import io.warden.tickets.TicketCategory;
import io.warden.tickets.TicketMessage;
import io.warden.web.auth.AuditActor;
import io.warden.web.auth.DashAuth;
import io.warden.web.auth.SessionCookie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Dashboard-wide AI helpers that surface the configured LLM gateway as a set
 * of one-click drafting tools next to free-text fields.
 *
 * Each endpoint is JSON in/out, gated to logged-in dashboard sessions, and
 * audits both the request and outcome. Endpoints fail fast (no retries) so
 * the UI can render a "try again" affordance without making the operator wait.
 */
public final class DashAiHandlers {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Services services;

    public DashAiHandlers(Services services) {
        this.services = services;
    }

    /* ============================================================
     * Autoresponder: turn a plain-English description into a Java regex
     * ============================================================ */

    public void autoresponderRegex(Context ctx) throws Exception {
        SessionCookie.Session sess = requireConfigSession(ctx);
        if (sess == null) return;

        String brief = str(ctx, "brief");
        if (brief.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "message", "describe what should trigger the rule"));
            return;
        }
        if (brief.length() > 1500) brief = brief.substring(0, 1500);

        Settings s = services.settingsDao.get();
        if (!s.llmConfigured()) {
            ctx.status(400).json(Map.of("ok", false, "message",
                    "AI gateway isn't configured. Set the API key on the AI tab first."));
            return;
        }

        String sysPrompt = "You convert a plain-English description of a Discord chat trigger into a "
                + "Java-flavoured regular expression suitable for java.util.regex.Pattern. "
                + "Output ONLY a JSON object with these keys: "
                + "pattern (string, the regex; no surrounding slashes, no /flags), "
                + "case_insensitive (boolean), "
                + "explanation (one short sentence describing what the regex matches). "
                + "Rules: prefer word boundaries (\\\\b) so the regex doesn't match inside unrelated words; "
                + "escape literal punctuation; "
                + "use non-capturing groups (?:...) unless a capture is genuinely useful; "
                + "avoid catastrophic backtracking (no nested quantifiers like (a+)+). "
                + "Use named/numbered captures sparingly because they expose {match.1}, {match.2}, ... placeholders. "
                + "Do not wrap the JSON in markdown fences.";

        String userPrompt = "Trigger description:\n" + brief;
        var endpoint = endpoint(s);
        try {
            String reply = services.manifest.requestText(endpoint, sysPrompt, userPrompt, 0, 0L, 0L);
            JsonNode parsed = parseJsonObject(reply);
            if (parsed == null) {
                ctx.status(502).json(Map.of("ok", false,
                        "message", "AI reply didn't contain a JSON object. Try again."));
                return;
            }
            String pattern = parsed.path("pattern").asText("").trim();
            if (pattern.isEmpty()) {
                ctx.status(502).json(Map.of("ok", false, "message", "AI returned an empty pattern."));
                return;
            }
            try {
                java.util.regex.Pattern.compile(pattern);
            } catch (Exception ex) {
                ctx.status(502).json(Map.of("ok", false,
                        "message", "AI returned an invalid regex: " + ex.getMessage()));
                return;
            }
            boolean ci = parsed.path("case_insensitive").asBoolean(true);
            String explain = parsed.path("explanation").asText("").trim();
            services.audit.write("web", "ai_autoresponder_regex", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, Map.of("brief_len", brief.length(), "pattern_len", pattern.length())));
            ctx.json(Map.of(
                    "ok", true,
                    "pattern", pattern,
                    "case_insensitive", ci,
                    "explanation", explain));
        } catch (ManifestClient.ManifestException e) {
            ctx.status(502).json(Map.of("ok", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /* ============================================================
     * Autoresponder: draft a canned reply for a given trigger
     * ============================================================ */

    public void autoresponderResponse(Context ctx) throws Exception {
        SessionCookie.Session sess = requireConfigSession(ctx);
        if (sess == null) return;

        String trigger = str(ctx, "trigger");
        String intent = str(ctx, "intent");
        String tone = str(ctx, "tone");
        String mode = str(ctx, "mode");
        if (mode.isBlank()) mode = "content";

        if (intent.isBlank() && trigger.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "message", "describe what the response should say"));
            return;
        }
        if (intent.length() > 2000) intent = intent.substring(0, 2000);
        if (trigger.length() > 500) trigger = trigger.substring(0, 500);

        Settings s = services.settingsDao.get();
        if (!s.llmConfigured()) {
            ctx.status(400).json(Map.of("ok", false, "message", "AI gateway isn't configured."));
            return;
        }

        boolean embed = "embed".equalsIgnoreCase(mode);
        String shape = embed
                ? "Return ONLY a JSON object with keys: title (short, 2 to 8 words), "
                + "description (1 to 3 short sentences, supports basic markdown), "
                + "content (optional one-line plain message rendered above the embed; may be empty). "
                + "Do not wrap the JSON in markdown fences."
                : "Return ONLY a JSON object with one key: content (the plain message, 1 to 3 short sentences). "
                + "Do not wrap the JSON in markdown fences.";
        String sysPrompt = "You draft a Discord chat autoresponder. The operator describes what the bot "
                + "should say when a trigger fires. Keep the message short and natural - this is a chat reply, "
                + "not a help-desk article. " + shape + " Available placeholders the operator can keep in the "
                + "draft: {user_mention}, {user}, {channel_name}, {server}. Don't invent new placeholders. "
                + (tone.isBlank() ? "" : "Tone preference: " + tone + ". ")
                + "No preamble, no quotation marks around the whole reply.";

        StringBuilder up = new StringBuilder();
        if (!trigger.isBlank()) up.append("Trigger pattern (what users type):\n").append(trigger).append("\n\n");
        up.append("What the response should communicate:\n").append(intent.isBlank() ? trigger : intent);
        var endpoint = endpoint(s);
        try {
            String reply = services.manifest.requestText(endpoint, sysPrompt, up.toString(), 0, 0L, 0L);
            JsonNode parsed = parseJsonObject(reply);
            if (parsed == null) {
                String fallback = stripWrap(reply);
                if (fallback.isEmpty()) {
                    ctx.status(502).json(Map.of("ok", false, "message", "AI returned an empty draft."));
                    return;
                }
                ctx.json(Map.of("ok", true, "content", fallback, "title", "", "description", ""));
                return;
            }
            services.audit.write("web", "ai_autoresponder_response", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, Map.of("mode", mode)));
            ctx.json(Map.of(
                    "ok", true,
                    "content", parsed.path("content").asText("").trim(),
                    "title", parsed.path("title").asText("").trim(),
                    "description", parsed.path("description").asText("").trim()));
        } catch (ManifestClient.ManifestException e) {
            ctx.status(502).json(Map.of("ok", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /* ============================================================
     * Tickets: suggest a staff reply for the current ticket thread
     * ============================================================ */

    public void ticketReply(Context ctx) throws Exception {
        SessionCookie.Session sess = requireSession(ctx);
        if (sess == null) return;

        long ticketId;
        try { ticketId = Long.parseLong(ctx.pathParam("id")); }
        catch (Exception e) { ctx.status(400).json(Map.of("ok", false, "message", "bad ticket id")); return; }

        Optional<Ticket> opt = services.tickets.tickets().find(ticketId);
        if (opt.isEmpty()) {
            ctx.status(404).json(Map.of("ok", false, "message", "ticket not found"));
            return;
        }
        Ticket t = opt.get();

        Settings s = services.settingsDao.get();
        if (!s.llmConfigured()) {
            ctx.status(400).json(Map.of("ok", false, "message", "AI gateway isn't configured."));
            return;
        }

        String guidance = str(ctx, "guidance");
        if (guidance.length() > 1500) guidance = guidance.substring(0, 1500);

        TicketCategory cat = t.categoryId() == null ? null
                : services.tickets.categories().find(t.categoryId()).orElse(null);
        List<TicketMessage> msgs = services.tickets.tickets().messages(ticketId);

        StringBuilder thread = new StringBuilder();
        thread.append("Ticket subject: ").append(safe(t.subject())).append('\n');
        if (cat != null) thread.append("Category: ").append(safe(cat.name())).append('\n');
        thread.append("Status: ").append(t.status().wire()).append("\n\n");
        thread.append("Opening message:\n")
                .append(safe(displayName(t))).append(": ").append(safe(t.body())).append("\n\n");
        thread.append("Conversation so far:\n");
        int rendered = 0;
        for (TicketMessage m : msgs) {
            if (TicketMessage.KIND_SYSTEM.equals(m.authorKind())) continue;
            String who = switch (m.authorKind() == null ? "" : m.authorKind()) {
                case TicketMessage.KIND_STAFF -> "STAFF " + safe(m.authorName());
                case TicketMessage.KIND_INTERNAL -> "INTERNAL NOTE " + safe(m.authorName());
                default -> "USER " + safe(m.authorName());
            };
            String body = safe(m.body());
            if (body.isBlank()) continue;
            if (body.length() > 2000) body = body.substring(0, 2000) + " [truncated]";
            thread.append("- ").append(who).append(": ").append(body).append('\n');
            rendered++;
            if (rendered >= 40) {
                thread.append("- [older messages omitted]\n");
                break;
            }
        }
        if (!guidance.isBlank()) {
            thread.append("\nStaff guidance for this draft:\n").append(guidance);
        }

        String sysPrompt = "You draft a polite, helpful reply that a staff member can edit and send back to "
                + "a Discord support ticket reporter. Match a warm, plainspoken tone. "
                + "Address the user directly using 'you'. Acknowledge what they reported before answering. "
                + "Keep it concise - usually 2 to 5 sentences. Do not impersonate the user. "
                + "Do not invent facts about server policy you weren't given; if you don't know, say staff will follow up. "
                + "Do not add greetings like 'Hi NAME,' or sign-offs like 'Best,'. "
                + "Reply with ONLY the message body - no preamble, no quotes, no markdown fences.";
        var endpoint = endpoint(s);
        try {
            String draft = services.manifest.requestText(endpoint, sysPrompt, thread.toString(), 0, 0L, 0L);
            draft = stripWrap(draft);
            if (draft.isEmpty()) {
                ctx.status(502).json(Map.of("ok", false, "message", "AI returned an empty draft."));
                return;
            }
            services.audit.write("web", "ai_ticket_reply", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, Map.of("ticket_id", ticketId, "draft_len", draft.length())));
            ctx.json(Map.of("ok", true, "draft", draft));
        } catch (ManifestClient.ManifestException e) {
            ctx.status(502).json(Map.of("ok", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /* ============================================================
     * AutoMod: turn a brief into a banned-word list
     * ============================================================ */

    public void automodBannedWords(Context ctx) throws Exception {
        SessionCookie.Session sess = requireConfigSession(ctx);
        if (sess == null) return;

        String brief = str(ctx, "brief");
        if (brief.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "message", "describe the categories you want to ban"));
            return;
        }
        if (brief.length() > 1500) brief = brief.substring(0, 1500);

        Settings s = services.settingsDao.get();
        if (!s.llmConfigured()) {
            ctx.status(400).json(Map.of("ok", false, "message", "AI gateway isn't configured."));
            return;
        }

        String sysPrompt = "You expand a moderator's brief into a list of words/phrases that an automated chat "
                + "filter should match. Output ONLY a JSON object: {\"words\": [\"...\", ...]}. "
                + "Rules: include common variants and obvious leetspeak (e.g. @ for a, 0 for o, 3 for e), "
                + "but skip extreme slurs - return them as the base form; the filter is case-insensitive. "
                + "Stay under 80 entries. Avoid words that have benign meanings ('damn' in 'damnation') unless "
                + "the brief explicitly asks for them. Don't repeat. No markdown, no commentary.";
        var endpoint = endpoint(s);
        try {
            String reply = services.manifest.requestText(endpoint, sysPrompt, "Brief:\n" + brief, 0, 0L, 0L);
            JsonNode parsed = parseJsonObject(reply);
            List<String> words = new ArrayList<>();
            if (parsed != null && parsed.path("words").isArray()) {
                for (JsonNode w : parsed.path("words")) {
                    String t = w.asText("").trim();
                    if (!t.isEmpty() && t.length() <= 64) words.add(t);
                    if (words.size() >= 200) break;
                }
            }
            if (words.isEmpty()) {
                // Tolerate a bare list - some models ignore the JSON envelope.
                for (String line : reply.split("\\r?\\n")) {
                    String t = line.replaceAll("^[\\-*\\d.\\s\"']+", "").replaceAll("[\"']+\\s*$", "").trim();
                    if (!t.isEmpty() && !t.startsWith("{") && !t.startsWith("[") && t.length() <= 64) words.add(t);
                    if (words.size() >= 200) break;
                }
            }
            if (words.isEmpty()) {
                ctx.status(502).json(Map.of("ok", false, "message", "AI didn't return any words."));
                return;
            }
            services.audit.write("web", "ai_automod_words", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, Map.of("count", words.size())));
            ctx.json(Map.of("ok", true, "words", words));
        } catch (ManifestClient.ManifestException e) {
            ctx.status(502).json(Map.of("ok", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /* ============================================================
     * Scheduler: draft an event description from a brief
     * ============================================================ */

    public void eventDraft(Context ctx) throws Exception {
        SessionCookie.Session sess = requireSession(ctx);
        if (sess == null) return;

        String brief = str(ctx, "brief");
        String existingTitle = str(ctx, "title");
        if (brief.isBlank() && existingTitle.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "message", "describe the event"));
            return;
        }
        if (brief.length() > 1500) brief = brief.substring(0, 1500);

        Settings s = services.settingsDao.get();
        if (!s.llmConfigured()) {
            ctx.status(400).json(Map.of("ok", false, "message", "AI gateway isn't configured."));
            return;
        }

        String sysPrompt = "You draft a community event listing for a Minecraft / Discord server. "
                + "Output ONLY a JSON object with keys: title (3 to 8 words, no emoji), "
                + "description (2 to 4 sentences - what is happening, who it's for, anything attendees need to do "
                + "or bring). Match a warm, plainspoken tone. No markdown fences, no commentary.";
        StringBuilder up = new StringBuilder();
        if (!existingTitle.isBlank()) up.append("Current title: ").append(existingTitle).append('\n');
        up.append("Brief:\n").append(brief.isBlank() ? existingTitle : brief);

        var endpoint = endpoint(s);
        try {
            String reply = services.manifest.requestText(endpoint, sysPrompt, up.toString(), 0, 0L, 0L);
            JsonNode parsed = parseJsonObject(reply);
            String title = "";
            String desc = "";
            if (parsed != null) {
                title = parsed.path("title").asText("").trim();
                desc = parsed.path("description").asText("").trim();
            }
            if (title.isEmpty() && desc.isEmpty()) {
                desc = stripWrap(reply);
            }
            if (title.isEmpty() && desc.isEmpty()) {
                ctx.status(502).json(Map.of("ok", false, "message", "AI returned an empty draft."));
                return;
            }
            services.audit.write("web", "ai_event_draft", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, Map.of("brief_len", brief.length())));
            ctx.json(Map.of("ok", true, "title", title, "description", desc));
        } catch (ManifestClient.ManifestException e) {
            ctx.status(502).json(Map.of("ok", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /* ============================================================
     * Alerts: polish an alert message or draft from a brief
     * ============================================================ */

    public void alertPolish(Context ctx) throws Exception {
        SessionCookie.Session sess = requireConfigSession(ctx);
        if (sess == null) return;

        String text = str(ctx, "text");
        String brief = str(ctx, "brief");
        String kind = str(ctx, "kind");
        if (kind.isBlank()) kind = "content";

        if (text.isBlank() && brief.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "message", "nothing to polish"));
            return;
        }
        if (text.length() > 4000) text = text.substring(0, 4000);
        if (brief.length() > 1500) brief = brief.substring(0, 1500);

        Settings s = services.settingsDao.get();
        if (!s.llmConfigured()) {
            ctx.status(400).json(Map.of("ok", false, "message", "AI gateway isn't configured."));
            return;
        }

        String guidance = switch (kind.toLowerCase(Locale.ROOT)) {
            case "embed_title" -> "Output a single short embed title (3 to 8 words). No quotes.";
            case "embed_description" -> "Output an embed body of 1 to 3 short sentences. Markdown is fine.";
            default -> "Output a single chat-friendly message of 1 to 2 short sentences. No quotes.";
        };
        String sysPrompt = "You polish alert messages that a Minecraft server posts to Discord when an in-game "
                + "event fires (joins, deaths, achievements, etc.). Preserve any {variables} or %placeholder% "
                + "tokens exactly as written - they're substituted server-side. Match a friendly, plainspoken tone. "
                + "Reply with ONLY the rewritten text - no preamble, no quotes, no markdown fences. " + guidance;

        StringBuilder up = new StringBuilder();
        if (!text.isBlank()) up.append("Existing text:\n").append(text).append("\n\n");
        if (!brief.isBlank()) up.append("What the operator wants:\n").append(brief);
        if (up.length() == 0) up.append(text);

        var endpoint = endpoint(s);
        try {
            String reply = services.manifest.requestText(endpoint, sysPrompt, up.toString(), 0, 0L, 0L);
            String out = stripWrap(reply);
            if (out.isEmpty()) {
                ctx.status(502).json(Map.of("ok", false, "message", "AI returned an empty draft."));
                return;
            }
            services.audit.write("web", "ai_alert_polish", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, Map.of("kind", kind, "out_len", out.length())));
            ctx.json(Map.of("ok", true, "polished", out));
        } catch (ManifestClient.ManifestException e) {
            ctx.status(502).json(Map.of("ok", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /* ============================================================
     * Helpers
     * ============================================================ */

    private SessionCookie.Session requireSession(Context ctx) {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null) {
            ctx.status(401).json(Map.of("ok", false, "message", "sign in first"));
            return null;
        }
        return sess;
    }

    private SessionCookie.Session requireConfigSession(Context ctx) {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null || !sess.canEditConfig()) {
            ctx.status(401).json(Map.of("ok", false, "message", "config admin only"));
            return null;
        }
        return sess;
    }

    private static ManifestClient.Endpoint endpoint(Settings s) {
        return new ManifestClient.Endpoint(s.llmApiKey(), s.llmBaseUrl(), s.llmModel());
    }

    private static String str(Context ctx, String key) {
        String v = ctx.formParam(key);
        if (v == null) v = ctx.queryParam(key);
        return v == null ? "" : v.trim();
    }

    private static String safe(String s) {
        if (s == null) return "";
        // Collapse runs of whitespace so the prompt stays compact, but keep newlines.
        return s.replaceAll("[\\t ]+", " ").trim();
    }

    private static String displayName(Ticket t) {
        if (t.discordUsername() != null && !t.discordUsername().isBlank()) return t.discordUsername();
        return "user " + t.discordId();
    }

    private static JsonNode parseJsonObject(String reply) {
        if (reply == null) return null;
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode n = JSON.readTree(reply.substring(start, end + 1));
            return n.isObject() ? n : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Strip wrapping quotes, code fences, or "Answer:" / "Title:" prefixes the LLM sometimes adds. */
    static String stripWrap(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        if (t.matches("(?is)^(title|question|answer|polished|result|response|reply)\\s*[:\\-]\\s*.+")) {
            int idx = -1;
            int colon = t.indexOf(':');
            int dash = t.indexOf('-');
            if (colon >= 0) idx = colon;
            if (dash >= 0 && (idx < 0 || dash < idx)) idx = dash;
            if (idx >= 0 && idx + 1 < t.length()) t = t.substring(idx + 1).trim();
        }
        if (t.length() >= 2) {
            char a = t.charAt(0), b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }
}
