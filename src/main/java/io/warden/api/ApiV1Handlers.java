package io.warden.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.warden.Services;
import io.warden.data.Json;
import io.warden.discord.DiscordService;
import io.warden.discord.DiscordSrvBridge;
import io.warden.onboarding.model.Application;
import io.warden.onboarding.model.UserRecord;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bearer-token-authenticated JSON API at /api/v1. Each endpoint declares the
 * {@link ApiScope} it accepts and returns 401 / 403 when the token is missing,
 * unknown, revoked, or under-scoped.
 *
 * Conventions:
 *   - Responses are always JSON objects, even for lists (wrapped in
 *     {@code {"items": [...]}}) so callers can extend the envelope later.
 *   - 4xx errors return {@code {"error": "kind", "message": "..."}}.
 *   - Discord IDs and other long numbers are emitted as JSON strings to keep
 *     JavaScript clients happy.
 */
public final class ApiV1Handlers {

    private final Services services;
    private final DiscordService discord;
    private final ApiKeyService apiKeys;

    public ApiV1Handlers(Services services, DiscordService discord) {
        this.services = services;
        this.discord = discord;
        this.apiKeys = services.apiKeys;
    }

    /* ---------------------------------------------------------------------
     * Auth helpers
     * ------------------------------------------------------------------- */

    private Optional<ApiKey> authenticate(Context ctx) {
        String auth = ctx.header("Authorization");
        if (auth == null || auth.isBlank()) return Optional.empty();
        String token = auth;
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        return apiKeys.verify(token);
    }

    private boolean require(Context ctx, ApiScope scope) {
        Optional<ApiKey> key = authenticate(ctx);
        if (key.isEmpty()) {
            unauthorized(ctx, "Missing or invalid API key. Send 'Authorization: Bearer <key>'.");
            return false;
        }
        ApiKey k = key.get();
        if (!k.hasScope(scope)) {
            forbidden(ctx, "API key is missing required scope: " + scope.key());
            return false;
        }
        ctx.attribute("warden.api.key", k);
        return true;
    }

    private static void unauthorized(Context ctx, String msg) {
        ctx.status(HttpStatus.UNAUTHORIZED);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("error", "unauthorized");
        n.put("message", msg);
        ctx.json(n);
    }

    private static void forbidden(Context ctx, String msg) {
        ctx.status(HttpStatus.FORBIDDEN);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("error", "forbidden");
        n.put("message", msg);
        ctx.json(n);
    }

    private static void badRequest(Context ctx, String msg) {
        ctx.status(HttpStatus.BAD_REQUEST);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("error", "bad_request");
        n.put("message", msg);
        ctx.json(n);
    }

    private static void notFound(Context ctx, String msg) {
        ctx.status(HttpStatus.NOT_FOUND);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("error", "not_found");
        n.put("message", msg);
        ctx.json(n);
    }

    private static int qpInt(Context ctx, String name, int def, int min, int max) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            int v = Integer.parseInt(raw);
            if (v < min) return min;
            if (v > max) return max;
            return v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /* ---------------------------------------------------------------------
     * Index / health
     * ------------------------------------------------------------------- */

