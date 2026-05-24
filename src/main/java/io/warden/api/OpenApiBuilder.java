package io.warden.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.warden.data.Json;

/**
 * Builds a fresh OpenAPI 3.1 document describing the /api/v1 surface every
 * time the spec endpoint is hit. Kept hand-written rather than annotation
 * driven so adding a route is a one-line append here rather than another
 * dependency on the codebase.
 *
 * The spec is the single source of truth for the Swagger UI rendered at
 * /api/docs. When you add or change an endpoint in {@link ApiV1Handlers},
 * mirror the change here.
 */
public final class OpenApiBuilder {

    private final String publicUrl;

    public OpenApiBuilder(String publicUrl) {
        this.publicUrl = (publicUrl == null || publicUrl.isBlank()) ? "" : publicUrl;
    }

    public ObjectNode build() {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("openapi", "3.1.0");

        ObjectNode info = root.putObject("info");
        info.put("title", "Warden API");
        info.put("version", "1.0.0");
        info.put("description",
                "JSON HTTP API for the Warden Discord onboarding plugin. "
                        + "Each endpoint requires a bearer token minted on the dashboard "
                        + "Page **API keys** with the appropriate scope. Tokens start with "
                        + "`WRDN-` and are passed as `Authorization: Bearer <token>`.");

        if (!publicUrl.isEmpty()) {
            ArrayNode servers = root.putArray("servers");
            servers.addObject().put("url", publicUrl).put("description", "This server");
        }

        // -- Tags grouping endpoints by module --------------------------------
        ArrayNode tags = root.putArray("tags");
        addTag(tags, "Health",         "Plugin health + module status");
        addTag(tags, "Members",        "Discord identities tracked by Warden");
        addTag(tags, "Audit",          "Plugin audit log");
        addTag(tags, "Onboarding",     "Application queue, approve/deny actions");
        addTag(tags, "Moderation",     "Warnings, mod actions");
        addTag(tags, "Levels",         "XP leaderboard");
        addTag(tags, "Tickets",        "Support tickets + transcripts");
        addTag(tags, "Feedback",       "Feedback / suggestions board");
        addTag(tags, "Reaction Roles", "Reaction-role groups + options");
        addTag(tags, "Engagement",     "Polls and giveaways");
        addTag(tags, "Autoresponders", "Trigger-based responses");
        addTag(tags, "Alerts",         "Event-driven alerts");
        addTag(tags, "Scheduler",      "Scheduled events");
        addTag(tags, "Timezones",      "Per-user timezones");
        addTag(tags, "Analytics",      "Aggregate metrics");
        addTag(tags, "Minecraft",      "Player snapshots and Discord-link lookups");

        // -- Components: security scheme + reusable schemas -------------------
        ObjectNode components = root.putObject("components");
        ObjectNode securitySchemes = components.putObject("securitySchemes");
        ObjectNode bearer = securitySchemes.putObject("BearerAuth");
        bearer.put("type", "http");
        bearer.put("scheme", "bearer");
        bearer.put("bearerFormat", "WRDN");
        bearer.put("description", "Warden API key. Mint one on /dash/api-keys.");

        ObjectNode schemas = components.putObject("schemas");
        addSchemas(schemas);

        // -- Paths ------------------------------------------------------------
        ObjectNode paths = root.putObject("paths");

        // Health / index
        op(paths, "/api/v1", "get",
                "Index", "API description, scopes, and module list. No auth required.",
                "Health", null, null, ref("ApiIndex"), false);
        op(paths, "/api/v1/health", "get",
                "Health check", "Returns plugin status (Discord ready, SSL active, server time).",
                "Health", ApiScope.READ_HEALTH, null, ref("HealthResponse"), true);

        // Members
        op(paths, "/api/v1/members", "get",
                "List members", "Paginated list of tracked Discord users.",
                "Members", ApiScope.READ_MEMBERS,
                queryLimit(),
                ref("MemberList"), true);
        op(paths, "/api/v1/members/{discordId}", "get",
                "Get member", "Look up a single member by Discord snowflake id.",
                "Members", ApiScope.READ_MEMBERS,
                pathDiscordId(),
                ref("Member"), true);

        // Audit
        op(paths, "/api/v1/audit", "get",
                "List audit entries",
                "Filter by actor / action substring / target Discord id.",
                "Audit", ApiScope.READ_AUDIT,
                mergeParams(queryLimit(),
                        qParam("actor", "Actor filter (e.g. 'web', 'bot', 'llm', a Discord id)"),
                        qParam("action", "Action substring filter (LIKE %action%)"),
                        qParam("target", "Target Discord id filter")),
                ref("AuditList"), true);

        // Onboarding
        op(paths, "/api/v1/onboarding/pending", "get",
                "Pending applications",
                "All applications awaiting a mod decision.",
                "Onboarding", ApiScope.READ_ONBOARDING, null,
                ref("ApplicationList"), true);
        op(paths, "/api/v1/onboarding/applications", "get",
                "List applications for a user",
                "Returns every application a Discord id has submitted, newest first.",
                "Onboarding", ApiScope.READ_ONBOARDING,
                requiredQuery("discord_id", "Discord snowflake id"),
                ref("ApplicationList"), true);
        op(paths, "/api/v1/onboarding/applications/{id}", "get",
                "Get an application",
                "Returns the full record including answers and LLM verdict.",
                "Onboarding", ApiScope.READ_ONBOARDING,
                pathId(),
                ref("ApplicationDetail"), true);
        op(paths, "/api/v1/onboarding/applications/{id}/approve", "post",
                "Approve an application",
                "Issues approve actions per current flow config. Audited as actor='api'.",
                "Onboarding", ApiScope.WRITE_ONBOARDING,
                pathId(),
                ref("OkResponse"), true,
                ref("NoteBody"));
        op(paths, "/api/v1/onboarding/applications/{id}/deny", "post",
                "Deny an application",
                "Triggers configured deny actions. Optional user_dm overrides the templated DM.",
                "Onboarding", ApiScope.WRITE_ONBOARDING,
                pathId(),
                ref("OkResponse"), true,
                ref("DenyBody"));

        // Moderation
        op(paths, "/api/v1/moderation/warnings", "get",
                "List warnings",
                "Omit discord_id to get the most recent across the guild.",
                "Moderation", ApiScope.READ_MODERATION,
                mergeParams(queryLimit(), qParam("discord_id", "Filter by target user")),
                ref("WarningList"), true);
        op(paths, "/api/v1/moderation/warnings", "post",
                "Issue a warning",
                "Records a warning. Discord-side warn action is not dispatched - this is the audit row only.",
                "Moderation", ApiScope.WRITE_MODERATION, null,
                ref("WarningCreated"), true,
                ref("WarningCreateBody"));
        op(paths, "/api/v1/moderation/warnings/{id}/clear", "post",
                "Clear a warning",
                "Marks the warning row cleared. No-op if already cleared.",
                "Moderation", ApiScope.WRITE_MODERATION,
                pathId(),
                ref("OkResponse"), true);
        op(paths, "/api/v1/moderation/actions", "get",
                "List mod actions",
                "Includes kicks, bans, tempbans, mutes. Omit discord_id for recent global activity.",
                "Moderation", ApiScope.READ_MODERATION,
                mergeParams(queryLimit(), qParam("discord_id", "Filter by target user")),
                ref("ModActionList"), true);
        op(paths, "/api/v1/moderation/actions/kick", "post",
                "Kick a member",
                "Queues a Discord kick of the target user via the configured guild.",
                "Moderation", ApiScope.WRITE_MODERATION, null,
                ref("OkResponse"), true,
                ref("KickBody"));
        op(paths, "/api/v1/moderation/actions/ban", "post",
                "Ban a member",
                "Permanently bans the target. delete_message_days clamped to 0..7.",
                "Moderation", ApiScope.WRITE_MODERATION, null,
                ref("OkResponse"), true,
                ref("BanBody"));
        op(paths, "/api/v1/moderation/actions/tempban", "post",
                "Tempban a member",
                "Bans the target with an auto-revoke scheduled after duration_seconds.",
                "Moderation", ApiScope.WRITE_MODERATION, null,
                ref("OkResponse"), true,
                ref("DurationBody"));
        op(paths, "/api/v1/moderation/actions/timeout", "post",
                "Timeout a member",
                "Applies a Discord timeout for duration_seconds.",
                "Moderation", ApiScope.WRITE_MODERATION, null,
                ref("OkResponse"), true,
                ref("DurationBody"));
        op(paths, "/api/v1/moderation/actions/unban", "post",
                "Unban a member",
                "Removes an existing ban for the target Discord id.",
                "Moderation", ApiScope.WRITE_MODERATION, null,
                ref("OkResponse"), true,
                ref("KickBody"));

        // Levels
        op(paths, "/api/v1/levels/leaderboard", "get",
                "XP leaderboard", "Top XP earners.",
                "Levels", ApiScope.READ_LEVELS, queryLimit(), ref("LeaderboardResponse"), true);
        op(paths, "/api/v1/levels/{discordId}", "get",
                "Get a user's level", "Returns XP, level, message count, rank.",
                "Levels", ApiScope.READ_LEVELS, pathDiscordId(), ref("LevelEntry"), true);
        op(paths, "/api/v1/levels/{discordId}/xp", "post",
                "Grant XP",
                "Adds (or subtracts when negative) XP for the user.",
                "Levels", ApiScope.WRITE_LEVELS, pathDiscordId(),
                ref("OkResponse"), true,
                ref("XpBody"));
        op(paths, "/api/v1/levels/{discordId}/level", "post",
                "Set level",
                "Overwrites the stored level for the user.",
                "Levels", ApiScope.WRITE_LEVELS, pathDiscordId(),
                ref("OkResponse"), true,
                ref("LevelBody"));
        op(paths, "/api/v1/levels/{discordId}/reset", "post",
                "Reset level",
                "Zeroes XP and level for the user.",
                "Levels", ApiScope.WRITE_LEVELS, pathDiscordId(),
                ref("OkResponse"), true);

        // Tickets
        op(paths, "/api/v1/tickets", "get",
                "List tickets",
                "Optionally filter by status: open, in_progress, resolved, closed.",
                "Tickets", ApiScope.READ_TICKETS,
                mergeParams(queryLimit(),
                        qParam("status", "Status filter (open|in_progress|resolved|closed)")),
                ref("TicketList"), true);
        op(paths, "/api/v1/tickets/{id}", "get",
                "Get a ticket",
                "Includes message thread (user + staff + system messages).",
                "Tickets", ApiScope.READ_TICKETS,
                pathId(),
                ref("TicketDetail"), true);
        op(paths, "/api/v1/tickets/{id}/reply", "post",
                "Reply as staff",
                "Appends a staff message and delivers it per ticket mode (DM or channel).",
                "Tickets", ApiScope.WRITE_TICKETS, pathId(),
                ref("OkResponse"), true,
                ref("TicketReplyBody"));
        op(paths, "/api/v1/tickets/{id}/internal-note", "post",
                "Add internal note",
                "Staff-only note attached to the ticket. Never shown to the reporter.",
                "Tickets", ApiScope.WRITE_TICKETS, pathId(),
                ref("OkResponse"), true,
                ref("TicketReplyBody"));
        op(paths, "/api/v1/tickets/{id}/status", "post",
                "Change ticket status",
                "Allowed values: open, in_progress, resolved, closed.",
                "Tickets", ApiScope.WRITE_TICKETS, pathId(),
                ref("OkResponse"), true,
                ref("TicketStatusBody"));
        op(paths, "/api/v1/tickets/{id}/assign", "post",
                "Assign ticket",
                "Sets the staff owner. Pass an empty staff_id to unassign.",
                "Tickets", ApiScope.WRITE_TICKETS, pathId(),
                ref("OkResponse"), true,
                ref("TicketAssignBody"));

        // Feedback
        op(paths, "/api/v1/feedback", "get",
                "List feedback", "Suggestions with vote tallies.",
                "Feedback", ApiScope.READ_FEEDBACK, queryLimit(), ref("FeedbackList"), true);
        op(paths, "/api/v1/feedback/{id}/status", "post",
                "Change feedback status",
                "Allowed values: open, under_review, planned, in_progress, done, declined, duplicate.",
                "Feedback", ApiScope.WRITE_FEEDBACK, pathId(),
                ref("OkResponse"), true,
                ref("FeedbackStatusBody"));
        op(paths, "/api/v1/feedback/{id}/respond", "post",
                "Post staff response",
                "Sets the visible staff response and DMs the reporter when configured.",
                "Feedback", ApiScope.WRITE_FEEDBACK, pathId(),
                ref("OkResponse"), true,
                ref("FeedbackRespondBody"));
        op(paths, "/api/v1/feedback/{id}/delete", "post",
                "Delete feedback",
                "Removes the row and the associated Discord message when present.",
                "Feedback", ApiScope.WRITE_FEEDBACK, pathId(),
                ref("OkResponse"), true);

        // Reaction roles
        op(paths, "/api/v1/reaction-roles", "get",
                "List reaction-role groups",
                "Each group includes its option list.",
                "Reaction Roles", ApiScope.READ_REACTION_ROLES, null, ref("ReactionRoleList"), true);

        // Engagement
        op(paths, "/api/v1/engagement/polls", "get",
                "List open polls",
                "Polls that have not been closed yet.",
                "Engagement", ApiScope.READ_ENGAGEMENT, null, ref("PollList"), true);
        op(paths, "/api/v1/engagement/polls", "post",
                "Create poll",
                "Posts a poll embed in the given channel; supports anonymous and multi-choice.",
                "Engagement", ApiScope.WRITE_ENGAGEMENT, null,
                ref("PollCreated"), true,
                ref("PollCreateBody"));
        op(paths, "/api/v1/engagement/polls/{id}/close", "post",
                "Close poll",
                "Closes voting and re-renders the embed.",
                "Engagement", ApiScope.WRITE_ENGAGEMENT, pathId(),
                ref("OkResponse"), true);
        op(paths, "/api/v1/engagement/giveaways", "get",
                "List open giveaways",
                "Giveaways that have not been drawn or cancelled.",
                "Engagement", ApiScope.READ_ENGAGEMENT, null, ref("GiveawayList"), true);
        op(paths, "/api/v1/engagement/giveaways", "post",
                "Create giveaway",
                "Posts a giveaway embed in the channel with entry/leave buttons.",
                "Engagement", ApiScope.WRITE_ENGAGEMENT, null,
                ref("GiveawayCreated"), true,
                ref("GiveawayCreateBody"));
        op(paths, "/api/v1/engagement/giveaways/{id}/draw", "post",
                "Draw giveaway winners",
                "Picks winners now (irrespective of ends_at) and announces in the channel.",
                "Engagement", ApiScope.WRITE_ENGAGEMENT, pathId(),
                ref("GiveawayDrawResponse"), true);
        op(paths, "/api/v1/engagement/giveaways/{id}/cancel", "post",
                "Cancel giveaway",
                "Marks the giveaway cancelled. No winners are drawn.",
                "Engagement", ApiScope.WRITE_ENGAGEMENT, pathId(),
                ref("OkResponse"), true);

        // Autoresponders
        op(paths, "/api/v1/autoresponders", "get",
                "List autoresponders", "All triggers + response config.",
                "Autoresponders", ApiScope.READ_AUTORESPONDERS, null, ref("AutoresponderList"), true);
        op(paths, "/api/v1/autoresponders/{id}/toggle", "post",
                "Toggle autoresponder",
                "Enable or disable a rule without editing its body.",
                "Autoresponders", ApiScope.WRITE_AUTORESPONDERS, pathId(),
                ref("OkResponse"), true,
                ref("EnabledBody"));
        op(paths, "/api/v1/autoresponders/{id}/delete", "post",
                "Delete autoresponder",
                "Removes the rule permanently.",
                "Autoresponders", ApiScope.WRITE_AUTORESPONDERS, pathId(),
                ref("OkResponse"), true);

        // Alerts
        op(paths, "/api/v1/alerts", "get",
                "List alerts", "All defined alert rules.",
                "Alerts", ApiScope.READ_ALERTS, null, ref("AlertList"), true);
        op(paths, "/api/v1/alerts/{id}/toggle", "post",
                "Toggle alert",
                "Enable or disable an alert rule.",
                "Alerts", ApiScope.WRITE_ALERTS, pathId(),
                ref("OkResponse"), true,
                ref("EnabledBody"));
        op(paths, "/api/v1/alerts/{id}/delete", "post",
                "Delete alert",
                "Removes the alert rule permanently.",
                "Alerts", ApiScope.WRITE_ALERTS, pathId(),
                ref("OkResponse"), true);

        // Scheduler
        op(paths, "/api/v1/scheduler/events", "get",
                "List scheduled events",
                "Pass ?upcoming=true to skip past or cancelled ones.",
                "Scheduler", ApiScope.READ_SCHEDULER,
                arrayOf(qParam("upcoming", "Only return events still in the future")),
                ref("ScheduledEventList"), true);
        op(paths, "/api/v1/scheduler/events/{id}/cancel", "post",
                "Cancel scheduled event",
                "Marks the event cancelled (without deleting it).",
                "Scheduler", ApiScope.WRITE_SCHEDULER, pathId(),
                ref("OkResponse"), true);

        // Timezones
        op(paths, "/api/v1/timezones/{discordId}", "get",
                "Get a user's timezone",
                "Returns IANA tz id, source, last updated.",
                "Timezones", ApiScope.READ_TIMEZONES, pathDiscordId(), ref("UserTimezone"), true);
        op(paths, "/api/v1/timezones/{discordId}", "post",
                "Set a user's timezone",
                "tz_id must be a valid IANA zone (e.g. America/New_York).",
                "Timezones", ApiScope.WRITE_TIMEZONES, pathDiscordId(),
                ref("OkResponse"), true,
                ref("TimezoneSetBody"));
        op(paths, "/api/v1/timezones/{discordId}/clear", "post",
                "Clear a user's timezone",
                "Removes the stored timezone for the user.",
                "Timezones", ApiScope.WRITE_TIMEZONES, pathDiscordId(),
                ref("OkResponse"), true);

        // Analytics
        op(paths, "/api/v1/analytics/overview", "get",
                "Analytics overview",
                "Member, application, level totals.",
                "Analytics", ApiScope.READ_ANALYTICS, null, ref("AnalyticsOverview"), true);

        // Minecraft
        op(paths, "/api/v1/players/{username}", "get",
                "Get player stats",
                "Returns live player state for an online player, or the last cached "
                        + "snapshot recorded on their most recent quit. "
                        + "When the player has never been seen, returns "
                        + "`{ \"error\": true, \"message\": \"Player not online, or not found\" }`.",
                "Minecraft", ApiScope.READ_MC_PLAYERS,
                arrayOf(pParam("username", "Minecraft player name (exact, case-sensitive for online lookup)")),
                ref("McPlayer"), true);
        op(paths, "/api/v1/discord/name/{username}", "get",
                "Discord link by Minecraft name",
                "Looks up the Discord account linked to a Minecraft username via DiscordSRV. "
                        + "Requires DiscordSRV to be installed on the server.",
                "Minecraft", ApiScope.READ_MC_PLAYERS,
                arrayOf(pParam("username", "Minecraft player name")),
                ref("McDiscordLink"), true);
        op(paths, "/api/v1/discord/id/{id}", "get",
                "Discord link by UUID",
                "Looks up the Discord account linked to a Minecraft UUID via DiscordSRV.",
                "Minecraft", ApiScope.READ_MC_PLAYERS,
                arrayOf(pParam("id", "Minecraft player UUID (canonical hyphenated form)")),
                ref("McDiscordLink"), true);

        return root;
    }

