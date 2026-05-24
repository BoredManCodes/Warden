package io.warden.web.handlers;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.warden.Services;
import io.warden.data.Json;
import io.warden.data.dao.ApplicationDao;
import io.warden.data.dao.DailyMetricsDao;
import io.warden.data.dao.McSessionDao;
import io.warden.discord.DiscordService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stats / analytics dashboard. The page itself is static chrome; ApexCharts on
 * the client fetches /dash/api/overview and renders the headline cards + 90d
 * activity timeline.
 *
 * Reads come from daily_metrics (rolled up nightly by {@link io.warden.analytics.RollupJob}),
 * so even with hundreds of thousands of raw events this stays O(days_shown).
 */
public final class DashStatsHandlers {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final int TIMELINE_DAYS = 90;
    private static final int WINDOW_DAYS = 30;
    private static final int ACTIVITY_DAYS = 60;
    private static final int TOP_CHANNELS_DAYS = 7;
    /** Cap channel-stacked area chart at N concrete channels + an "Other" bucket. */
    private static final int TOP_CHANNELS_IN_CHART = 8;
    private static final int GEO_DAYS = 90;

    private final Services services;
    private final DiscordService discord; // may be null when JDA isn't configured

    public DashStatsHandlers(Services services, DiscordService discord) {
        this.services = services;
        this.discord = discord;
    }

    public void page(Context ctx) {
        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head("Stats · Warden", "stats", ctx));
        h.append(pageHeader("overview"));

        // Six headline stat cards, populated by JS from /dash/api/overview.
        h.append("<div class=\"row g-3 mb-4\">");
        h.append(card("total_members", "bi-people-fill",   "Total members",   "primary",
                "Every Discord user Warden has seen at least once. Includes members who joined, "
                + "got onboarding, were approved, or were denied. Does not auto-prune when someone leaves the guild."));
        h.append(card("discord_mau",   "bi-discord",       "Discord MAU",     "info",
                "Distinct Discord users who sent at least one message in the configured guild in the "
                + "last 30 days. Bots and system messages are excluded. Voice activity is not counted here."));
        h.append(card("mc_mau",        "bi-controller",    "Minecraft MAU",   "success",
                "Distinct Minecraft accounts (by UUID) that opened at least one session in the last 30 days. "
                + "Counted at login, not based on time spent."));
        h.append(card("pending",       "bi-inbox-fill",    "Pending review",  "warning",
                "Submitted applications that haven't been approved or denied yet. Includes applications "
                + "the LLM escalated and any still waiting on a mod button click."));
        h.append(card("approval_rate", "bi-check2-circle", "Approval rate "
                + " <span class=\"text-secondary small\">(30d)</span>", "secondary",
                "Of the applications decided in the last 30 days, the fraction that were approved "
                + "(by any actor: LLM auto-approve, bot policy, or a mod). 'n/a' means nothing was decided yet."));
        h.append(card("avg_decision",  "bi-stopwatch",     "Avg time to decision "
                + "<span class=\"text-secondary small\">(30d)</span>", "secondary",
                "Average wall-clock time between application_submitted and the final decision, over the "
                + "last 30 days. Fast auto-approves drag this down; long mod backlogs push it up."));
        h.append("</div>");