    /** Public index - lists modules + scopes so integrators can discover the surface. */
    public void index(Context ctx) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("name", "Warden API");
        root.put("version", "v1");
        ArrayNode scopes = root.putArray("scopes");
        for (ApiScope s : ApiScope.values()) {
            ObjectNode o = scopes.addObject();
            o.put("key", s.key());
            o.put("label", s.label());
        }
        ArrayNode modules = root.putArray("modules");
        addModule(modules, "health",          "/api/v1/health");
        addModule(modules, "members",         "/api/v1/members");
        addModule(modules, "audit",           "/api/v1/audit");
        addModule(modules, "onboarding",      "/api/v1/onboarding/applications");
        addModule(modules, "moderation",      "/api/v1/moderation/warnings");
        addModule(modules, "mod-actions",     "/api/v1/moderation/actions");
        addModule(modules, "levels",          "/api/v1/levels/leaderboard");
        addModule(modules, "tickets",         "/api/v1/tickets");
        addModule(modules, "feedback",        "/api/v1/feedback");
        addModule(modules, "reaction-roles",  "/api/v1/reaction-roles");
        addModule(modules, "engagement",      "/api/v1/engagement/polls");
        addModule(modules, "autoresponders",  "/api/v1/autoresponders");
        addModule(modules, "alerts",          "/api/v1/alerts");
        addModule(modules, "scheduler",       "/api/v1/scheduler/events");
        addModule(modules, "timezones",       "/api/v1/timezones");
        addModule(modules, "analytics",       "/api/v1/analytics/overview");
        addModule(modules, "players",         "/api/v1/players/{username}");
        addModule(modules, "discord-link",    "/api/v1/discord/name/{username}");
        ctx.json(root);
    }

    private static void addModule(ArrayNode arr, String name, String href) {
        ObjectNode o = arr.addObject();
        o.put("name", name);
        o.put("href", href);
    }

    public void health(Context ctx) {
        if (!require(ctx, ApiScope.READ_HEALTH)) return;
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("status", "ok");
        n.put("discord_ready", discord != null && discord.isReady());
        n.put("web_ssl_active", services.config.ssl().usable());
        n.put("server_now", System.currentTimeMillis());
        ctx.json(n);
    }

    /* ---------------------------------------------------------------------
     * Members
     * ------------------------------------------------------------------- */

    public void listMembers(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_MEMBERS)) return;
        int limit = qpInt(ctx, "limit", 100, 1, 1000);
        List<UserRecord> users = services.userDao.listAll(limit);
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("total", services.userDao.countAll());
        ArrayNode arr = root.putArray("items");
        for (UserRecord u : users) arr.add(toJson(u));
        ctx.json(root);
    }

    public void getMember(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_MEMBERS)) return;
        String did = ctx.pathParam("discordId");
        Optional<UserRecord> u = services.userDao.findByDiscordId(did);
        if (u.isEmpty()) { notFound(ctx, "Unknown member"); return; }
        ctx.json(toJson(u.get()));
    }

    private static ObjectNode toJson(UserRecord u) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("discord_id", u.discordId());
        n.put("username", u.username());
        n.put("joined_at", u.joinedAt());
        n.put("state", u.state() == null ? null : u.state().wireName());
        n.put("updated_at", u.updatedAt());
        return n;
    }

    /* ---------------------------------------------------------------------
     * Audit
     * ------------------------------------------------------------------- */

    public void listAudit(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_AUDIT)) return;
        int limit = qpInt(ctx, "limit", 100, 1, 1000);
        String actor = ctx.queryParam("actor");
        String action = ctx.queryParam("action");
        String target = ctx.queryParam("target");
        var rows = services.auditDao.listFiltered(actor, action, target, limit);
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var e : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", e.id());
            o.put("actor", e.actor());
            o.put("action", e.action());
            o.put("target_discord_id", e.targetDiscordId());
            o.put("at", e.at());
            try {
                JsonNode payload = e.payloadJson() == null || e.payloadJson().isBlank()
                        ? Json.MAPPER.createObjectNode()
                        : Json.MAPPER.readTree(e.payloadJson());
                o.set("payload", payload);
            } catch (Exception ex) {
                o.put("payload_raw", e.payloadJson());
            }
        }
        ctx.json(root);
    }

    /* ---------------------------------------------------------------------
     * Onboarding
     * ------------------------------------------------------------------- */

    public void listPending(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_ONBOARDING)) return;
        var rows = services.applicationDao.listPending();
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("count", rows.size());
        ArrayNode arr = root.putArray("items");
        for (Application a : rows) arr.add(toJson(a));
        ctx.json(root);
    }

    public void listApplications(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_ONBOARDING)) return;
        String did = ctx.queryParam("discord_id");
        if (did == null || did.isBlank()) {
            badRequest(ctx, "Supply ?discord_id=...");
            return;
        }
        var rows = services.applicationDao.listFor(did);
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("discord_id", did);
        ArrayNode arr = root.putArray("items");
        for (Application a : rows) arr.add(toJson(a));
        ctx.json(root);
    }

    public void getApplication(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_ONBOARDING)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        var a = services.applicationDao.findById(id);
        if (a.isEmpty()) { notFound(ctx, "Unknown application"); return; }
        Application app = a.get();
        ObjectNode out = toJson(app);
        ArrayNode answers = out.putArray("answers");
        for (var ans : services.answerDao.listFor(app.discordId())) {
            ObjectNode o = answers.addObject();
            o.put("question_id", ans.questionId());
            o.put("value_json", io.warden.data.Json.writeAnswer(ans.value()));
            o.put("submitted_at", ans.submittedAt());
        }
        ctx.json(out);
    }

    public void approveApplication(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_ONBOARDING)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        String note = optString(ctx, "note");
        services.decisionService().applyManualApprove(id, "api", note);
        ctx.json(okJson("approved"));
    }

    public void denyApplication(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_ONBOARDING)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        String note = optString(ctx, "note");
        String userDm = optString(ctx, "user_dm");
        services.decisionService().applyManualDeny(id, "api", note, userDm);
        ctx.json(okJson("denied"));
    }

    private static ObjectNode toJson(Application a) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("id", a.id());
        n.put("discord_id", a.discordId());
        n.put("submitted_at", a.submittedAt());
        n.put("llm_decision", a.llmDecision());
        if (a.llmConfidenceX1000() != null) n.put("llm_confidence", a.llmConfidenceX1000() / 1000.0);
        n.put("llm_reasoning", a.llmReasoning());
        n.put("final_decision", a.finalDecision());
        n.put("decided_by", a.decidedBy());
        if (a.decidedAt() != null) n.put("decided_at", a.decidedAt());
        n.put("mod_note", a.modNote());
        n.put("pending_review", a.pendingModReview());
        return n;
    }

    /* ---------------------------------------------------------------------
     * Moderation
     * ------------------------------------------------------------------- */

    public void listWarnings(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_MODERATION)) return;
        String did = ctx.queryParam("discord_id");
        int limit = qpInt(ctx, "limit", 100, 1, 1000);
        List<io.warden.moderation.WarningDao.Warning> rows = (did == null || did.isBlank())
                ? services.warningDao.listRecent(limit)
                : services.warningDao.listFor(did);
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var w : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", w.id());
            o.put("discord_id", w.discordId());
            o.put("moderator_id", w.moderatorId());
            o.put("reason", w.reason());
            o.put("severity", w.severity());
            o.put("created_at", w.createdAt());
            if (w.clearedAt() != null) o.put("cleared_at", w.clearedAt());
        }
        ctx.json(root);
    }

    public void listModActions(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_MODERATION)) return;
        String did = ctx.queryParam("discord_id");
        int limit = qpInt(ctx, "limit", 100, 1, 1000);
        List<io.warden.moderation.ModActionDao.ModAction> rows = (did == null || did.isBlank())
                ? services.modActionDao.listRecent(limit)
                : services.modActionDao.listFor(did);
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var m : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", m.id());
            o.put("action", m.action());
            o.put("target_discord_id", m.targetDiscordId());
            o.put("moderator_id", m.moderatorId());
            o.put("reason", m.reason());
            if (m.durationSeconds() != null) o.put("duration_seconds", m.durationSeconds());
            if (m.expiresAt() != null) o.put("expires_at", m.expiresAt());
            if (m.revokedAt() != null) o.put("revoked_at", m.revokedAt());
            o.put("created_at", m.createdAt());
        }
        ctx.json(root);
    }

    public void createWarning(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_MODERATION)) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String did = body.path("discord_id").asText("");
        if (did.isBlank()) { badRequest(ctx, "discord_id is required"); return; }
        String reason = body.path("reason").asText("");
        int severity = Math.max(0, Math.min(10, body.path("severity").asInt(1)));
        long id = services.moderation.warn(did, "api", reason, severity);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("ok", true);
        n.put("warning_id", id);
        ctx.json(n);
    }

    public void clearWarning(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_MODERATION)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        services.warningDao.clear(id);
        ctx.json(okJson("cleared"));
    }

    public void kickMember(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_MODERATION)) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String did = body.path("discord_id").asText("");
        if (did.isBlank()) { badRequest(ctx, "discord_id is required"); return; }
        Guild g = requireGuild(ctx);
        if (g == null) return;
        String reason = body.path("reason").asText("");
        services.moderation.kick(g, did, "api", reason);
        ctx.json(okJson("kick queued"));
    }

    public void banMember(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_MODERATION)) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String did = body.path("discord_id").asText("");
        if (did.isBlank()) { badRequest(ctx, "discord_id is required"); return; }
        Guild g = requireGuild(ctx);
        if (g == null) return;
        String reason = body.path("reason").asText("");
        int days = Math.max(0, Math.min(7, body.path("delete_message_days").asInt(0)));
        services.moderation.ban(g, did, "api", reason, days);
        ctx.json(okJson("ban queued"));
    }

    public void tempbanMember(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_MODERATION)) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String did = body.path("discord_id").asText("");
        if (did.isBlank()) { badRequest(ctx, "discord_id is required"); return; }
        int seconds = body.path("duration_seconds").asInt(0);
        if (seconds <= 0) { badRequest(ctx, "duration_seconds must be > 0"); return; }
        Guild g = requireGuild(ctx);
        if (g == null) return;
        String reason = body.path("reason").asText("");
        services.moderation.tempban(g, did, "api", reason, seconds);
        ctx.json(okJson("tempban queued"));
    }

    public void timeoutMember(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_MODERATION)) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String did = body.path("discord_id").asText("");
        if (did.isBlank()) { badRequest(ctx, "discord_id is required"); return; }
        int seconds = body.path("duration_seconds").asInt(0);
        if (seconds <= 0) { badRequest(ctx, "duration_seconds must be > 0"); return; }
        Guild g = requireGuild(ctx);
        if (g == null) return;
        String reason = body.path("reason").asText("");
        services.moderation.timeout(g, did, "api", reason, seconds);
        ctx.json(okJson("timeout queued"));
    }

    public void unbanMember(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_MODERATION)) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String did = body.path("discord_id").asText("");
        if (did.isBlank()) { badRequest(ctx, "discord_id is required"); return; }
        Guild g = requireGuild(ctx);
        if (g == null) return;
        String reason = body.path("reason").asText("api unban");
        g.unban(UserSnowflake.fromId(did)).reason(safeReason(reason)).queue(
                ok -> services.moderation.recordAction("unban", did, "api", reason, null, null),
                err -> {});
        ctx.json(okJson("unban queued"));
    }

    /* ---------------------------------------------------------------------
     * Levels
     * ------------------------------------------------------------------- */

    public void leaderboard(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_LEVELS)) return;
        int limit = qpInt(ctx, "limit", 100, 1, 1000);
        var rows = services.levelUserDao.top(limit);
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("total_users", services.levelUserDao.totalUsers());
        ArrayNode arr = root.putArray("items");
        int rank = 1;
        for (var u : rows) {
            ObjectNode o = arr.addObject();
            o.put("rank", rank++);
            o.put("discord_id", u.discordId());
            o.put("xp", u.xp());
            o.put("level", u.level());
            o.put("messages", u.messages());
            o.put("last_grant_at", u.lastGrantAt());
        }
        ctx.json(root);
    }

    public void getLevel(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_LEVELS)) return;
        String did = ctx.pathParam("discordId");
        var u = services.levelUserDao.find(did);
        if (u.isEmpty()) { notFound(ctx, "No level row for this user"); return; }
        var lu = u.get();
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("discord_id", lu.discordId());
        n.put("xp", lu.xp());
        n.put("level", lu.level());
        n.put("messages", lu.messages());
        n.put("rank", services.levelUserDao.rank(did));
        n.put("last_grant_at", lu.lastGrantAt());
        ctx.json(n);
    }

    public void grantXp(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_LEVELS)) return;
        String did = ctx.pathParam("discordId");
        JsonNode body = readBody(ctx);
        if (body == null) return;
        long delta = body.path("amount").asLong(0);
        if (delta == 0) { badRequest(ctx, "amount must be a non-zero integer"); return; }
        services.levelUserDao.grant(did, delta);
        services.audit.write("api", "level_grant", did,
                java.util.Map.of("delta", delta));
        ctx.json(okJson("granted"));
    }

    public void setLevel(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_LEVELS)) return;
        String did = ctx.pathParam("discordId");
        JsonNode body = readBody(ctx);
        if (body == null) return;
        int level = body.path("level").asInt(-1);
        if (level < 0) { badRequest(ctx, "level must be >= 0"); return; }
        services.levelUserDao.setLevel(did, level);
        services.audit.write("api", "level_set", did,
                java.util.Map.of("level", level));
        ctx.json(okJson("level set"));
    }

    public void resetLevel(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_LEVELS)) return;
        String did = ctx.pathParam("discordId");
        services.levelUserDao.reset(did);
        services.audit.write("api", "level_reset", did, java.util.Map.of());
        ctx.json(okJson("reset"));
    }

    /* ---------------------------------------------------------------------
     * Tickets
     * ------------------------------------------------------------------- */

    public void listTickets(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_TICKETS)) return;
        int limit = qpInt(ctx, "limit", 100, 1, 1000);
        String statusRaw = ctx.queryParam("status");
        io.warden.tickets.TicketStatus statusFilter = null;
        if (statusRaw != null && !statusRaw.isBlank()) {
            try { statusFilter = io.warden.tickets.TicketStatus.fromWire(statusRaw); }
            catch (Exception e) { badRequest(ctx, "unknown status"); return; }
        }
        var rows = services.ticketDao.list(statusFilter, null, limit);
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var t : rows) arr.add(toJson(t));
        ctx.json(root);
    }

    public void getTicket(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_TICKETS)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        var t = services.ticketDao.find(id);
        if (t.isEmpty()) { notFound(ctx, "Unknown ticket"); return; }
        ObjectNode out = toJson(t.get());
        ArrayNode msgs = out.putArray("messages");
        for (var m : services.ticketDao.messages(id)) {
            ObjectNode mo = msgs.addObject();
            mo.put("id", m.id());
            mo.put("author_kind", m.authorKind());
            mo.put("author_id", m.authorId());
            mo.put("author_name", m.authorName());
            mo.put("body", m.body());
            mo.put("internal", m.isInternal());
            mo.put("created_at", m.createdAt());
        }
        ctx.json(out);
    }

    public void replyToTicket(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_TICKETS)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String text = body.path("body").asText("");
        if (text.isBlank()) { badRequest(ctx, "body is required"); return; }
        String staffId = body.path("staff_id").asText("api");
        String staffName = body.path("staff_name").asText("API");
        JDA jda = discord == null ? null : discord.jda();
        boolean ok = services.tickets.replyAsStaff(jda, id, staffId, staffName, text, java.util.List.of());
        if (!ok) { notFound(ctx, "Ticket not found or reply rejected"); return; }
        ctx.json(okJson("replied"));
    }

    public void ticketInternalNote(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_TICKETS)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String text = body.path("body").asText("");
        if (text.isBlank()) { badRequest(ctx, "body is required"); return; }
        String staffId = body.path("staff_id").asText("api");
        String staffName = body.path("staff_name").asText("API");
        boolean ok = services.tickets.postInternalNote(id, staffId, staffName, text);
        if (!ok) { notFound(ctx, "Ticket not found"); return; }
        ctx.json(okJson("note added"));
    }

    public void ticketStatus(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_TICKETS)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String statusRaw = body.path("status").asText("");
        if (statusRaw.isBlank()) { badRequest(ctx, "status is required"); return; }
        io.warden.tickets.TicketStatus next;
        try { next = io.warden.tickets.TicketStatus.fromWire(statusRaw); }
        catch (Exception e) { badRequest(ctx, "unknown status"); return; }
        String staffId = body.path("staff_id").asText("api");
        String staffName = body.path("staff_name").asText("API");
        JDA jda = discord == null ? null : discord.jda();
        boolean ok = services.tickets.changeStatus(jda, id, next, staffId, staffName);
        if (!ok) { notFound(ctx, "Ticket not found"); return; }
        ctx.json(okJson("status set"));
    }

    public void ticketAssign(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_TICKETS)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String staffId = body.path("staff_id").asText("");
        String staffName = body.path("staff_name").asText("");
        if (staffId.isBlank()) { badRequest(ctx, "staff_id is required"); return; }
        boolean ok = services.tickets.assign(id, staffId, staffName);
        if (!ok) { notFound(ctx, "Ticket not found"); return; }
        ctx.json(okJson("assigned"));
    }

    private static ObjectNode toJson(io.warden.tickets.Ticket t) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("id", t.id());
        if (t.categoryId() != null) n.put("category_id", t.categoryId());
        n.put("discord_id", t.discordId());
        n.put("discord_username", t.discordUsername());
        n.put("subject", t.subject());
        n.put("body", t.body());
        n.put("status", t.status() == null ? null : t.status().wire());
        n.put("assignee_id", t.assigneeId());
        n.put("assignee_name", t.assigneeName());
        n.put("mode", t.mode());
        n.put("channel_id", t.channelId());
        n.put("created_at", t.createdAt());
        n.put("last_activity_at", t.lastActivityAt());
        if (t.closedAt() != null) n.put("closed_at", t.closedAt());
        return n;
    }

    /* ---------------------------------------------------------------------
     * Feedback
     * ------------------------------------------------------------------- */

    public void listFeedback(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_FEEDBACK)) return;
        int limit = qpInt(ctx, "limit", 100, 1, 1000);
        var rows = services.feedbackDao.list(null, io.warden.feedback.FeedbackDao.Sort.RECENT, limit);
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var f : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", f.id());
            o.put("discord_id", f.discordId());
            o.put("discord_username", f.discordUsername());
            o.put("title", f.title());
            o.put("body", f.body());
            o.put("status", f.status() == null ? null : f.status().wire());
            o.put("staff_response", f.staffResponse());
            o.put("created_at", f.createdAt());
            o.put("updated_at", f.updatedAt());
            if (f.closedAt() != null) o.put("closed_at", f.closedAt());
            var tally = services.feedbackDao.tally(f.id());
            ObjectNode votes = o.putObject("votes");
            votes.put("up", tally.up());
            votes.put("down", tally.down());
            votes.put("net", tally.net());
        }
        ctx.json(root);
    }

    public void feedbackStatus(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_FEEDBACK)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String wire = body.path("status").asText("");
        if (wire.isBlank()) { badRequest(ctx, "status is required"); return; }
        io.warden.feedback.FeedbackStatus next = io.warden.feedback.FeedbackStatus.fromWire(wire);
        String staffId = body.path("staff_id").asText("api");
        String staffName = body.path("staff_name").asText("API");
        JDA jda = discord == null ? null : discord.jda();
        boolean ok = services.feedback.changeStatus(jda, id, next, staffId, staffName);
        if (!ok) { notFound(ctx, "Feedback not found"); return; }
        ctx.json(okJson("status set"));
    }

    public void feedbackRespond(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_FEEDBACK)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String response = body.path("response").asText("");
        String staffId = body.path("staff_id").asText("api");
        String staffName = body.path("staff_name").asText("API");
        JDA jda = discord == null ? null : discord.jda();
        boolean ok = services.feedback.setStaffResponse(jda, id, response, staffId, staffName);
        if (!ok) { notFound(ctx, "Feedback not found"); return; }
        ctx.json(okJson("responded"));
    }

    public void feedbackDelete(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_FEEDBACK)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JDA jda = discord == null ? null : discord.jda();
        boolean ok = services.feedback.delete(jda, id, "api", "API");
        if (!ok) { notFound(ctx, "Feedback not found"); return; }
        ctx.json(okJson("deleted"));
    }

    /* ---------------------------------------------------------------------
     * Reaction roles
     * ------------------------------------------------------------------- */

    public void listReactionRoles(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_REACTION_ROLES)) return;
        var rows = services.reactionRoleDao.listAll();
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var g : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", g.id());
            o.put("name", g.name());
            o.put("channel_id", g.channelId());
            o.put("message_id", g.messageId());
            o.put("mode", g.mode());
            o.put("style", g.style());
            o.put("title", g.title());
            o.put("description", g.description());
            o.put("color_hex", g.colorHex());
            o.put("max_selections", g.maxSelections());
            o.put("required_role", g.requiredRole());
            o.put("created_at", g.createdAt());
            o.put("updated_at", g.updatedAt());
            ArrayNode opts = o.putArray("options");
            for (var op : g.options()) {
                ObjectNode oo = opts.addObject();
                oo.put("id", op.id());
                oo.put("emoji", op.emoji());
                oo.put("role_id", op.roleId());
                oo.put("label", op.label());
                oo.put("description", op.description());
            }
        }
        ctx.json(root);
    }

    /* ---------------------------------------------------------------------
     * Engagement (polls + giveaways)
     * ------------------------------------------------------------------- */

    public void listPolls(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_ENGAGEMENT)) return;
        var rows = services.pollDao.listOpen();
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var p : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", p.id());
            o.put("channel_id", p.channelId());
            o.put("message_id", p.messageId());
            o.put("creator_id", p.creatorId());
            o.put("question", p.question());
            ArrayNode opts = o.putArray("options");
            for (String s : p.options()) opts.add(s);
            o.put("anonymous", p.anonymous());
            o.put("multi_choice", p.multiChoice());
            if (p.endsAt() != null) o.put("ends_at", p.endsAt());
            if (p.closedAt() != null) o.put("closed_at", p.closedAt());
            o.put("created_at", p.createdAt());
        }
        ctx.json(root);
    }

    public void createPoll(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_ENGAGEMENT)) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String channelId = body.path("channel_id").asText("");
        String question = body.path("question").asText("");
        if (channelId.isBlank() || question.isBlank()) {
            badRequest(ctx, "channel_id and question are required");
            return;
        }
        List<String> options = new ArrayList<>();
        JsonNode opts = body.get("options");
        if (opts != null && opts.isArray()) {
            for (JsonNode o : opts) options.add(o.asText(""));
        }
        if (options.size() < 2) { badRequest(ctx, "at least 2 options required"); return; }
        boolean anonymous = body.path("anonymous").asBoolean(false);
        boolean multi = body.path("multi_choice").asBoolean(false);
        Long endsAt = body.hasNonNull("ends_at") ? body.path("ends_at").asLong() : null;
        String creatorId = body.path("creator_id").asText("api");
        JDA jda = discord == null ? null : discord.jda();
        long id = services.engagement.createPoll(jda, channelId, creatorId, question,
                options, anonymous, multi, endsAt);
        if (id < 0) { badRequest(ctx, "failed to create poll"); return; }
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("ok", true);
        n.put("poll_id", id);
        ctx.json(n);
    }

    public void closePoll(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_ENGAGEMENT)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        services.pollDao.close(id);
        JDA jda = discord == null ? null : discord.jda();
        if (jda != null) services.engagement.postPoll(jda, id);
        services.audit.write("api", "poll_close", null, java.util.Map.of("pollId", id));
        ctx.json(okJson("closed"));
    }

    public void createGiveaway(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_ENGAGEMENT)) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String channelId = body.path("channel_id").asText("");
        String prize = body.path("prize").asText("");
        long endsAt = body.path("ends_at").asLong(0);
        if (channelId.isBlank() || prize.isBlank() || endsAt <= 0) {
            badRequest(ctx, "channel_id, prize, and ends_at are required");
            return;
        }
        String desc = body.path("description").asText("");
        int winners = Math.max(1, body.path("winners").asInt(1));
        String requiredRole = body.path("required_role").asText("");
        String creatorId = body.path("creator_id").asText("api");
        JDA jda = discord == null ? null : discord.jda();
        long id = services.engagement.createGiveaway(jda, channelId, creatorId, prize, desc,
                winners, requiredRole, endsAt);
        if (id < 0) { badRequest(ctx, "failed to create giveaway"); return; }
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("ok", true);
        n.put("giveaway_id", id);
        ctx.json(n);
    }

    public void drawGiveaway(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_ENGAGEMENT)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JDA jda = discord == null ? null : discord.jda();
        if (jda == null) { serviceUnavailable(ctx, "Discord client not ready"); return; }
        List<String> winners = services.engagement.drawWinners(jda, id);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("ok", true);
        ArrayNode arr = n.putArray("winners");
        for (String w : winners) arr.add(w);
        ctx.json(n);
    }

    public void cancelGiveaway(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_ENGAGEMENT)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        services.giveawayDao.cancel(id);
        JDA jda = discord == null ? null : discord.jda();
        if (jda != null) services.engagement.postGiveaway(jda, id);
        services.audit.write("api", "giveaway_cancel", null, java.util.Map.of("giveawayId", id));
        ctx.json(okJson("cancelled"));
    }

    public void listGiveaways(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_ENGAGEMENT)) return;
        var rows = services.giveawayDao.listOpen();
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var g : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", g.id());
            o.put("channel_id", g.channelId());
            o.put("message_id", g.messageId());
            o.put("creator_id", g.creatorId());
            o.put("prize", g.prize());
            o.put("description", g.description());
            o.put("winners", g.winners());
            o.put("required_role", g.requiredRole());
            o.put("ends_at", g.endsAt());
            if (g.drawnAt() != null) o.put("drawn_at", g.drawnAt());
            if (g.cancelledAt() != null) o.put("cancelled_at", g.cancelledAt());
            ArrayNode winners = o.putArray("winner_ids");
            for (String w : g.winnerIds()) winners.add(w);
            o.put("created_at", g.createdAt());
        }
        ctx.json(root);
    }

    /* ---------------------------------------------------------------------
     * Autoresponders
     * ------------------------------------------------------------------- */

    public void listAutoresponders(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_AUTORESPONDERS)) return;
        var rows = services.autoresponderDao.listAll();
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var a : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", a.id());
            o.put("name", a.name());
            o.put("enabled", a.enabled());
            o.put("match_mode", a.matchMode());
            o.put("pattern", a.pattern());
            o.put("case_insensitive", a.caseInsensitive());
            o.put("response_mode", a.responseMode());
            o.put("priority", a.priority());
            o.put("cooldown_seconds", a.cooldownSeconds());
            o.put("created_at", a.createdAt());
            o.put("updated_at", a.updatedAt());
        }
        ctx.json(root);
    }

    public void toggleAutoresponder(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_AUTORESPONDERS)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        boolean enabled = body.path("enabled").asBoolean(true);
        services.autoresponderDao.setEnabled(id, enabled);
        services.audit.write("api", "autoresponder_toggle", null,
                java.util.Map.of("id", id, "enabled", enabled));
        ctx.json(okJson(enabled ? "enabled" : "disabled"));
    }

    public void deleteAutoresponder(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_AUTORESPONDERS)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        services.autoresponderDao.delete(id);
        services.audit.write("api", "autoresponder_delete", null, java.util.Map.of("id", id));
        ctx.json(okJson("deleted"));
    }

    /* ---------------------------------------------------------------------
     * Alerts
     * ------------------------------------------------------------------- */

    public void listAlerts(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_ALERTS)) return;
        var rows = services.alertDao.listAll();
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var a : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", a.id());
            o.put("name", a.name());
            o.put("enabled", a.enabled());
            o.put("event", a.event());
            o.put("channel_id", a.channelId());
            o.put("custom_trigger", a.isCustomTrigger());
            o.put("expressions_enabled", a.expressionsEnabled());
            o.put("async_dispatch", a.asyncDispatch());
            o.put("created_at", a.createdAt());
            o.put("updated_at", a.updatedAt());
        }
        ctx.json(root);
    }

    public void toggleAlert(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_ALERTS)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        JsonNode body = readBody(ctx);
        if (body == null) return;
        boolean enabled = body.path("enabled").asBoolean(true);
        services.alertDao.setEnabled(id, enabled);
        services.audit.write("api", "alert_toggle", null,
                java.util.Map.of("id", id, "enabled", enabled));
        ctx.json(okJson(enabled ? "enabled" : "disabled"));
    }

    public void deleteAlert(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_ALERTS)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        services.alertDao.delete(id);
        services.audit.write("api", "alert_delete", null, java.util.Map.of("id", id));
        ctx.json(okJson("deleted"));
    }

    /* ---------------------------------------------------------------------
     * Scheduler
     * ------------------------------------------------------------------- */

    public void listScheduledEvents(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_SCHEDULER)) return;
        boolean upcoming = "true".equalsIgnoreCase(ctx.queryParam("upcoming"));
        var rows = upcoming
                ? services.scheduledEventDao.listUpcoming(System.currentTimeMillis())
                : services.scheduledEventDao.listAll(200);
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("items");
        for (var e : rows) {
            ObjectNode o = arr.addObject();
            o.put("id", e.id());
            o.put("title", e.title());
            o.put("description", e.description());
            o.put("starts_at_utc", e.startsAtUtc());
            o.put("ends_at_utc", e.endsAtUtc());
            o.put("duration_minutes", e.durationMinutes());
            o.put("creator_id", e.creatorId());
            o.put("creator_name", e.creatorName());
            ArrayNode rls = o.putArray("target_roles");
            for (String r : e.targetRoles()) rls.add(r);
            o.put("discord_channel_id", e.discordAnnounceChannelId());
            o.put("discord_message_id", e.discordAnnounceMessageId());
            if (e.cancelledAt() != null) o.put("cancelled_at", e.cancelledAt());
            o.put("created_at", e.createdAt());
        }
        ctx.json(root);
    }

    public void cancelScheduledEvent(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.WRITE_SCHEDULER)) return;
        long id = parseLong(ctx, "id");
        if (id < 0) return;
        services.scheduledEventDao.cancel(id, System.currentTimeMillis());
        services.audit.write("api", "scheduled_event_cancel", null,
                java.util.Map.of("id", id));
        ctx.json(okJson("cancelled"));
    }

    /* ---------------------------------------------------------------------
     * Timezones
     * ------------------------------------------------------------------- */

    public void getTimezone(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_TIMEZONES)) return;
        String did = ctx.pathParam("discordId");
        var tz = services.userTimezoneDao.find(did);
        if (tz.isEmpty()) { notFound(ctx, "No timezone recorded for this user"); return; }
        var t = tz.get();
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("discord_id", t.discordId());
        n.put("tz_id", t.tzId());
        n.put("source", t.source());
        n.put("updated_at", t.updatedAt());
        ctx.json(n);
    }

    public void setTimezone(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_TIMEZONES)) return;
        String did = ctx.pathParam("discordId");
        JsonNode body = readBody(ctx);
        if (body == null) return;
        String tz = body.path("tz_id").asText("");
        if (tz.isBlank()) { badRequest(ctx, "tz_id is required"); return; }
        if (!services.timezones.isValid(tz)) { badRequest(ctx, "tz_id is not a valid IANA timezone"); return; }
        String source = body.path("source").asText("api");
        services.timezones.saveUser(did, tz, source, "api");
        ctx.json(okJson("timezone set"));
    }

    public void clearTimezone(Context ctx) {
        if (!require(ctx, ApiScope.WRITE_TIMEZONES)) return;
        String did = ctx.pathParam("discordId");
        services.timezones.clearUser(did, "api");
        ctx.json(okJson("cleared"));
    }

    /* ---------------------------------------------------------------------
     * Analytics
     * ------------------------------------------------------------------- */

    public void analyticsOverview(Context ctx) throws Exception {
        if (!require(ctx, ApiScope.READ_ANALYTICS)) return;
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("total_members", services.userDao.countAll());
        n.put("pending_applications", services.applicationDao.countPending());
        n.put("total_level_users", services.levelUserDao.totalUsers());
        n.put("server_now", System.currentTimeMillis());
        ctx.json(n);
    }

    /* ---------------------------------------------------------------------
     * Minecraft player snapshot + Discord-link lookups
     *
     * Player stats are served live for online players, or from the per-name
     * JSON snapshot under plugins/Warden/playerdata/<name>.json for offline
     * players (written on PlayerQuitEvent). Auth is the Warden bearer-token
     * system, gated on the READ_MC_PLAYERS scope.
     * ------------------------------------------------------------------- */

    public void mcPlayer(Context ctx) {
        if (!require(ctx, ApiScope.READ_MC_PLAYERS)) return;
        String username = ctx.pathParam("username");

        Player online = Bukkit.getPlayerExact(username);
        if (online != null) {
            ctx.json(io.warden.api.mc.McPlayerSnapshot.forOnlinePlayer(online));
            return;
        }

        var cache = services.mcPlayerCache();
        if (cache != null) {
            var cached = cache.read(username);
            if (cached.isPresent()) {
                ctx.json(cached.get());
                return;
            }
        }

        ObjectNode obj = Json.MAPPER.createObjectNode();
        obj.put("error", true);
        obj.put("message", "Player not online, or not found");
        ctx.json(obj);
    }

    public void mcDiscordByName(Context ctx) {
        if (!require(ctx, ApiScope.READ_MC_PLAYERS)) return;
        String username = ctx.pathParam("username");

        @SuppressWarnings("deprecation")
        OfflinePlayer player = Bukkit.getOfflinePlayer(username);
        if (player == null || (player.getName() == null && !player.hasPlayedBefore())) {
            playerNotFound(ctx);
            return;
        }
        respondWithDiscordLink(ctx, player, username);
    }

    public void mcDiscordById(Context ctx) {
        if (!require(ctx, ApiScope.READ_MC_PLAYERS)) return;
        String id = ctx.pathParam("id");

        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            ObjectNode obj = Json.MAPPER.createObjectNode();
            obj.put("error", true);
            obj.put("message", "Invalid UUID");
            ctx.json(obj);
            return;
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        if (player == null || (player.getName() == null && !player.hasPlayedBefore())) {
            playerNotFound(ctx);
            return;
        }
        respondWithDiscordLink(ctx, player, player.getName() != null ? player.getName() : id);
    }

    private void respondWithDiscordLink(Context ctx, OfflinePlayer player, String username) {
        DiscordSrvBridge bridge = services.discordSrv();
        if (bridge == null || !bridge.isPresent()) {
            ObjectNode obj = Json.MAPPER.createObjectNode();
            obj.put("error", true);
            obj.put("message", "DiscordSRV is not installed; cannot resolve Discord link");
            ctx.json(obj);
            return;
        }

        var discordIdOpt = bridge.discordIdFor(player.getUniqueId());
        if (discordIdOpt.isEmpty()) {
            ObjectNode obj = Json.MAPPER.createObjectNode();
            obj.put("error", true);
            obj.put("message", "Player not linked to discord");
            ctx.json(obj);
            return;
        }
        String discordId = discordIdOpt.get();

        User user = (discord != null && discord.jda() != null)
                ? discord.jda().getUserById(discordId) : null;
        if (user == null) {
            ObjectNode obj = Json.MAPPER.createObjectNode();
            obj.put("error", true);
            obj.put("message", "Couldn't find Discord User by ID. Maybe they left the server?");
            ctx.json(obj);
            return;
        }

        ObjectNode obj = Json.MAPPER.createObjectNode();
        obj.put("error", false);
        obj.put("username", username);
        obj.put("uuid", player.getUniqueId().toString());
        obj.put("discordId", discordId);
        obj.put("discordName", user.getName());
        String globalName = user.getGlobalName();
        obj.put("discordGlobalName", (globalName == null || globalName.isBlank()) ? user.getName() : globalName);
        ctx.json(obj);
    }

    private static void playerNotFound(Context ctx) {
        ObjectNode obj = Json.MAPPER.createObjectNode();
        obj.put("error", true);
        obj.put("message", "Player not found");
        ctx.json(obj);
    }

    /* ---------------------------------------------------------------------
     * OpenAPI spec + Swagger UI
     * ------------------------------------------------------------------- */

    /** Live OpenAPI 3.1 document. Public (no auth) so docs tooling can pull it. */
    public void openapiJson(Context ctx) {
        ObjectNode spec = new OpenApiBuilder(services.config.webPublicUrl()).build();
        ctx.header("Cache-Control", "no-store");
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.json(spec);
    }

    /**
     * Self-contained Swagger UI page pinned to a CDN build. The UI hits
     * {@link #openapiJson(Context)} for the spec, so it's always in sync
     * with whatever this build supports.
     */
    public void swaggerUi(Context ctx) {
        ctx.contentType("text/html");
        ctx.result("<!doctype html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<title>Warden API · Swagger UI</title>\n"
                + "<link rel=\"icon\" type=\"image/svg+xml\" href=\"/static/img/warden-icon.svg\">\n"
                + "<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui.css\">\n"
                + "<style>\n"
                + "  body{margin:0;background:#0f1226}\n"
                + "  .topbar{display:none}\n"
                + "  #swagger-ui{background:#fff;min-height:100vh}\n"
                + "  .warden-header{display:flex;align-items:center;gap:.6rem;padding:.9rem 1.25rem;\n"
                + "    background:linear-gradient(135deg,#0f1226,#1a1f3d);color:#fff;\n"
                + "    border-bottom:1px solid #2a3158}\n"
                + "  .warden-header img{height:1.6rem}\n"
                + "  .warden-header .title{font-weight:600}\n"
                + "  .warden-header .pill{font-size:.78rem;background:rgba(255,255,255,.08);\n"
                + "    padding:.15rem .55rem;border-radius:99px;color:#cfd6f5;border:1px solid #2a3158}\n"
                + "  .warden-header .right{margin-left:auto;display:flex;gap:.6rem}\n"
                + "  .warden-header a{color:#cfd6f5;text-decoration:none;font-size:.85rem}\n"
                + "  .warden-header a:hover{color:#fff}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"warden-header\">\n"
                + "  <img src=\"/static/img/warden-icon.svg\" alt=\"\">\n"
                + "  <span class=\"title\">Warden API</span>\n"
                + "  <span class=\"pill\">v1</span>\n"
                + "  <div class=\"right\">\n"
                + "    <a href=\"/api/v1/openapi.json\">openapi.json</a>\n"
                + "    <a href=\"/dash/api-keys\">Manage keys</a>\n"
                + "    <a href=\"/dash\">Dashboard</a>\n"
                + "  </div>\n"
                + "</div>\n"
                + "<div id=\"swagger-ui\"></div>\n"
                + "<script src=\"https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui-bundle.js\" crossorigin></script>\n"
                + "<script src=\"https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui-standalone-preset.js\" crossorigin></script>\n"
                + "<script>\n"
                + "window.addEventListener('load', function(){\n"
                + "  window.ui = SwaggerUIBundle({\n"
                + "    url: '/api/v1/openapi.json',\n"
                + "    dom_id: '#swagger-ui',\n"
                + "    deepLinking: true,\n"
                + "    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],\n"
                + "    layout: 'BaseLayout',\n"
                + "    persistAuthorization: true,\n"
                + "    defaultModelsExpandDepth: 0\n"
                + "  });\n"
                + "});\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>");
    }

    /* ---------------------------------------------------------------------
     * Small helpers
     * ------------------------------------------------------------------- */

    private static ObjectNode okJson(String message) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("ok", true);
        n.put("message", message);
        return n;
    }

    private static String optString(Context ctx, String name) {
        String v = ctx.formParam(name);
        if (v != null) return v;
        try {
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            if (body != null) {
                JsonNode field = body.get(name);
                if (field != null && !field.isNull()) return field.asText("");
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static long parseLong(Context ctx, String name) {
        String raw = ctx.pathParam(name);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            badRequest(ctx, "Invalid id: " + raw);
            return -1;
        }
    }

    private static JsonNode readBody(Context ctx) {
        try {
            return Json.MAPPER.readTree(ctx.body());
        } catch (Exception e) {
            badRequest(ctx, "Request body must be JSON");
            return null;
        }
    }

    private Guild requireGuild(Context ctx) {
        JDA jda = discord == null ? null : discord.jda();
        if (jda == null) { serviceUnavailable(ctx, "Discord client not ready"); return null; }
        String guildId = services.config.discordGuildId();
        if (guildId == null || guildId.isBlank()) {
            serviceUnavailable(ctx, "discord.guild_id is not configured");
            return null;
        }
        Guild g = jda.getGuildById(guildId);
        if (g == null) { serviceUnavailable(ctx, "Configured guild not visible to the bot"); return null; }
        return g;
    }

    private static void serviceUnavailable(Context ctx, String msg) {
        ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("error", "service_unavailable");
        n.put("message", msg);
        ctx.json(n);
    }

    private static String safeReason(String r) {
        if (r == null) return "";
        return r.length() > 500 ? r.substring(0, 500) : r;
    }
}