    // ---------------------------------------------------------------------
    // Schema definitions
    // ---------------------------------------------------------------------
    private static void addSchemas(ObjectNode schemas) {
        // Generic envelopes
        schemas.set("ErrorResponse", obj(
                "type", "object",
                "properties", obj(
                        "error", strSchema("Machine-readable error kind"),
                        "message", strSchema("Human-readable message"))));

        schemas.set("OkResponse", obj(
                "type", "object",
                "properties", obj(
                        "ok", boolSchema(),
                        "message", strSchema(null))));

        schemas.set("ApiIndex", obj(
                "type", "object",
                "properties", obj(
                        "name", strSchema(null),
                        "version", strSchema(null),
                        "scopes", arrSchema(obj("type", "object",
                                "properties", obj(
                                        "key", strSchema(null),
                                        "label", strSchema(null)))),
                        "modules", arrSchema(obj("type", "object",
                                "properties", obj(
                                        "name", strSchema(null),
                                        "href", strSchema(null)))))));

        schemas.set("HealthResponse", obj(
                "type", "object",
                "properties", obj(
                        "status", strSchema(null),
                        "discord_ready", boolSchema(),
                        "web_ssl_active", boolSchema(),
                        "server_now", intSchema("Unix ms"))));

        // Members
        schemas.set("Member", obj(
                "type", "object",
                "properties", obj(
                        "discord_id", strSchema(null),
                        "username", strSchema(null),
                        "joined_at", intSchema("Unix ms"),
                        "state", strSchema("Onboarding state wire name"),
                        "updated_at", intSchema("Unix ms"))));
        schemas.set("MemberList", listOf("Member", "total"));

        // Audit
        schemas.set("AuditEntry", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "actor", strSchema(null),
                        "action", strSchema(null),
                        "target_discord_id", strSchema(null),
                        "at", intSchema("Unix ms"),
                        "payload", obj("type", "object",
                                "additionalProperties", obj("type", "object")))));
        schemas.set("AuditList", listOf("AuditEntry", null));

        // Onboarding
        schemas.set("Application", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "discord_id", strSchema(null),
                        "submitted_at", intSchema("Unix ms"),
                        "llm_decision", strSchema("approve|deny|escalate"),
                        "llm_confidence", obj("type", "number", "description", "0.0 to 1.0"),
                        "llm_reasoning", strSchema(null),
                        "final_decision", strSchema("approve|deny|null"),
                        "decided_by", strSchema("mod Discord id, 'llm', or 'api'"),
                        "decided_at", intSchema("Unix ms"),
                        "mod_note", strSchema(null),
                        "pending_review", boolSchema())));
        schemas.set("ApplicationAnswer", obj(
                "type", "object",
                "properties", obj(
                        "question_id", intSchema(null),
                        "value_json", strSchema("JSON-encoded answer value"),
                        "submitted_at", intSchema("Unix ms"))));
        schemas.set("ApplicationDetail", obj(
                "allOf", arr(
                        refNode("Application"),
                        obj("type", "object", "properties", obj(
                                "answers", arrSchema(refNode("ApplicationAnswer")))))));
        schemas.set("ApplicationList", listOf("Application", "count"));
        schemas.set("NoteBody", obj("type", "object",
                "properties", obj(
                        "note", strSchema("Mod note recorded with the decision"))));
        schemas.set("DenyBody", obj("type", "object",
                "properties", obj(
                        "note", strSchema("Mod note recorded with the decision"),
                        "user_dm", strSchema("Optional DM message that overrides the deny template"))));

        // Moderation
        schemas.set("Warning", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "discord_id", strSchema(null),
                        "moderator_id", strSchema(null),
                        "reason", strSchema(null),
                        "severity", intSchema("0..10"),
                        "created_at", intSchema("Unix ms"),
                        "cleared_at", intSchema("Unix ms, null if active"))));
        schemas.set("WarningList", listOf("Warning", null));
        schemas.set("WarningCreateBody", obj("type", "object",
                "required", arr("discord_id"),
                "properties", obj(
                        "discord_id", strSchema(null),
                        "reason", strSchema(null),
                        "severity", intSchema("Defaults to 1"))));
        schemas.set("WarningCreated", obj("type", "object",
                "properties", obj(
                        "ok", boolSchema(),
                        "warning_id", intSchema(null))));
        schemas.set("KickBody", obj("type", "object",
                "required", arr("discord_id"),
                "properties", obj(
                        "discord_id", strSchema(null),
                        "reason", strSchema(null))));
        schemas.set("BanBody", obj("type", "object",
                "required", arr("discord_id"),
                "properties", obj(
                        "discord_id", strSchema(null),
                        "reason", strSchema(null),
                        "delete_message_days", intSchema("0..7, defaults to 0"))));
        schemas.set("DurationBody", obj("type", "object",
                "required", arr("discord_id", "duration_seconds"),
                "properties", obj(
                        "discord_id", strSchema(null),
                        "reason", strSchema(null),
                        "duration_seconds", intSchema("Must be > 0"))));
        schemas.set("ModAction", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "action", strSchema("kick|ban|tempban|mute|unmute"),
                        "target_discord_id", strSchema(null),
                        "moderator_id", strSchema(null),
                        "reason", strSchema(null),
                        "duration_seconds", intSchema(null),
                        "expires_at", intSchema("Unix ms"),
                        "revoked_at", intSchema("Unix ms"),
                        "created_at", intSchema("Unix ms"))));
        schemas.set("ModActionList", listOf("ModAction", null));

        // Levels
        schemas.set("LevelEntry", obj(
                "type", "object",
                "properties", obj(
                        "rank", intSchema(null),
                        "discord_id", strSchema(null),
                        "xp", intSchema(null),
                        "level", intSchema(null),
                        "messages", intSchema(null),
                        "last_grant_at", intSchema("Unix ms"))));
        schemas.set("LeaderboardResponse", obj(
                "type", "object",
                "properties", obj(
                        "total_users", intSchema(null),
                        "items", arrSchema(refNode("LevelEntry")))));
        schemas.set("XpBody", obj("type", "object",
                "required", arr("amount"),
                "properties", obj(
                        "amount", intSchema("Positive to grant, negative to deduct"))));
        schemas.set("LevelBody", obj("type", "object",
                "required", arr("level"),
                "properties", obj(
                        "level", intSchema("Must be >= 0"))));

        // Tickets
        schemas.set("Ticket", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "category_id", intSchema(null),
                        "discord_id", strSchema(null),
                        "discord_username", strSchema(null),
                        "subject", strSchema(null),
                        "body", strSchema(null),
                        "status", strSchema("open|in_progress|resolved|closed"),
                        "assignee_id", strSchema(null),
                        "assignee_name", strSchema(null),
                        "mode", strSchema("dm|channel"),
                        "channel_id", strSchema(null),
                        "created_at", intSchema("Unix ms"),
                        "last_activity_at", intSchema("Unix ms"),
                        "closed_at", intSchema("Unix ms"))));
        schemas.set("TicketMessage", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "author_kind", strSchema("user|staff|system|internal"),
                        "author_id", strSchema(null),
                        "author_name", strSchema(null),
                        "body", strSchema(null),
                        "internal", boolSchema(),
                        "created_at", intSchema("Unix ms"))));
        schemas.set("TicketDetail", obj(
                "allOf", arr(
                        refNode("Ticket"),
                        obj("type", "object", "properties", obj(
                                "messages", arrSchema(refNode("TicketMessage")))))));
        schemas.set("TicketList", listOf("Ticket", null));
        schemas.set("TicketReplyBody", obj("type", "object",
                "required", arr("body"),
                "properties", obj(
                        "body", strSchema(null),
                        "staff_id", strSchema("Defaults to 'api'"),
                        "staff_name", strSchema("Defaults to 'API'"))));
        schemas.set("TicketStatusBody", obj("type", "object",
                "required", arr("status"),
                "properties", obj(
                        "status", strSchema("open|in_progress|resolved|closed"),
                        "staff_id", strSchema(null),
                        "staff_name", strSchema(null))));
        schemas.set("TicketAssignBody", obj("type", "object",
                "required", arr("staff_id"),
                "properties", obj(
                        "staff_id", strSchema(null),
                        "staff_name", strSchema(null))));

        // Feedback
        schemas.set("Feedback", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "discord_id", strSchema(null),
                        "discord_username", strSchema(null),
                        "title", strSchema(null),
                        "body", strSchema(null),
                        "status", strSchema("open|planned|in_progress|done|wontfix"),
                        "staff_response", strSchema(null),
                        "created_at", intSchema("Unix ms"),
                        "updated_at", intSchema("Unix ms"),
                        "closed_at", intSchema("Unix ms"),
                        "votes", obj("type", "object",
                                "properties", obj(
                                        "up", intSchema(null),
                                        "down", intSchema(null),
                                        "net", intSchema(null))))));
        schemas.set("FeedbackList", listOf("Feedback", null));
        schemas.set("FeedbackStatusBody", obj("type", "object",
                "required", arr("status"),
                "properties", obj(
                        "status", strSchema("open|under_review|planned|in_progress|done|declined|duplicate"),
                        "staff_id", strSchema(null),
                        "staff_name", strSchema(null))));
        schemas.set("FeedbackRespondBody", obj("type", "object",
                "properties", obj(
                        "response", strSchema("Public staff response text"),
                        "staff_id", strSchema(null),
                        "staff_name", strSchema(null))));

        // Reaction roles
        schemas.set("ReactionRoleOption", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "emoji", strSchema(null),
                        "role_id", strSchema(null),
                        "label", strSchema(null),
                        "description", strSchema(null))));
        schemas.set("ReactionRoleGroup", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "name", strSchema(null),
                        "channel_id", strSchema(null),
                        "message_id", strSchema(null),
                        "mode", strSchema(null),
                        "style", strSchema(null),
                        "title", strSchema(null),
                        "description", strSchema(null),
                        "color_hex", strSchema(null),
                        "max_selections", intSchema(null),
                        "required_role", strSchema(null),
                        "created_at", intSchema("Unix ms"),
                        "updated_at", intSchema("Unix ms"),
                        "options", arrSchema(refNode("ReactionRoleOption")))));
        schemas.set("ReactionRoleList", listOf("ReactionRoleGroup", null));

        // Engagement
        schemas.set("Poll", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "channel_id", strSchema(null),
                        "message_id", strSchema(null),
                        "creator_id", strSchema(null),
                        "question", strSchema(null),
                        "options", arrSchema(strSchema(null)),
                        "anonymous", boolSchema(),
                        "multi_choice", boolSchema(),
                        "ends_at", intSchema("Unix ms"),
                        "closed_at", intSchema("Unix ms"),
                        "created_at", intSchema("Unix ms"))));
        schemas.set("PollList", listOf("Poll", null));
        schemas.set("Giveaway", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "channel_id", strSchema(null),
                        "message_id", strSchema(null),
                        "creator_id", strSchema(null),
                        "prize", strSchema(null),
                        "description", strSchema(null),
                        "winners", intSchema(null),
                        "required_role", strSchema(null),
                        "ends_at", intSchema("Unix ms"),
                        "drawn_at", intSchema("Unix ms"),
                        "cancelled_at", intSchema("Unix ms"),
                        "winner_ids", arrSchema(strSchema(null)),
                        "created_at", intSchema("Unix ms"))));
        schemas.set("GiveawayList", listOf("Giveaway", null));
        schemas.set("PollCreateBody", obj("type", "object",
                "required", arr("channel_id", "question", "options"),
                "properties", obj(
                        "channel_id", strSchema("Target Discord channel id"),
                        "question", strSchema(null),
                        "options", arrSchema(strSchema(null)),
                        "anonymous", boolSchema(),
                        "multi_choice", boolSchema(),
                        "ends_at", intSchema("Unix ms, optional"),
                        "creator_id", strSchema("Defaults to 'api'"))));
        schemas.set("PollCreated", obj("type", "object",
                "properties", obj(
                        "ok", boolSchema(),
                        "poll_id", intSchema(null))));
        schemas.set("GiveawayCreateBody", obj("type", "object",
                "required", arr("channel_id", "prize", "ends_at"),
                "properties", obj(
                        "channel_id", strSchema(null),
                        "prize", strSchema(null),
                        "description", strSchema(null),
                        "winners", intSchema("Defaults to 1"),
                        "required_role", strSchema("Role id, optional"),
                        "ends_at", intSchema("Unix ms"),
                        "creator_id", strSchema("Defaults to 'api'"))));
        schemas.set("GiveawayCreated", obj("type", "object",
                "properties", obj(
                        "ok", boolSchema(),
                        "giveaway_id", intSchema(null))));
        schemas.set("GiveawayDrawResponse", obj("type", "object",
                "properties", obj(
                        "ok", boolSchema(),
                        "winners", arrSchema(strSchema(null)))));

        // Autoresponders
        schemas.set("Autoresponder", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "name", strSchema(null),
                        "enabled", boolSchema(),
                        "match_mode", strSchema("contains|exact|prefix|suffix|regex"),
                        "pattern", strSchema(null),
                        "case_insensitive", boolSchema(),
                        "response_mode", strSchema("content|embed"),
                        "priority", intSchema(null),
                        "cooldown_seconds", intSchema(null),
                        "created_at", intSchema("Unix ms"),
                        "updated_at", intSchema("Unix ms"))));
        schemas.set("AutoresponderList", listOf("Autoresponder", null));
        schemas.set("EnabledBody", obj("type", "object",
                "properties", obj(
                        "enabled", boolSchema())));

        // Alerts
        schemas.set("Alert", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "name", strSchema(null),
                        "enabled", boolSchema(),
                        "event", strSchema(null),
                        "channel_id", strSchema(null),
                        "custom_trigger", boolSchema(),
                        "expressions_enabled", boolSchema(),
                        "async_dispatch", boolSchema(),
                        "created_at", intSchema("Unix ms"),
                        "updated_at", intSchema("Unix ms"))));
        schemas.set("AlertList", listOf("Alert", null));

        // Scheduler
        schemas.set("ScheduledEvent", obj(
                "type", "object",
                "properties", obj(
                        "id", intSchema(null),
                        "title", strSchema(null),
                        "description", strSchema(null),
                        "starts_at_utc", intSchema("Unix ms"),
                        "ends_at_utc", intSchema("Unix ms"),
                        "duration_minutes", intSchema(null),
                        "creator_id", strSchema(null),
                        "creator_name", strSchema(null),
                        "target_roles", arrSchema(strSchema(null)),
                        "discord_channel_id", strSchema(null),
                        "discord_message_id", strSchema(null),
                        "cancelled_at", intSchema("Unix ms"),
                        "created_at", intSchema("Unix ms"))));
        schemas.set("ScheduledEventList", listOf("ScheduledEvent", null));

        // Timezones
        schemas.set("UserTimezone", obj(
                "type", "object",
                "properties", obj(
                        "discord_id", strSchema(null),
                        "tz_id", strSchema("IANA timezone id"),
                        "source", strSchema("manual|geoip|admin"),
                        "updated_at", intSchema("Unix ms"))));
        schemas.set("TimezoneSetBody", obj("type", "object",
                "required", arr("tz_id"),
                "properties", obj(
                        "tz_id", strSchema("Valid IANA timezone id"),
                        "source", strSchema("Defaults to 'api'"))));

        // Analytics
        schemas.set("AnalyticsOverview", obj(
                "type", "object",
                "properties", obj(
                        "total_members", intSchema(null),
                        "pending_applications", intSchema(null),
                        "total_level_users", intSchema(null),
                        "server_now", intSchema("Unix ms"))));

        // Minecraft player schema. Numeric values are returned as strings for
        // backward compatibility with existing consumers; the 'online' flag and
        // 'lastJoined' epoch are the two booleans/longs in the payload.
        schemas.set("McPlayer", obj(
                "type", "object",
                "properties", obj(
                        "username", strSchema(null),
                        "uuid", strSchema(null),
                        "health", strSchema(null),
                        "food", strSchema(null),
                        "world", strSchema(null),
                        "experience", strSchema("0.0 - 1.0 progress to next level"),
                        "level", strSchema(null),
                        "deaths", strSchema(null),
                        "kills", strSchema("MOB_KILLS statistic"),
                        "jumps", strSchema(null),
                        "gamemode", strSchema("SURVIVAL|CREATIVE|ADVENTURE|SPECTATOR"),
                        "bed", strSchema("x=..,y=..,z=.. for the respawn point, omitted when unset"),
                        "time", strSchema("PLAY_ONE_MINUTE in seconds"),
                        "death", strSchema("TIME_SINCE_DEATH in seconds"),
                        "address", strSchema("IP address (only visible online; cached snapshot keeps last IP)"),
                        "lastJoined", intSchema("Unix ms"),
                        "online", boolSchema(),
                        "location", strSchema("x=..,y=..,z=.."),
                        "error", boolSchema(),
                        "message", strSchema("Set when error=true (e.g. player not found)"))));
        schemas.set("McDiscordLink", obj(
                "type", "object",
                "properties", obj(
                        "error", boolSchema(),
                        "message", strSchema("Set when error=true"),
                        "username", strSchema("Minecraft username"),
                        "uuid", strSchema("Minecraft UUID"),
                        "discordId", strSchema("Discord snowflake"),
                        "discordName", strSchema("Unique Discord username (post-pomelo)"),
                        "discordGlobalName", strSchema("Discord display/global name; falls back to discordName if unset"))));
    }

    // ---------------------------------------------------------------------
    // Operation builder
    // ---------------------------------------------------------------------
    private static void op(ObjectNode paths, String path, String method,
                           String summary, String description, String tag,
                           ApiScope scope, ArrayNode params,
                           ObjectNode okResponseSchema, boolean securedHere) {
        op(paths, path, method, summary, description, tag, scope, params,
                okResponseSchema, securedHere, null);
    }

    private static void op(ObjectNode paths, String path, String method,
                           String summary, String description, String tag,
                           ApiScope scope, ArrayNode params,
                           ObjectNode okResponseSchema, boolean secured,
                           ObjectNode requestBodySchema) {
        ObjectNode pathItem = (ObjectNode) paths.get(path);
        if (pathItem == null) {
            pathItem = paths.putObject(path);
        }
        ObjectNode opNode = pathItem.putObject(method);
        opNode.put("summary", summary);
        if (description != null) opNode.put("description", description);
        ArrayNode tagsArr = opNode.putArray("tags");
        tagsArr.add(tag);

        if (secured) {
            ArrayNode sec = opNode.putArray("security");
            ObjectNode entry = sec.addObject();
            entry.putArray("BearerAuth");
        }

        if (scope != null && description != null) {
            opNode.put("description", description + "\n\n**Required scope:** `" + scope.key() + "`");
        }

        if (params != null && params.size() > 0) {
            opNode.set("parameters", params);
        }

        if (requestBodySchema != null) {
            ObjectNode rb = opNode.putObject("requestBody");
            rb.put("required", false);
            ObjectNode content = rb.putObject("content");
            content.putObject("application/json").set("schema", requestBodySchema);
        }

        ObjectNode responses = opNode.putObject("responses");
        ObjectNode ok = responses.putObject("200");
        ok.put("description", "Success");
        ObjectNode okContent = ok.putObject("content");
        okContent.putObject("application/json").set("schema", okResponseSchema);

        if (secured) {
            ObjectNode unauth = responses.putObject("401");
            unauth.put("description", "Missing or invalid bearer token");
            unauth.putObject("content").putObject("application/json").set("schema", ref("ErrorResponse"));
            ObjectNode forb = responses.putObject("403");
            forb.put("description", "Token missing the required scope");
            forb.putObject("content").putObject("application/json").set("schema", ref("ErrorResponse"));
        }
        ObjectNode notFound = responses.putObject("404");
        notFound.put("description", "Resource not found");
        notFound.putObject("content").putObject("application/json").set("schema", ref("ErrorResponse"));
    }

    // ---------------------------------------------------------------------
    // Param + schema helpers
    // ---------------------------------------------------------------------
    private static ArrayNode queryLimit() {
        return arrayOf(qParam("limit", "Maximum rows to return (default 100, max 1000)"));
    }

    private static ArrayNode pathDiscordId() {
        return arrayOf(pParam("discordId", "Discord snowflake id"));
    }

    private static ArrayNode pathId() {
        return arrayOf(pParam("id", "Numeric row id"));
    }

    private static ArrayNode requiredQuery(String name, String desc) {
        ObjectNode q = qParam(name, desc);
        q.put("required", true);
        return arrayOf(q);
    }

    private static ObjectNode qParam(String name, String desc) {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("name", name);
        p.put("in", "query");
        p.put("description", desc);
        ObjectNode schema = p.putObject("schema");
        schema.put("type", "string");
        return p;
    }

    private static ObjectNode pParam(String name, String desc) {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("name", name);
        p.put("in", "path");
        p.put("required", true);
        p.put("description", desc);
        ObjectNode schema = p.putObject("schema");
        schema.put("type", "string");
        return p;
    }

    private static ArrayNode arrayOf(ObjectNode... params) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        for (ObjectNode p : params) arr.add(p);
        return arr;
    }

    private static ArrayNode mergeParams(ArrayNode base, ObjectNode... extras) {
        ArrayNode out = Json.MAPPER.createArrayNode();
        out.addAll(base);
        for (ObjectNode p : extras) out.add(p);
        return out;
    }

    private static ObjectNode strSchema(String desc) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "string");
        if (desc != null) n.put("description", desc);
        return n;
    }

    private static ObjectNode intSchema(String desc) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "integer");
        n.put("format", "int64");
        if (desc != null) n.put("description", desc);
        return n;
    }

    private static ObjectNode boolSchema() {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "boolean");
        return n;
    }

    private static ObjectNode arrSchema(ObjectNode itemSchema) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "array");
        n.set("items", itemSchema);
        return n;
    }

    private static ObjectNode obj(Object... kv) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object val = kv[i + 1];
            if (val instanceof ObjectNode on) n.set(key, on);
            else if (val instanceof ArrayNode an) n.set(key, an);
            else if (val instanceof String s) n.put(key, s);
            else if (val instanceof Integer ii) n.put(key, ii);
            else if (val instanceof Boolean bb) n.put(key, bb);
            else n.put(key, String.valueOf(val));
        }
        return n;
    }

    private static ArrayNode arr(Object... vals) {
        ArrayNode a = Json.MAPPER.createArrayNode();
        for (Object v : vals) {
            if (v instanceof ObjectNode on) a.add(on);
            else if (v instanceof ArrayNode an) a.add(an);
            else if (v instanceof String s) a.add(s);
        }
        return a;
    }

    private static ObjectNode ref(String name) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("$ref", "#/components/schemas/" + name);
        return n;
    }

    private static ObjectNode refNode(String name) {
        return ref(name);
    }

    /** Standard envelope: { (count_key): n, items: [Ref] }. count_key may be null. */
    private static ObjectNode listOf(String itemRef, String countKey) {
        ObjectNode props = Json.MAPPER.createObjectNode();
        if (countKey != null) props.set(countKey, intSchema(null));
        props.set("items", arrSchema(refNode(itemRef)));
        return obj("type", "object", "properties", props);
    }

    private static void addTag(ArrayNode tags, String name, String desc) {
        ObjectNode t = tags.addObject();
        t.put("name", name);
        t.put("description", desc);
    }
}