        // Funnel chart - 30d onboarding stages.
        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<div class=\"d-flex justify-content-between align-items-baseline mb-2\">");
        h.append("<h2 class=\"h5 mb-0\">Onboarding funnel <span class=\"text-secondary small\">(last 30 days)</span>")
                .append(Layout.infoIcon("Each step is a count of distinct Discord users who hit that audit event "
                        + "in the last 30 days: Joined (discord_member_events.join), Onboarding sent (DM or "
                        + "channel delivered), Started onboarding (button/code/OAuth landed), Accepted rules, "
                        + "Submitted answers, Approved. Steps aren't forced monotonic - events from before the "
                        + "window can leak in."))
                .append("</h2>");
        h.append("</div>");
        h.append("<div id=\"warden-funnel\" style=\"min-height:340px\"></div>");
        h.append("</div></div>");

        // Joins by invite (30d).
        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<div class=\"d-flex justify-content-between align-items-baseline mb-2\">");
        h.append("<h2 class=\"h5 mb-0\">Joins by invite <span class=\"text-secondary small\">(last 30 days)</span>")
                .append(Layout.infoIcon("Members are attributed to the Discord invite whose uses-counter bumped at the "
                        + "moment they joined. Invites with a label (Reddit, TikTok, ...) are grouped under that label; "
                        + "unlabelled invites show up as #code. 'Untracked' covers vanity URLs, server discovery, and "
                        + "direct bot-add - those have no invite code to attribute to. Set labels on /dash/invites."))
                .append("</h2>");
        h.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/invites\">manage labels</a>");
        h.append("</div>");
        h.append("<div id=\"warden-joins-by-invite\" style=\"min-height:300px\"></div>");
        h.append("</div></div>");

        // 90d activity chart.
        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<div class=\"d-flex justify-content-between align-items-baseline mb-2\">");
        h.append("<h2 class=\"h5 mb-0\">Activity (last ").append(TIMELINE_DAYS).append(" days)")
                .append(Layout.infoIcon("Three daily series: Discord joins (new guild members), Discord leaves "
                        + "(includes plain leaves, kicks and bans), and Minecraft DAU (distinct UUIDs with at "
                        + "least one session that day). Values come from the nightly rollup; new traffic shows "
                        + "up after the next pass."))
                .append("</h2>");
        h.append("<span class=\"text-secondary small\" id=\"warden-stats-asof\">loading...</span>");
        h.append("</div>");
        h.append("<div id=\"warden-stats-timeline\" style=\"min-height:340px\"></div>");
        h.append("</div></div>");

        h.append(APEX_CDN);
        h.append("<script>").append(CHART_BOOT_JS).append("</script>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void activityPage(Context ctx) {
        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head("Activity · Stats · Warden", "stats", ctx));
        h.append(pageHeader("activity"));

        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<div class=\"d-flex justify-content-between align-items-baseline mb-2\">");
        h.append("<h2 class=\"h5 mb-0\">Discord messages per day <span class=\"text-secondary small\">(top ")
                .append(TOP_CHANNELS_IN_CHART).append(" channels, ").append(ACTIVITY_DAYS).append(" days)</span>")
                .append(Layout.infoIcon("Stacked area chart of daily message counts, broken down by channel. "
                        + "Only the top " + TOP_CHANNELS_IN_CHART + " channels by total volume in the window get "
                        + "their own slice; everything else is grouped under 'Other'. Channel names come from "
                        + "JDA's cache when available, otherwise the raw channel id is shown."))
                .append("</h2>");
        h.append("<span class=\"text-secondary small\" id=\"warden-activity-asof\">loading...</span>");
        h.append("</div>");
        h.append("<div id=\"warden-activity-channels\" style=\"min-height:380px\"></div>");
        h.append("</div></div>");

        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<h2 class=\"h5 mb-2\">Top channels <span class=\"text-secondary small\">(last ")
                .append(TOP_CHANNELS_DAYS).append(" days)</span>")
                .append(Layout.infoIcon("Channels ranked by total message count in the last " + TOP_CHANNELS_DAYS
                        + " days. The progress bar is relative to the top channel, not to the whole guild. Bots "
                        + "and system messages don't count toward these numbers."))
                .append("</h2>");
        h.append("<div class=\"table-responsive\"><table class=\"table table-sm align-middle mb-0\">");
        h.append("<thead><tr><th>Channel</th><th class=\"text-end\">Messages</th><th></th></tr></thead>");
        h.append("<tbody id=\"warden-top-channels\"><tr><td colspan=3 class=\"text-secondary\">loading...</td></tr></tbody>");
        h.append("</table></div></div></div>");

        h.append(APEX_CDN);
        h.append("<script>").append(ACTIVITY_BOOT_JS).append("</script>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void overviewApi(Context ctx) throws Exception {
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate windowFrom = today.minusDays(WINDOW_DAYS - 1L);
        LocalDate timelineFrom = today.minusDays(TIMELINE_DAYS - 1L);
        String windowFromKey = DAY_FMT.format(windowFrom);
        String todayKey = DAY_FMT.format(today);

        int totalMembers = services.userDao.countAll();
        int pending = services.applicationDao.countPending();

        long discordMau = distinctOver("discord_messages", "discord_id", "at",
                windowFrom.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        long mcMau = distinctOver("mc_sessions", "mc_uuid", "started_at",
                windowFrom.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());

        long windowStartMs = windowFrom.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        ApplicationDao.DecisionStats decisions = services.applicationDao.decisionStatsBetween(windowStartMs, now);

        long lastRollup = services.analyticsMetaDao
                .get(io.warden.data.dao.AnalyticsMetaDao.KEY_LAST_ROLLUP_AT)
                .map(Long::parseLong).orElse(0L);

        ObjectNode root = Json.MAPPER.createObjectNode();

        ObjectNode cards = root.putObject("cards");
        cards.put("total_members", totalMembers);
        cards.put("discord_mau", discordMau);
        cards.put("mc_mau", mcMau);
        cards.put("pending", pending);
        int totalDecided = decisions.approved() + decisions.denied();
        cards.put("decisions_count", totalDecided);
        if (totalDecided > 0) {
            cards.put("approval_rate_pct", Math.round(100.0 * decisions.approved() / totalDecided));
        } else {
            cards.putNull("approval_rate_pct");
        }
        if (decisions.avgDecisionMs() != null) {
            cards.put("avg_decision_ms", decisions.avgDecisionMs());
            cards.put("avg_decision_human", humanizeMs(decisions.avgDecisionMs()));
        } else {
            cards.putNull("avg_decision_ms");
            cards.put("avg_decision_human", "n/a");
        }

        ObjectNode timeline = root.putObject("timeline");
        ArrayNode labels = timeline.putArray("labels");
        Map<String, long[]> seriesByKey = new TreeMap<>();
        String[] series = new String[] {
                DailyMetricsDao.M_DISCORD_JOINS,
                DailyMetricsDao.M_DISCORD_LEAVES,
                DailyMetricsDao.M_MC_DAU
        };
        for (String s : series) seriesByKey.put(s, new long[TIMELINE_DAYS]);

        Map<String, Integer> dayIndex = new HashMap<>(TIMELINE_DAYS);
        for (int i = 0; i < TIMELINE_DAYS; i++) {
            String dayKey = DAY_FMT.format(timelineFrom.plusDays(i));
            labels.add(dayKey);
            dayIndex.put(dayKey, i);
        }
        for (String s : series) {
            List<DailyMetricsDao.Point> rows = services.dailyMetricsDao
                    .rangeUnscoped(s, DAY_FMT.format(timelineFrom), todayKey);
            long[] vals = seriesByKey.get(s);
            for (var p : rows) {
                Integer idx = dayIndex.get(p.day());
                if (idx != null) vals[idx] = p.value();
            }
        }
        ArrayNode seriesArr = timeline.putArray("series");
        addSeries(seriesArr, "Discord joins", seriesByKey.get(DailyMetricsDao.M_DISCORD_JOINS));
        addSeries(seriesArr, "Discord leaves", seriesByKey.get(DailyMetricsDao.M_DISCORD_LEAVES));
        addSeries(seriesArr, "Minecraft DAU", seriesByKey.get(DailyMetricsDao.M_MC_DAU));

        ObjectNode metaNode = root.putObject("meta");
        metaNode.put("window_days", WINDOW_DAYS);
        metaNode.put("timeline_days", TIMELINE_DAYS);
        metaNode.put("last_rollup_at", lastRollup);
        metaNode.put("window_from", windowFromKey);
        metaNode.put("server_now", now);

        ctx.header("Cache-Control", "no-store");
        ctx.json(root);
    }

    /** Admin-triggered immediate rollup. Useful for "I just imported events; refresh the chart now." */
    public void rollupNow(Context ctx) {
        services.bgExecutor.submit(services.rollup::runOnce);
        ctx.redirect("/dash/stats?rollup=queued");
    }

    /** Per-channel messages-per-day over the last {@code days} (default 60). */
    public void messagesDailyApi(Context ctx) throws Exception {
        int days = clampDays(ctx.queryParam("days"), ACTIVITY_DAYS, 7, 180);
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate fromDay = today.minusDays(days - 1L);
        String fromKey = DAY_FMT.format(fromDay);
        String toKey = DAY_FMT.format(today);

        // Rank channels by total volume across the window, keep top N, group rest into "Other".
        List<DailyMetricsDao.DimPoint> rows = services.dailyMetricsDao
                .rangeDimensioned(DailyMetricsDao.M_DISCORD_MSGS_CHANNEL, fromKey, toKey);
        Map<String, Long> totals = new HashMap<>();
        for (var p : rows) totals.merge(p.dimension(), p.value(), Long::sum);
        List<String> topChannelIds = totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_CHANNELS_IN_CHART)
                .map(Map.Entry::getKey)
                .toList();
        java.util.Set<String> topSet = new java.util.HashSet<>(topChannelIds);

        Map<String, Integer> dayIndex = new HashMap<>(days);
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode labels = root.putArray("labels");
        for (int i = 0; i < days; i++) {
            String dk = DAY_FMT.format(fromDay.plusDays(i));
            labels.add(dk);
            dayIndex.put(dk, i);
        }

        // Fill each top channel; sum remainder into "other".
        Map<String, long[]> bucket = new LinkedHashMap<>();
        for (String id : topChannelIds) bucket.put(id, new long[days]);
        long[] other = new long[days];
        boolean haveOther = false;
        for (var p : rows) {
            Integer idx = dayIndex.get(p.day());
            if (idx == null) continue;
            if (topSet.contains(p.dimension())) {
                bucket.get(p.dimension())[idx] += p.value();
            } else {
                other[idx] += p.value();
                haveOther = true;
            }
        }

        ArrayNode series = root.putArray("series");
        for (var entry : bucket.entrySet()) {
            ObjectNode s = series.addObject();
            s.put("name", resolveChannelName(entry.getKey()));
            s.put("channel_id", entry.getKey());
            ArrayNode data = s.putArray("data");
            for (long v : entry.getValue()) data.add(v);
        }
        if (haveOther) {
            ObjectNode s = series.addObject();
            s.put("name", "Other");
            ArrayNode data = s.putArray("data");
            for (long v : other) data.add(v);
        }

        long lastRollup = services.analyticsMetaDao
                .get(io.warden.data.dao.AnalyticsMetaDao.KEY_LAST_ROLLUP_AT)
                .map(Long::parseLong).orElse(0L);
        ObjectNode metaNode = root.putObject("meta");
        metaNode.put("days", days);
        metaNode.put("last_rollup_at", lastRollup);

        ctx.header("Cache-Control", "no-store");
        ctx.json(root);
    }

    /** Top N channels by total messages in the trailing {@code days} window. */
    public void topChannelsApi(Context ctx) throws Exception {
        int days = clampDays(ctx.queryParam("days"), TOP_CHANNELS_DAYS, 1, 90);
        int limit = clampDays(ctx.queryParam("limit"), 15, 1, 100);
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String fromKey = DAY_FMT.format(today.minusDays(days - 1L));
        String toKey = DAY_FMT.format(today);

        List<DailyMetricsDao.DimPoint> rows = services.dailyMetricsDao
                .rangeDimensioned(DailyMetricsDao.M_DISCORD_MSGS_CHANNEL, fromKey, toKey);
        Map<String, Long> totals = new HashMap<>();
        for (var p : rows) totals.merge(p.dimension(), p.value(), Long::sum);

        List<Map.Entry<String, Long>> top = totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .toList();

        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("channels");
        for (var entry : top) {
            ObjectNode o = arr.addObject();
            o.put("channel_id", entry.getKey());
            o.put("name", resolveChannelName(entry.getKey()));
            o.put("messages", entry.getValue());
        }
        root.put("days", days);
        ctx.header("Cache-Control", "no-store");
        ctx.json(root);
    }

    /** Top-N joins grouped by invite label (falling back to code) over a trailing window. */
    public void joinsByInviteApi(Context ctx) throws Exception {
        int days = clampDays(ctx.queryParam("days"), WINDOW_DAYS, 1, 365);
        long toMs = System.currentTimeMillis();
        long fromMs = LocalDate.now(ZoneId.systemDefault())
                .minusDays(days - 1L)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();

        var rows = services.inviteDao.joinsByInvite(fromMs, toMs);

        // Group by label when present so multiple Reddit invites collapse into one bar.
        Map<String, long[]> grouped = new LinkedHashMap<>();
        for (var r : rows) {
            String key = (r.label() != null && !r.label().isBlank()) ? r.label() : "#" + r.code();
            grouped.computeIfAbsent(key, k -> new long[]{0L})[0] += r.count();
        }
        // Also report the "unattributed" bucket: joins in the window that don't carry an invite_code.
        long unattributed = 0;
        try (var c = services.database.connection();
             var ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM discord_member_events "
                             + "WHERE kind = 'join' AND invite_code IS NULL "
                             + "AND at >= ? AND at <= ?")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (var rs = ps.executeQuery()) {
                rs.next();
                unattributed = rs.getLong(1);
            }
        }

        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("days", days);
        ArrayNode arr = root.putArray("groups");
        grouped.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(
                        (a, b) -> Long.compare(b[0], a[0])))
                .forEach(e -> {
                    ObjectNode o = arr.addObject();
                    o.put("name", e.getKey());
                    o.put("count", e.getValue()[0]);
                });
        if (unattributed > 0) {
            ObjectNode o = arr.addObject();
            o.put("name", "Untracked");
            o.put("count", unattributed);
        }

        ctx.header("Cache-Control", "no-store");
        ctx.json(root);
    }

    /** Cohort retention page. */
    public void retentionPage(Context ctx) {
        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head("Retention · Stats · Warden", "stats", ctx));
        h.append(pageHeader("retention"));

        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<div class=\"d-flex justify-content-between align-items-baseline mb-2 flex-wrap gap-2\">");
        h.append("<h2 class=\"h5 mb-0\">Cohort retention")
                .append(Layout.infoIcon("Each row is an ISO-week cohort of users who first appeared in that week. "
                        + "Size is the cohort headcount. D1/D7/D30 show the percentage of that cohort that returned "
                        + "on the day-1, day-7, or day-30 anniversary slice (a one-day window). 'Discord' counts a "
                        + "message as a return; 'Minecraft' counts a new login session. A '-' means the cohort hasn't "
                        + "aged enough for that horizon yet. Recomputed weekly."))
                .append("</h2>");
        h.append("<div class=\"btn-group btn-group-sm\" role=\"group\">");
        h.append("<button type=\"button\" class=\"btn btn-outline-primary active\" data-retention-source=\"discord\">Discord</button>");
        h.append("<button type=\"button\" class=\"btn btn-outline-primary\" data-retention-source=\"mc\">Minecraft</button>");
        h.append("</div>");
        h.append("</div>");
        h.append("<p class=\"text-secondary small mb-3\" id=\"warden-retention-asof\">loading...</p>");
        h.append("<div class=\"table-responsive\"><table class=\"table table-sm align-middle mb-0 warden-cohort\">");
        h.append("<thead><tr>")
                .append("<th>Cohort</th><th class=\"text-end\">Size</th>")
                .append("<th class=\"text-end\">D1</th><th class=\"text-end\">D7</th><th class=\"text-end\">D30</th>")
                .append("</tr></thead><tbody id=\"warden-cohort-rows\">")
                .append("<tr><td colspan=5 class=\"text-secondary\">loading...</td></tr>")
                .append("</tbody></table></div>");
        h.append("</div></div>");

        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<h2 class=\"h5 mb-2\">Survival curve <span class=\"text-secondary small\">(average across cohorts)</span>")
                .append(Layout.infoIcon("The cohort table averaged into a single curve: day 0 is 100% by definition, "
                        + "and each of D1/D7/D30 is the mean of that column across every cohort where the value isn't "
                        + "blank. A drop between two horizons is the cohort's typical attrition over that interval."))
                .append("</h2>");
        h.append("<div id=\"warden-retention-curve\" style=\"min-height:300px\"></div>");
        h.append("</div></div>");

        h.append(APEX_CDN);
        h.append("<style>")
                .append(".warden-cohort td.cell-rate{position:relative;text-align:right;font-variant-numeric:tabular-nums}")
                .append(".warden-cohort td.cell-rate.empty{color:var(--bs-secondary-color)}")
                .append(".warden-cohort td.cell-rate .rate-fill{position:absolute;inset:0;border-radius:.2rem;")
                .append("opacity:.18;background:#22c55e;pointer-events:none}")
                .append(".warden-cohort td.cell-rate .rate-text{position:relative}")
                .append("</style>");
        h.append("<script>").append(RETENTION_BOOT_JS).append("</script>");
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    /** Returns the cohort/retention rows for one source ("discord" or "mc"). */
    public void retentionApi(Context ctx) throws Exception {
        String source = ctx.queryParam("source");
        if (source == null || !(DailyMetricsDao.DIM_DISCORD.equals(source) || DailyMetricsDao.DIM_MC.equals(source))) {
            source = DailyMetricsDao.DIM_DISCORD;
        }
        long lastCohort = services.analyticsMetaDao
                .get(io.warden.data.dao.AnalyticsMetaDao.KEY_LAST_COHORT_AT)
                .map(Long::parseLong).orElse(0L);

        // Read every retention row, then filter to the requested source. The
        // cohort table only ever holds ~26 weeks per source so this stays cheap.
        String from = "0000-W00";
        String to = "9999-W99";
        Map<String, Long> sizes = filterBySource(services.dailyMetricsDao.rangeDimensioned(
                DailyMetricsDao.M_RETENTION_SIZE, from, to), source);
        Map<String, Long> d1   = filterBySource(services.dailyMetricsDao.rangeDimensioned(
                DailyMetricsDao.M_RETENTION_D1,  from, to), source);
        Map<String, Long> d7   = filterBySource(services.dailyMetricsDao.rangeDimensioned(
                DailyMetricsDao.M_RETENTION_D7,  from, to), source);
        Map<String, Long> d30  = filterBySource(services.dailyMetricsDao.rangeDimensioned(
                DailyMetricsDao.M_RETENTION_D30, from, to), source);

        java.util.TreeSet<String> weeks = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        weeks.addAll(sizes.keySet());

        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("source", source);
        root.put("last_cohort_at", lastCohort);
        ArrayNode rows = root.putArray("weeks");
        for (String w : weeks) {
            ObjectNode o = rows.addObject();
            o.put("cohort", w);
            o.put("size", sizes.getOrDefault(w, 0L));
            putRate(o, "d1",  d1.get(w));
            putRate(o, "d7",  d7.get(w));
            putRate(o, "d30", d30.get(w));
        }

        ctx.header("Cache-Control", "no-store");
        ctx.json(root);
    }

    private static Map<String, Long> filterBySource(
            List<DailyMetricsDao.DimPoint> rows, String source) {
        Map<String, Long> out = new HashMap<>();
        for (var r : rows) if (source.equals(r.dimension())) out.put(r.day(), r.value());
        return out;
    }

    /** Geography page: distribution of Minecraft sessions across countries. */
    public void geoPage(Context ctx) {
        var status = services.geoip.status();
        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head("Geography · Stats · Warden", "stats", ctx));
        h.append(pageHeader("geo"));

        if (!status.enabled() || !status.hasLicense()) {
            h.append("<div class=\"alert alert-info\" role=\"alert\">");
            h.append("<h2 class=\"h5 mb-2\"><i class=\"bi bi-globe2 me-1\"></i>GeoIP is not configured</h2>");
            h.append("<p class=\"mb-2\">Warden can colour-code Minecraft logins by country using MaxMind's free "
                    + "GeoLite2 data. To turn this on:</p>");
            h.append("<ol class=\"mb-2\"><li>Sign up free at "
                    + "<a href=\"https://www.maxmind.com/en/geolite2/signup\" target=\"_blank\" rel=\"noopener\">"
                    + "maxmind.com/en/geolite2/signup</a> and generate a licence key.</li>");
            h.append("<li>Open the <a href=\"/dash/config?tab=geoip\">GeoIP tab on the Config page</a>, "
                    + "tick <em>Enable GeoIP</em>, and paste the key. "
                    + "(Or supply the key via the <code>WARDEN_GEOIP_LICENSE_KEY</code> env var.)</li>");
            h.append("<li>Restart the server; Warden will download the database into "
                    + "<code>plugins/Warden/data/geoip/</code> and refresh it weekly.</li></ol>");
            h.append("<p class=\"mb-0 small text-secondary\">Discord-side geography isn't shown because Discord "
                    + "doesn't expose user IPs to bots. Only Minecraft logins are mapped.</p>");
            h.append("</div>");
            h.append(Layout.foot());
            ctx.html(h.toString());
            return;
        }
        if (!status.ready()) {
            h.append("<div class=\"alert alert-warning\" role=\"alert\">");
            h.append("<h2 class=\"h5 mb-2\"><i class=\"bi bi-hourglass-split me-1\"></i>Database not loaded</h2>");
            h.append("<p class=\"mb-1\">GeoIP is enabled but the ").append(escape(status.edition()))
                    .append(" database hasn't been opened yet.</p>");
            if (status.lastError() != null) {
                h.append("<p class=\"mb-0\"><code>").append(escape(status.lastError())).append("</code></p>");
            } else {
                h.append("<p class=\"mb-0 small text-secondary\">Initial download usually finishes within a "
                        + "minute of plugin start. Refresh this page.</p>");
            }
            h.append("</div>");
            h.append(Layout.foot());
            ctx.html(h.toString());
            return;
        }

        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<div class=\"d-flex justify-content-between align-items-baseline mb-2 flex-wrap gap-2\">");
        h.append("<h2 class=\"h5 mb-0\">Where players log in from <span class=\"text-secondary small\">(last ")
                .append(GEO_DAYS).append(" days)</span>")
                .append(Layout.infoIcon("Distinct Minecraft accounts per country, based on the IP MaxMind GeoLite2 "
                        + "resolved at login. Sessions with no country (older data, lookup miss, or non-routable "
                        + "addresses) are bucketed under 'Unknown'. Discord-side geography isn't available."))
                .append("</h2>");
        h.append("<span class=\"text-secondary small\" id=\"warden-geo-asof\">").append(geoStatusLine(status)).append("</span>");
        h.append("</div>");
        h.append("<div id=\"warden-geo-map\" style=\"width:100%;height:460px;background:transparent\"></div>");
        h.append("</div></div>");

        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<h2 class=\"h5 mb-2\">Top countries")
                .append(Layout.infoIcon("Ranked by distinct Minecraft accounts seen logging in from that country "
                        + "in the window. The bar is relative to the top country; the sessions column is the raw "
                        + "login count."))
                .append("</h2>");
        h.append("<div class=\"table-responsive\"><table class=\"table table-sm align-middle mb-0\">");
        h.append("<thead><tr><th>Country</th><th class=\"text-end\">Players</th>"
                + "<th class=\"text-end\">Sessions</th><th></th></tr></thead>");
        h.append("<tbody id=\"warden-geo-rows\"><tr><td colspan=4 class=\"text-secondary\">loading...</td></tr></tbody>");
        h.append("</table></div></div></div>");

        h.append("<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/jsvectormap@1.6.0/dist/css/jsvectormap.min.css\">");
        h.append("<script src=\"https://cdn.jsdelivr.net/npm/jsvectormap@1.6.0/dist/jsvectormap.min.js\"></script>");
        h.append("<script src=\"https://cdn.jsdelivr.net/npm/jsvectormap@1.6.0/dist/maps/world.js\"></script>");
        h.append("<script>").append(GEO_BOOT_JS).append("</script>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    /**
     * Geography API. Returns the country distribution of Minecraft sessions
     * in the trailing window, plus a small status block so the page can show
     * "GeoIP not configured" or "last refresh: ..." without a second roundtrip.
     */
    public void geoApi(Context ctx) throws Exception {
        int days = clampDays(ctx.queryParam("days"), GEO_DAYS, 1, 365);
        long now = System.currentTimeMillis();
        long fromMs = LocalDate.now(ZoneId.systemDefault())
                .minusDays(days - 1L)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();

        List<McSessionDao.CountryCount> rows = services.mcSessionDao.countryCountsBetween(fromMs, now);

        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("days", days);

        ObjectNode statusNode = root.putObject("status");
        var st = services.geoip.status();
        statusNode.put("enabled", st.enabled());
        statusNode.put("has_license", st.hasLicense());
        statusNode.put("ready", st.ready());
        statusNode.put("edition", st.edition() == null ? "" : st.edition());
        if (st.dbModifiedAt() != null) statusNode.put("db_modified_at", st.dbModifiedAt());
        if (st.lastRefreshAt() != null) statusNode.put("last_refresh_at", st.lastRefreshAt());
        if (st.lastError() != null) statusNode.put("last_error", st.lastError());

        ArrayNode arr = root.putArray("countries");
        long total = 0;
        long unknown = 0;
        for (var r : rows) {
            total += r.players();
            if ("??".equals(r.country())) unknown += r.players();
            ObjectNode o = arr.addObject();
            o.put("code", r.country());
            o.put("name", countryName(r.country()));
            o.put("players", r.players());
            o.put("sessions", r.sessions());
        }
        root.put("total_players", total);
        root.put("unknown_players", unknown);

        ctx.header("Cache-Control", "no-store");
        ctx.json(root);
    }

    private static String geoStatusLine(io.warden.analytics.GeoIpService.Status st) {
        if (st.lastRefreshAt() != null) {
            return "last refresh: <span data-ts=\"" + st.lastRefreshAt() + "\">"
                    + isoDate(st.lastRefreshAt()) + "</span>";
        }
        if (st.dbModifiedAt() != null) {
            return "database on disk: <span data-ts=\"" + st.dbModifiedAt() + "\">"
                    + isoDate(st.dbModifiedAt()) + "</span>";
        }
        return "loaded";
    }

    private static String isoDate(long ms) {
        return java.time.Instant.ofEpochMilli(ms)
                .atZone(ZoneId.systemDefault())
                .toLocalDate().toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Best-effort country code -> display name. Uses {@link java.util.Locale}
     * so we don't carry a hand-maintained table; falls back to the raw code
     * when the locale doesn't know it.
     */
    private static String countryName(String iso) {
        if (iso == null || "??".equals(iso) || iso.isBlank()) return "Unknown";
        try {
            String name = new java.util.Locale.Builder().setRegion(iso).build()
                    .getDisplayCountry(java.util.Locale.ENGLISH);
            return (name == null || name.isBlank() || iso.equals(name)) ? iso : name;
        } catch (Exception e) {
            return iso;
        }
    }

    private static void putRate(ObjectNode o, String key, Long valueX1000) {
        if (valueX1000 == null) o.putNull(key);
        else o.put(key, valueX1000 / 1000.0);
    }

    /**
     * Onboarding funnel for the trailing {@code days} window. Each step is a
     * count of distinct discord ids who hit that audit event. Counts are NOT
     * forced monotonic; if a downstream step has more than its upstream it
     * means events from before the window are leaking in, which is honest data.
     */
    public void funnelApi(Context ctx) throws Exception {
        int days = clampDays(ctx.queryParam("days"), WINDOW_DAYS, 1, 365);
        long now = System.currentTimeMillis();
        long fromMs = LocalDate.now(ZoneId.systemDefault())
                .minusDays(days - 1L)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();

        // 1. Joined Discord (raw count from discord_member_events; doesn't need a target_discord_id audit row)
        long joined;
        try (var c = services.database.connection();
             var ps = c.prepareStatement(
                     "SELECT COUNT(DISTINCT discord_id) FROM discord_member_events "
                             + "WHERE kind = 'join' AND at >= ? AND at <= ?")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, now);
            try (var rs = ps.executeQuery()) {
                rs.next();
                joined = rs.getLong(1);
            }
        }

        long delivered = services.auditDao.countAction("onboarding_dm_sent", fromMs, now, true)
                + services.auditDao.countAction("onboarding_channel_sent", fromMs, now, true);
        long started   = services.auditDao.countAction("onboarding_linked",  fromMs, now, true);
        long rules     = services.auditDao.countAction("rules_accepted",     fromMs, now, true);
        long submitted = services.auditDao.countAction("application_submitted", fromMs, now, true);
        long approved  = services.auditDao.countAction("application_approved",  fromMs, now, true);
        long denied    = services.auditDao.countAction("application_denied",    fromMs, now, true);
        long escalated = services.auditDao.countAction("application_escalated", fromMs, now, true);

        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode steps = root.putArray("steps");
        addStep(steps, "Joined Discord",     joined);
        addStep(steps, "Onboarding sent",    delivered);
        addStep(steps, "Started onboarding", started);
        addStep(steps, "Accepted rules",     rules);
        addStep(steps, "Submitted answers",  submitted);
        addStep(steps, "Approved",           approved);

        ObjectNode outcomes = root.putObject("outcomes");
        outcomes.put("approved", approved);
        outcomes.put("denied", denied);
        outcomes.put("escalated", escalated);

        root.put("days", days);
        ctx.header("Cache-Control", "no-store");
        ctx.json(root);
    }

    /* ---------- helpers ---------- */

    private static void addStep(ArrayNode steps, String name, long count) {
        ObjectNode o = steps.addObject();
        o.put("name", name);
        o.put("count", count);
    }

    private static int clampDays(String raw, int defaultValue, int min, int max) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int v = Integer.parseInt(raw);
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Best-effort JDA cache lookup so the chart shows #welcome instead of 12345678. */
    private String resolveChannelName(String channelId) {
        if (channelId == null || channelId.isBlank()) return "(unknown)";
        if (discord != null) {
            JDA jda = discord.jda();
            if (jda != null) {
                try {
                    GuildChannel gc = jda.getGuildChannelById(channelId);
                    if (gc != null) return "#" + gc.getName();
                } catch (Exception ignored) {
                    // fall through to raw id
                }
            }
        }
        return "#" + (channelId.length() > 6 ? channelId.substring(0, 6) + "..." : channelId);
    }

    /** Sub-nav rendered above the stats body. {@code activeKey} is "overview" | "activity". */
    private static String pageHeader(String activeKey) {
        StringBuilder s = new StringBuilder();
        s.append("<h1 class=\"h3 mb-2\">Stats</h1>");
        s.append("<p class=\"text-secondary mb-3\">Activity rolled up nightly from event tables. "
                + "Updates land within 24h of new traffic, or right after a manual rollup.</p>");
        s.append("<ul class=\"nav nav-pills mb-4\">");
        s.append(subnavItem(activeKey, "overview",  "/dash/stats",            "bi-graph-up",   "Overview"));
        s.append(subnavItem(activeKey, "activity",  "/dash/stats/activity",   "bi-chat-text",  "Activity"));
        s.append(subnavItem(activeKey, "retention", "/dash/stats/retention",  "bi-calendar-week", "Retention"));
        s.append(subnavItem(activeKey, "geo",       "/dash/stats/geo",        "bi-globe2",     "Geography"));
        s.append("</ul>");
        return s.toString();
    }

    private static String subnavItem(String activeKey, String key, String href, String icon, String label) {
        boolean active = key.equals(activeKey);
        return "<li class=\"nav-item\"><a class=\"nav-link" + (active ? " active" : "") + "\""
                + " href=\"" + href + "\"><i class=\"bi " + icon + " me-1\"></i>" + label + "</a></li>";
    }

    private static final String APEX_CDN =
            "<script src=\"https://cdn.jsdelivr.net/npm/apexcharts@3.54.1/dist/apexcharts.min.js\"></script>";

    private long distinctOver(String table, String column, String timeColumn, long startMs, long endMs)
            throws java.sql.SQLException {
        String sql = "SELECT COUNT(DISTINCT " + column + ") FROM " + table
                + " WHERE " + timeColumn + " >= ? AND " + timeColumn + " < ?";
        try (var c = services.database.connection();
             var ps = c.prepareStatement(sql)) {
            ps.setLong(1, startMs);
            ps.setLong(2, endMs);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void addSeries(ArrayNode arr, String name, long[] points) {
        ObjectNode o = arr.addObject();
        o.put("name", name);
        ArrayNode data = o.putArray("data");
        for (long v : points) data.add(v);
    }

    private static String humanizeMs(long ms) {
        if (ms <= 0) return "n/a";
        Duration d = Duration.ofMillis(ms);
        if (d.toMinutes() < 1) return d.toSeconds() + "s";
        if (d.toHours() < 1)   return d.toMinutes() + "m";
        if (d.toDays() < 1)    return d.toHours() + "h " + (d.toMinutesPart()) + "m";
        return d.toDays() + "d " + d.toHoursPart() + "h";
    }

    private static String card(String slot, String icon, String labelHtml, String accent, String tip) {
        return "<div class=\"col-sm-6 col-xl-4\">"
                + "<div class=\"card h-100\">"
                + "<div class=\"card-body d-flex align-items-center gap-3\">"
                + "<div class=\"flex-shrink-0 rounded-circle d-flex align-items-center justify-content-center bg-" + accent + "-subtle\""
                + " style=\"width:48px;height:48px\"><i class=\"bi " + icon + " text-" + accent + " fs-4\"></i></div>"
                + "<div class=\"flex-grow-1\">"
                + "<div class=\"text-secondary small\">" + labelHtml + Layout.infoIcon(tip) + "</div>"
                + "<div class=\"fs-3 fw-semibold\" data-stat=\"" + slot + "\">…</div>"
                + "</div></div></div></div>";
    }

    private static final String CHART_BOOT_JS = ""
            + "(async function(){"
            + "  const set = (k,v) => { var el = document.querySelector('[data-stat=\"'+k+'\"]');"
            + "    if(el) el.textContent = (v===null||v===undefined?'-':v); };"
            + "  let data;"
            + "  try { const r = await fetch('/dash/api/overview', {headers:{'Accept':'application/json'}}); data = await r.json(); }"
            + "  catch (e) { document.getElementById('warden-stats-asof').textContent = 'Failed to load stats: ' + e; return; }"
            + "  set('total_members', data.cards.total_members);"
            + "  set('discord_mau',   data.cards.discord_mau);"
            + "  set('mc_mau',        data.cards.mc_mau);"
            + "  set('pending',       data.cards.pending);"
            + "  set('approval_rate', data.cards.approval_rate_pct===null ? 'n/a' : (data.cards.approval_rate_pct + '%'));"
            + "  set('avg_decision',  data.cards.avg_decision_human);"
            + "  const asof = document.getElementById('warden-stats-asof');"
            + "  if (data.meta.last_rollup_at) {"
            + "    asof.textContent = 'last rollup: ' + new Date(data.meta.last_rollup_at).toLocaleString();"
            + "  } else { asof.textContent = 'never rolled up yet'; }"
            + "  const isDark = (document.documentElement.getAttribute('data-bs-theme')||'light') === 'dark';"
            + "  const options = {"
            + "    chart: { type:'area', height:340, toolbar:{show:false}, zoom:{enabled:false}, animations:{enabled:false}, foreColor: isDark?'#cbd2e6':'#3a4264' },"
            + "    series: data.timeline.series,"
            + "    xaxis: { categories: data.timeline.labels, type:'datetime', labels:{datetimeUTC:false} },"
            + "    stroke: { curve:'smooth', width:2 },"
            + "    dataLabels: { enabled:false },"
            + "    fill: { type:'gradient', gradient:{ opacityFrom:0.45, opacityTo:0.05 } },"
            + "    legend: { position:'top', horizontalAlign:'right' },"
            + "    colors: ['#3b82f6','#ef4444','#22c55e'],"
            + "    grid: { borderColor: isDark?'#2a3158':'#e5e7eb' },"
            + "    tooltip: { theme: isDark?'dark':'light', shared:true }"
            + "  };"
            + "  const chart = new ApexCharts(document.getElementById('warden-stats-timeline'), options);"
            + "  chart.render();"
            + "  try {"
            + "    const fr = await fetch('/dash/api/funnel', {headers:{'Accept':'application/json'}});"
            + "    const fd = await fr.json();"
            + "    const fOpts = {"
            + "      chart: { type:'bar', height:340, toolbar:{show:false}, animations:{enabled:false}, foreColor: isDark?'#cbd2e6':'#3a4264' },"
            + "      plotOptions: { bar: { horizontal:true, distributed:true, borderRadius:4, dataLabels:{position:'bottom'} } },"
            + "      series: [{ name:'count', data: fd.steps.map(s=>s.count) }],"
            + "      xaxis: { categories: fd.steps.map(s=>s.name) },"
            + "      yaxis: { labels:{ style:{ fontSize:'.85rem' } } },"
            + "      dataLabels: { enabled:true, textAnchor:'start', style:{colors:['#fff']}, formatter:(v,opts)=>{"
            + "        const max = Math.max(...opts.w.config.series[0].data) || 1;"
            + "        const pct = Math.round(100*v/max);"
            + "        return opts.w.config.xaxis.categories[opts.dataPointIndex] + ': ' + v + ' (' + pct + '%)';"
            + "      } },"
            + "      legend: { show:false },"
            + "      colors: ['#3b82f6','#6366f1','#8b5cf6','#a855f7','#ec4899','#22c55e'],"
            + "      grid: { borderColor: isDark?'#2a3158':'#e5e7eb' },"
            + "      tooltip: { theme: isDark?'dark':'light', y:{ formatter: v => v + ' member(s)' } }"
            + "    };"
            + "    new ApexCharts(document.getElementById('warden-funnel'), fOpts).render();"
            + "  } catch (e) { document.getElementById('warden-funnel').textContent = 'Failed to load funnel: ' + e; }"
            + "  try {"
            + "    const ir = await fetch('/dash/api/joins-by-invite?days=30', {headers:{'Accept':'application/json'}});"
            + "    const id = await ir.json();"
            + "    const inviteEl = document.getElementById('warden-joins-by-invite');"
            + "    if (!id.groups || id.groups.length === 0) {"
            + "      inviteEl.innerHTML = '<p class=\"text-secondary mb-0\">No tracked joins yet in the last 30 days. '"
            + "        + 'The tracker primes on bot ready - new invites get attributed automatically.</p>'; return;"
            + "    }"
            + "    const iOpts = {"
            + "      chart: { type:'bar', height:300, toolbar:{show:false}, animations:{enabled:false}, foreColor: isDark?'#cbd2e6':'#3a4264' },"
            + "      plotOptions: { bar: { horizontal:true, distributed:true, borderRadius:4 } },"
            + "      series: [{ name:'joins', data: id.groups.map(g => g.count) }],"
            + "      xaxis: { categories: id.groups.map(g => g.name) },"
            + "      yaxis: { labels:{ style:{ fontSize:'.85rem' } } },"
            + "      dataLabels: { enabled:true, formatter: v => v },"
            + "      legend: { show:false },"
            + "      colors: ['#3b82f6','#f97316','#22c55e','#a855f7','#ec4899','#10b981','#f59e0b','#6366f1','#ef4444','#94a3b8'],"
            + "      grid: { borderColor: isDark?'#2a3158':'#e5e7eb' },"
            + "      tooltip: { theme: isDark?'dark':'light', y:{ formatter: v => v + ' join(s)' } }"
            + "    };"
            + "    new ApexCharts(inviteEl, iOpts).render();"
            + "  } catch (e) { document.getElementById('warden-joins-by-invite').textContent = 'Failed to load invites: ' + e; }"
            + "})();";

    private static final String RETENTION_BOOT_JS = ""
            + "(function(){"
            + "  let source = 'discord';"
            + "  let curveChart = null;"
            + "  const isDark = (document.documentElement.getAttribute('data-bs-theme')||'light') === 'dark';"
            + "  function pct(v){ return v===null||v===undefined ? null : Math.round(v*100); }"
            + "  function cell(v){"
            + "    if (v === null || v === undefined) return '<td class=\"cell-rate empty\">-</td>';"
            + "    const p = Math.round(v*100);"
            + "    const w = Math.max(2, Math.min(100, p));"
            + "    return '<td class=\"cell-rate\"><div class=\"rate-fill\" style=\"width:'+w+'%\"></div>'"
            + "         + '<span class=\"rate-text\">'+p+'%</span></td>';"
            + "  }"
            + "  async function load(){"
            + "    const r = await fetch('/dash/api/retention?source=' + source, {headers:{'Accept':'application/json'}});"
            + "    const d = await r.json();"
            + "    const asof = document.getElementById('warden-retention-asof');"
            + "    if (d.last_cohort_at) asof.textContent = 'last cohort recompute: ' + new Date(d.last_cohort_at).toLocaleString();"
            + "    else asof.textContent = 'cohorts have not been recomputed yet';"
            + "    const tbody = document.getElementById('warden-cohort-rows');"
            + "    if (!d.weeks || d.weeks.length === 0) {"
            + "      tbody.innerHTML = '<tr><td colspan=5 class=\"text-secondary\">No cohort data yet for this source.</td></tr>';"
            + "    } else {"
            + "      tbody.innerHTML = d.weeks.map(w => '<tr>'"
            + "        + '<td><code>' + w.cohort + '</code></td>'"
            + "        + '<td class=\"text-end fw-semibold\">' + w.size.toLocaleString() + '</td>'"
            + "        + cell(w.d1) + cell(w.d7) + cell(w.d30) + '</tr>').join('');"
            + "    }"
            + "    // Survival curve: average D1/D7/D30 across all cohorts where the value is non-null."
            + "    const avg = (k) => {"
            + "      const vals = (d.weeks||[]).map(w => w[k]).filter(v => v!==null && v!==undefined);"
            + "      if (vals.length === 0) return null;"
            + "      return vals.reduce((a,b)=>a+b,0) / vals.length;"
            + "    };"
            + "    const data = [{x:0,y:100}, {x:1,y:pct(avg('d1'))||null}, {x:7,y:pct(avg('d7'))||null}, {x:30,y:pct(avg('d30'))||null}];"
            + "    const opts = {"
            + "      chart: { type:'line', height:300, toolbar:{show:false}, animations:{enabled:false}, foreColor: isDark?'#cbd2e6':'#3a4264' },"
            + "      series: [{ name:'% retained', data: data }],"
            + "      xaxis: { type:'numeric', title:{text:'Days after joining'}, tickAmount:6 },"
            + "      yaxis: { min:0, max:100, title:{text:'Retained %'} },"
            + "      stroke: { curve:'smooth', width:3 },"
            + "      markers: { size:5 },"
            + "      dataLabels: { enabled:true, formatter: v => v===null?'':v+'%' },"
            + "      colors: [source === 'mc' ? '#22c55e' : '#5865F2'],"
            + "      grid: { borderColor: isDark?'#2a3158':'#e5e7eb' },"
            + "      tooltip: { theme: isDark?'dark':'light', y:{ formatter: v => v===null?'n/a':v+'%' } }"
            + "    };"
            + "    if (curveChart) { curveChart.destroy(); }"
            + "    curveChart = new ApexCharts(document.getElementById('warden-retention-curve'), opts);"
            + "    curveChart.render();"
            + "  }"
            + "  document.querySelectorAll('[data-retention-source]').forEach(b => {"
            + "    b.addEventListener('click', () => {"
            + "      source = b.getAttribute('data-retention-source');"
            + "      document.querySelectorAll('[data-retention-source]').forEach(x => x.classList.toggle('active', x===b));"
            + "      load();"
            + "    });"
            + "  });"
            + "  load();"
            + "})();";

    private static final String ACTIVITY_BOOT_JS = ""
            + "(async function(){"
            + "  const isDark = (document.documentElement.getAttribute('data-bs-theme')||'light') === 'dark';"
            + "  const asof = document.getElementById('warden-activity-asof');"
            + "  try {"
            + "    const r = await fetch('/dash/api/messages/daily?days=60', {headers:{'Accept':'application/json'}});"
            + "    const d = await r.json();"
            + "    if (d.meta && d.meta.last_rollup_at) {"
            + "      asof.textContent = 'last rollup: ' + new Date(d.meta.last_rollup_at).toLocaleString();"
            + "    } else { asof.textContent = 'never rolled up yet'; }"
            + "    const opts = {"
            + "      chart: { type:'area', height:380, stacked:true, toolbar:{show:false}, animations:{enabled:false}, foreColor: isDark?'#cbd2e6':'#3a4264' },"
            + "      series: d.series,"
            + "      xaxis: { categories: d.labels, type:'datetime', labels:{datetimeUTC:false} },"
            + "      stroke: { curve:'smooth', width:0 },"
            + "      fill: { type:'solid', opacity:.65 },"
            + "      dataLabels: { enabled:false },"
            + "      legend: { position:'top', horizontalAlign:'right' },"
            + "      grid: { borderColor: isDark?'#2a3158':'#e5e7eb' },"
            + "      tooltip: { theme: isDark?'dark':'light', shared:true }"
            + "    };"
            + "    new ApexCharts(document.getElementById('warden-activity-channels'), opts).render();"
            + "  } catch (e) {"
            + "    document.getElementById('warden-activity-channels').textContent = 'Failed to load channel chart: ' + e;"
            + "    asof.textContent = '';"
            + "  }"
            + "  try {"
            + "    const r = await fetch('/dash/api/top-channels?days=7&limit=15', {headers:{'Accept':'application/json'}});"
            + "    const d = await r.json();"
            + "    const tbody = document.getElementById('warden-top-channels');"
            + "    if (!d.channels || d.channels.length === 0) {"
            + "      tbody.innerHTML = '<tr><td colspan=3 class=\"text-secondary\">No channel traffic in this window yet.</td></tr>';"
            + "      return;"
            + "    }"
            + "    const max = d.channels[0].messages || 1;"
            + "    tbody.innerHTML = d.channels.map(c => {"
            + "      const pct = Math.round(100 * c.messages / max);"
            + "      const safe = (c.name||'').replace(/[<>&]/g, x => ({\"<\":\"&lt;\",\">\":\"&gt;\",\"&\":\"&amp;\"}[x]));"
            + "      return '<tr><td>' + safe + '</td>'"
            + "        + '<td class=\"text-end fw-semibold\">' + c.messages.toLocaleString() + '</td>'"
            + "        + '<td style=\"width:50%\"><div class=\"progress\" role=progressbar style=\"height:8px\">'"
            + "        + '<div class=\"progress-bar bg-info\" style=\"width:'+pct+'%\"></div></div></td></tr>';"
            + "    }).join('');"
            + "  } catch (e) {"
            + "    document.getElementById('warden-top-channels').innerHTML ="
            + "      '<tr><td colspan=3 class=text-danger>Failed to load top channels: ' + e + '</td></tr>';"
            + "  }"
            + "})();";

    private static final String GEO_BOOT_JS = ""
            + "(async function(){"
            + "  const isDark = (document.documentElement.getAttribute('data-bs-theme')||'light') === 'dark';"
            + "  function flag(code){"
            + "    if(!code || code.length !== 2 || code === '??') return '';"
            + "    const A = 0x1F1E6, base = 'A'.charCodeAt(0);"
            + "    return String.fromCodePoint(A + code.charCodeAt(0) - base, A + code.charCodeAt(1) - base);"
            + "  }"
            + "  function escapeText(s){return (s||'').replace(/[<>&]/g, x => ({\"<\":\"&lt;\",\">\":\"&gt;\",\"&\":\"&amp;\"}[x]));}"
            + "  let data;"
            + "  try { data = await (await fetch('/dash/api/geo', {headers:{'Accept':'application/json'}})).json(); }"
            + "  catch (e) { document.getElementById('warden-geo-asof').textContent = 'Failed to load: ' + e; return; }"
            + "  const map = {};"
            + "  let maxCount = 0;"
            + "  (data.countries||[]).forEach(c => {"
            + "    if (c.code && c.code !== '??') { map[c.code] = c.players; if (c.players > maxCount) maxCount = c.players; }"
            + "  });"
            + "  try {"
            + "    new jsVectorMap({"
            + "      selector: '#warden-geo-map',"
            + "      map: 'world',"
            + "      backgroundColor: 'transparent',"
            + "      regionStyle: { initial: { fill: isDark ? '#2a3158' : '#e5e7eb', stroke: isDark ? '#1c2140' : '#fff', strokeWidth: 0.4 },"
            + "                     hover:   { fill: isDark ? '#3b82f6' : '#93c5fd', cursor:'pointer' } },"
            + "    visualizeData: { scale: ['#bfdbfe','#1e40af'], values: map, normalizeFunction: 'polynomial' },"
            + "      onRegionTooltipShow: (event, tooltip, code) => {"
            + "        const players = map[code] || 0;"
            + "        tooltip.text(tooltip.text() + ' - ' + players + ' player' + (players===1?'':'s'));"
            + "      }"
            + "    });"
            + "  } catch (e) {"
            + "    document.getElementById('warden-geo-map').innerHTML ="
            + "      '<p class=\"text-secondary p-3 mb-0\">Map could not be drawn: ' + e + '</p>';"
            + "  }"
            + "  const tbody = document.getElementById('warden-geo-rows');"
            + "  const visible = (data.countries||[]).filter(c => c.players > 0);"
            + "  if (visible.length === 0) {"
            + "    tbody.innerHTML = '<tr><td colspan=4 class=\"text-secondary\">No country-tagged sessions yet. '"
            + "      + 'Players who logged in before GeoIP was enabled will not be back-filled.</td></tr>';"
            + "    return;"
            + "  }"
            + "  const topPlayers = visible[0].players || 1;"
            + "  tbody.innerHTML = visible.map(c => {"
            + "    const pct = Math.round(100 * c.players / topPlayers);"
            + "    const f = flag(c.code);"
            + "    const label = (c.code === '??')"
            + "      ? '<span class=\"text-secondary\">Unknown</span>'"
            + "      : (f ? f + ' ' : '') + escapeText(c.name) + ' <span class=\"text-secondary small\">(' + escapeText(c.code) + ')</span>';"
            + "    return '<tr><td>' + label + '</td>'"
            + "      + '<td class=\"text-end fw-semibold\">' + c.players.toLocaleString() + '</td>'"
            + "      + '<td class=\"text-end\">' + c.sessions.toLocaleString() + '</td>'"
            + "      + '<td style=\"width:35%\"><div class=\"progress\" role=progressbar style=\"height:8px\">'"
            + "      + '<div class=\"progress-bar bg-info\" style=\"width:'+pct+'%\"></div></div></td></tr>';"
            + "  }).join('');"
            + "})();";
}
