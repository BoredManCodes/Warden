package io.warden.web.handlers;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.warden.Services;
import io.warden.data.Json;
import io.warden.discord.DiscordService;
import io.warden.discord.OnboardingDelivery;
import io.warden.onboarding.OnboardingState;
import io.warden.onboarding.model.Application;
import io.warden.onboarding.model.UserRecord;
import io.warden.web.auth.AuditActor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class DashMembersHandlers {

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private static final int ACTIVITY_DAYS = 90;

    private final Services services;
    private final DiscordService discord; // may be null in tests

    public DashMembersHandlers(Services services, DiscordService discord) {
        this.services = services;
        this.discord = discord;
    }

    public void list(Context ctx) throws Exception {
        String filter = ctx.queryParam("state"); // optional
        List<UserRecord> all = services.userDao.listAll(2000);
        if (filter != null && !filter.isBlank()) {
            all = all.stream().filter(u -> u.state().wireName().equals(filter)).toList();
        }
        int total = services.userDao.countAll();

        String flash = ctx.queryParam("flash");
        String flashKind = ctx.queryParam("flash_kind");

        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head("Members · Warden", "members", ctx));
        h.append("<div class=\"d-flex justify-content-between align-items-start flex-wrap gap-2 mb-2\">");
        h.append("<h1 class=\"h3 mb-0\">Members</h1>");
        boolean bulkConfigured = services.bulkOnboard() != null && services.bulkOnboard().fullRoleConfigured();
        h.append("<form method=post action=\"/dash/members/bulk-onboard\" class=\"m-0\" ")
                .append("data-confirm=\"Approve every current Discord member, skip onboarding questions, assign the full role and strip the gated role? ")
                .append("Bots are skipped. Members already approved are unchanged. This does not send DMs or welcome announcements.\" ")
                .append("data-confirm-title=\"Bulk-onboard all members\" data-confirm-ok=\"Onboard everyone\" data-confirm-kind=\"warning\">")
                .append("<button class=\"btn btn-warning\" type=submit")
                .append(bulkConfigured ? "" : " disabled title=\"Set full role on /dash/config first\"")
                .append("><i class=\"bi bi-people-fill me-1\"></i>One-click onboard all members</button></form>");
        h.append("</div>");

        if (flash != null && !flash.isBlank()) {
            String klass = "success".equals(flashKind) ? "alert-success"
                    : "warning".equals(flashKind) ? "alert-warning"
                    : "error".equals(flashKind) || "danger".equals(flashKind) ? "alert-danger"
                    : "alert-info";
            h.append("<div class=\"alert ").append(klass).append("\">").append(esc(flash)).append("</div>");
        }
        if (!bulkConfigured) {
            h.append("<div class=\"alert alert-secondary small\">")
                    .append("Bulk-onboard needs a <strong>full role</strong> configured on <a href=\"/dash/config\">/dash/config</a>; ")
                    .append("without it there's nothing to assign and the button stays disabled.</div>");
        }

        h.append("<p class=\"text-secondary mb-3\">")
                .append(all.size()).append(" shown of ").append(total).append(" total</p>");

        h.append("<div class=\"mb-4 d-flex flex-wrap gap-2\">");
        h.append("<span class=\"align-self-center text-secondary small me-1\">filter by state:</span>");
        for (var s : OnboardingState.values()) {
            String w = s.wireName();
            boolean active = w.equals(filter);
            h.append("<a class=\"btn btn-sm ")
                    .append(active ? "btn-primary" : "btn-outline-secondary")
                    .append("\" href=\"?state=").append(w).append("\">").append(esc(s.label())).append("</a>");
        }
        if (filter != null) {
            h.append("<a class=\"btn btn-sm btn-link\" href=/dash/members>clear</a>");
        }
        h.append("</div>");

        var srv = services.discordSrv();
        boolean srvPresent = srv != null && srv.isPresent();
        h.append("<div class=\"table-responsive\"><table class=\"table table-hover table-sm align-middle\">")
                .append("<thead><tr>")
                .append("<th>username</th><th>discord id</th>");
        if (srvPresent) h.append("<th>minecraft</th>");
        h.append("<th>state</th><th>joined</th><th>updated</th><th></th>")
                .append("</tr></thead><tbody>");
        for (UserRecord u : all) {
            String href = "/dash/members/" + u.discordId();
            h.append("<tr>")
                    .append("<td><a href=\"").append(esc(href)).append("\">").append(esc(u.username())).append("</a></td>")
                    .append("<td><a href=\"").append(esc(href)).append("\"><code>").append(esc(u.discordId())).append("</code></a></td>");
            if (srvPresent) {
                String mc = srv.mcNameFor(u.discordId()).orElse(null);
                h.append("<td>");
                if (mc != null) h.append("<span class=mc-chip>&#9935; ").append(esc(mc)).append("</span>");
                else h.append("<span class=\"text-secondary small\">&ndash;</span>");
                h.append("</td>");
            }
            h.append("<td>").append(stateBadge(u.state().wireName())).append("</td>")
                    .append("<td class=\"text-nowrap small\"><time>").append(FMT.format(Instant.ofEpochMilli(u.joinedAt()))).append("</time></td>")
                    .append("<td class=\"text-nowrap small\"><time>").append(FMT.format(Instant.ofEpochMilli(u.updatedAt()))).append("</time></td>")
                    .append("<td><form method=post action=\"/dash/members/").append(esc(u.discordId()))
                    .append("/reonboard\" data-confirm=\"Reset onboarding for this user? They'll be sent back to the start of the flow.\" data-confirm-ok=\"Reset onboarding\" class=\"m-0\">")
                    .append("<button class=\"btn btn-sm btn-outline-secondary\">reset onboarding</button></form></td>")
                    .append("</tr>");
        }
        h.append("</tbody></table></div>");
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private static String stateBadge(String state) {
        String bg = switch (state) {
            case "approved" -> "bg-success-subtle text-success-emphasis";
            case "denied" -> "bg-danger-subtle text-danger-emphasis";
            case "awaiting_review" -> "bg-warning-subtle text-warning-emphasis";
            case "awaiting_answers" -> "bg-info-subtle text-info-emphasis";
            case "linked", "rules_pending" -> "bg-info-subtle text-info-emphasis";
            case "pending_link" -> "bg-secondary-subtle text-secondary-emphasis";
            default -> "bg-secondary-subtle text-secondary-emphasis";
        };
        String label = io.warden.onboarding.OnboardingState.labelFromWire(state);
        return "<span class=\"badge state-badge " + bg + "\">" + esc(label) + "</span>";
    }

    /**
     * "Reset onboarding": move user back to PENDING_LINK, clear their answers,
     * and re-run the delivery pipeline so they actually get a fresh DM /
     * channel post and a new link code (best-effort - the user has to still
     * be in the guild for the Discord steps to land).
     */
    /** Per-member detail page: profile, activity sparkline, audit journey, application history. */
    public void detail(Context ctx) throws Exception {
        String discordId = ctx.pathParam("discordId");
        Optional<UserRecord> userOpt = services.userDao.findByDiscordId(discordId);
        UserRecord user = userOpt.orElse(null);

        // Try to enrich from JDA cache: avatar, member roles, global display name.
        Map<String, Object> profile = profileFromJda(discordId);
        if (user != null) {
            profile.putIfAbsent("username", user.username());
        }
        String displayName = (String) profile.getOrDefault("username",
                user != null ? user.username() : discordId);

        var srv = services.discordSrv();
        boolean srvPresent = srv != null && srv.isPresent();
        String mcName = srvPresent ? srv.mcNameFor(discordId).orElse(null) : null;

        List<io.warden.data.dao.AuditDao.Entry> auditRows =
                services.auditDao.listFiltered(null, null, discordId, 200);
        List<Application> applications = services.applicationDao.listFor(discordId);

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head(esc(displayName) + " · Members · Warden", "members", ctx));

        h.append("<div class=\"d-flex flex-wrap align-items-center gap-2 mb-3\">");
        h.append("<a href=/dash/members class=\"btn btn-sm btn-outline-secondary\">")
                .append("<i class=\"bi bi-arrow-left me-1\"></i>Members</a>");
        h.append("<h1 class=\"h3 mb-0 ms-2\">").append(esc(displayName)).append("</h1>");
        if (user != null) {
            h.append(" ").append(stateBadge(user.state().wireName()));
        } else {
            h.append(" <span class=\"badge bg-secondary-subtle text-secondary-emphasis state-badge\">Unknown to Warden</span>");
        }
        h.append("<div class=\"ms-auto\">")
                .append("<form method=post action=\"/dash/members/").append(esc(discordId))
                .append("/reonboard\" data-confirm=\"Reset onboarding for this user? They'll be sent back to the start of the flow.\" data-confirm-ok=\"Reset onboarding\" class=\"m-0\">")
                .append("<button class=\"btn btn-sm btn-outline-secondary\">")
                .append("<i class=\"bi bi-arrow-clockwise me-1\"></i>Reset onboarding</button></form>")
                .append("</div>");
        h.append("</div>");

        // Profile + activity row.
        h.append("<div class=\"row g-3 mb-4\">");

        // Profile card.
        h.append("<div class=\"col-12 col-lg-4\"><div class=\"card h-100\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6 text-secondary mb-3\">Profile")
                .append(Layout.infoIcon("Pulled from Warden's user row plus the JDA cache (display name, avatar, roles). "
                        + "Minecraft name comes from DiscordSRV if installed. Empty when Warden has never seen this id."))
                .append("</h2>");
        String avatar = (String) profile.get("avatar_url");
        h.append("<div class=\"d-flex align-items-center gap-3 mb-3\">");
        if (avatar != null && !avatar.isBlank()) {
            h.append("<img src=\"").append(esc(avatar)).append("\" alt=\"\" ")
                    .append("style=\"width:64px;height:64px;border-radius:50%;object-fit:cover\">");
        } else {
            h.append("<div style=\"width:64px;height:64px;border-radius:50%;background:#5865F2;color:#fff;")
                    .append("display:flex;align-items:center;justify-content:center;font-size:1.6rem\">")
                    .append("<i class=\"bi bi-person-fill\"></i></div>");
        }
        h.append("<div><div class=\"fw-semibold\">").append(esc(displayName)).append("</div>")
                .append("<div class=\"text-secondary small\"><code>").append(esc(discordId)).append("</code></div>");
        if (mcName != null) {
            h.append("<div class=\"mt-1\"><span class=mc-chip>&#9935; ").append(esc(mcName)).append("</span></div>");
        }
        h.append("</div></div>");

        h.append("<dl class=\"row mb-0 small\">");
        if (user != null) {
            h.append(dt("First seen", FMT.format(Instant.ofEpochMilli(user.joinedAt()))));
            h.append(dt("Last update", FMT.format(Instant.ofEpochMilli(user.updatedAt()))));
            h.append(dt("State", OnboardingState.labelFromWire(user.state().wireName())));
        } else {
            h.append(dt("Status", "No Warden record for this id"));
        }
        Long latestDecisionAt = applications.stream()
                .map(Application::decidedAt)
                .filter(java.util.Objects::nonNull)
                .max(Long::compareTo).orElse(null);
        if (latestDecisionAt != null) {
            h.append(dt("Last decision", FMT.format(Instant.ofEpochMilli(latestDecisionAt))));
        }
        h.append("</dl>");

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) profile.getOrDefault("roles", List.of());
        if (!roles.isEmpty()) {
            h.append("<hr class=\"my-3\"><h3 class=\"h6 text-secondary mb-2\">Roles")
                    .append(Layout.infoIcon("Roles the member currently holds in the configured guild. Read live from "
                            + "the JDA cache so this updates whenever the bot is online; blank if the bot isn't ready "
                            + "or the user has left the guild."))
                    .append("</h3>");
            h.append("<div class=\"d-flex flex-wrap gap-1\">");
            for (String r : roles) {
                h.append("<span class=\"badge bg-secondary-subtle text-secondary-emphasis\">")
                        .append(esc(r)).append("</span>");
            }
            h.append("</div>");
        }
        h.append("</div></div></div>");

        // Activity sparkline.
        h.append("<div class=\"col-12 col-lg-8\"><div class=\"card h-100\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6 text-secondary mb-3\">Activity (last ").append(ACTIVITY_DAYS).append(" days)")
                .append(Layout.infoIcon("Two daily series for this member: Discord messages sent in the configured guild, "
                        + "and Minecraft sessions started. Both are pulled live from the event tables - no rollup lag. "
                        + "Zeroes are gap-filled so the chart doesn't skip days."))
                .append("</h2>");
        h.append("<div id=\"warden-member-activity\" style=\"min-height:240px\"></div>");
        h.append("</div></div></div>");
        h.append("</div>");

        // Onboarding journey card.
        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6 text-secondary mb-3\">Onboarding journey")
                .append(Layout.infoIcon("Every audit_log row whose target is this Discord id, newest first. Covers "
                        + "state transitions, DM/channel deliveries, role changes, mod actions, and LLM verdicts. "
                        + "Up to the 200 most recent entries."))
                .append("</h2>");
        if (auditRows.isEmpty()) {
            h.append("<p class=\"text-secondary mb-0\">No audit entries for this id yet.</p>");
        } else {
            h.append("<div class=\"timeline\">");
            for (var row : auditRows) {
                h.append("<div class=\"timeline-row\">")
                        .append("<div class=\"timeline-dot\"></div>")
                        .append("<div class=\"timeline-body\">")
                        .append("<div class=\"small text-secondary\"><time>")
                        .append(FMT.format(Instant.ofEpochMilli(row.at())))
                        .append("</time> · ").append(esc(row.actor())).append("</div>")
                        .append("<div>").append(esc(humanizeAction(row.action()))).append("</div>");
                if (row.payloadJson() != null && !row.payloadJson().isBlank() && !"{}".equals(row.payloadJson())) {
                    h.append("<details class=\"raw-payload\"><summary>payload</summary>")
                            .append("<code>").append(esc(row.payloadJson())).append("</code></details>");
                }
                h.append("</div></div>");
            }
            h.append("</div>");
        }
        h.append("</div></div>");

        // Application history.
        h.append("<div class=\"card mb-4\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6 text-secondary mb-3\">Applications")
                .append(Layout.infoIcon("Each row is one application submission. Shows the LLM's suggested verdict + "
                        + "confidence (when triage was enabled), the final decision and who decided, and any mod note "
                        + "attached to it. Useful for spotting repeat applicants or LLM/mod disagreements."))
                .append("</h2>");
        if (applications.isEmpty()) {
            h.append("<p class=\"text-secondary mb-0\">No applications submitted by this member yet.</p>");
        } else {
            h.append("<div class=\"table-responsive\"><table class=\"table table-sm align-top mb-0\">")
                    .append("<thead><tr>")
                    .append("<th>#</th><th>submitted</th><th>LLM</th><th>final</th><th>note</th>")
                    .append("</tr></thead><tbody>");
            for (Application app : applications) {
                h.append("<tr>")
                        .append("<td>").append(app.id()).append("</td>")
                        .append("<td class=\"text-nowrap small\"><time>")
                        .append(FMT.format(Instant.ofEpochMilli(app.submittedAt()))).append("</time></td>")
                        .append("<td>").append(llmCell(app)).append("</td>")
                        .append("<td>").append(finalCell(app)).append("</td>")
                        .append("<td class=\"small\">").append(esc(app.modNote() == null ? "" : app.modNote())).append("</td>")
                        .append("</tr>");
            }
            h.append("</tbody></table></div>");
        }
        h.append("</div></div>");

        h.append(TIMELINE_CSS);
        h.append("<script src=\"https://cdn.jsdelivr.net/npm/apexcharts@3.54.1/dist/apexcharts.min.js\"></script>");
        h.append("<script>").append(activityBootJs(discordId)).append("</script>");
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    /** JSON for the per-member activity sparkline. */
    public void activityApi(Context ctx) throws Exception {
        String discordId = ctx.pathParam("discordId");
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate from = today.minusDays(ACTIVITY_DAYS - 1L);
        long fromMs = from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long toMs = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        Map<String, Integer> idx = new HashMap<>(ACTIVITY_DAYS);
        ArrayNode labels = Json.MAPPER.createArrayNode();
        for (int i = 0; i < ACTIVITY_DAYS; i++) {
            String dk = DAY_FMT.format(from.plusDays(i));
            labels.add(dk);
            idx.put(dk, i);
        }
        long[] messages = new long[ACTIVITY_DAYS];
        long[] sessions = new long[ACTIVITY_DAYS];

        // Messages: SQLite has no date_trunc; format in Java by epoch ms / day buckets in app-local TZ.
        try (Connection c = services.database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT at FROM discord_messages WHERE discord_id = ? AND at >= ? AND at < ?")) {
            ps.setString(1, discordId);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String day = DAY_FMT.format(Instant.ofEpochMilli(rs.getLong(1))
                            .atZone(ZoneId.systemDefault()).toLocalDate());
                    Integer i = idx.get(day);
                    if (i != null) messages[i]++;
                }
            }
        }

        // Sessions: keyed by mc_uuid - we need to first resolve discord_id → mc_uuid via DiscordSRV.
        String mcUuid = null;
        var srv = services.discordSrv();
        if (srv != null && srv.isPresent()) {
            var maybe = srv.uuidFor(discordId);
            if (maybe.isPresent()) mcUuid = maybe.get().toString();
        }
        if (mcUuid != null) {
            try (Connection c = services.database.connection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT started_at FROM mc_sessions WHERE mc_uuid = ? AND started_at >= ? AND started_at < ?")) {
                ps.setString(1, mcUuid);
                ps.setLong(2, fromMs);
                ps.setLong(3, toMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String day = DAY_FMT.format(Instant.ofEpochMilli(rs.getLong(1))
                                .atZone(ZoneId.systemDefault()).toLocalDate());
                        Integer i = idx.get(day);
                        if (i != null) sessions[i]++;
                    }
                }
            }
        }

        ObjectNode root = Json.MAPPER.createObjectNode();
        root.set("labels", labels);
        root.put("mc_linked", mcUuid != null);
        ArrayNode series = root.putArray("series");
        ObjectNode msgs = series.addObject();
        msgs.put("name", "Discord messages");
        ArrayNode m = msgs.putArray("data");
        for (long v : messages) m.add(v);
        ObjectNode sess = series.addObject();
        sess.put("name", "Minecraft sessions");
        ArrayNode s = sess.putArray("data");
        for (long v : sessions) s.add(v);

        ctx.header("Cache-Control", "no-store");
        ctx.json(root);
    }

    /* ---------- detail page helpers ---------- */

    private Map<String, Object> profileFromJda(String discordId) {
        Map<String, Object> out = new HashMap<>();
        if (discord == null) return out;
        JDA jda = discord.jda();
        if (jda == null) return out;
        try {
            User u = jda.getUserById(discordId);
            if (u != null) {
                String name = (u.getGlobalName() != null && !u.getGlobalName().isBlank())
                        ? u.getGlobalName() : u.getName();
                out.put("username", name);
                String avatarUrl = u.getEffectiveAvatarUrl();
                if (avatarUrl != null) out.put("avatar_url", avatarUrl);
            }
            String guildId = services.config.discordGuildId();
            if (guildId != null && !guildId.isBlank()) {
                Guild g = jda.getGuildById(guildId);
                if (g != null) {
                    Member m = g.getMemberById(discordId);
                    if (m != null) {
                        List<String> names = m.getRoles().stream()
                                .sorted(java.util.Comparator.comparingInt(Role::getPosition).reversed())
                                .map(Role::getName)
                                .toList();
                        out.put("roles", names);
                    }
                }
            }
        } catch (Exception ignored) {
            // best-effort - never block the page on JDA hiccups
        }
        return out;
    }

    private static String llmCell(Application app) {
        if (app.llmDecision() == null) return "<span class=\"text-secondary small\">&ndash;</span>";
        String color = switch (app.llmDecision()) {
            case "approve"  -> "success";
            case "deny"     -> "danger";
            case "escalate" -> "warning";
            default          -> "secondary";
        };
        String chip = "<span class=\"badge bg-" + color + "-subtle text-" + color + "-emphasis\">"
                + esc(app.llmDecision()) + "</span>";
        if (app.llmConfidenceX1000() != null) {
            chip += " <span class=\"text-secondary small\">"
                    + (app.llmConfidenceX1000() / 10) + "%</span>";
        }
        return chip;
    }

    private static String finalCell(Application app) {
        if (app.finalDecision() == null) {
            return "<span class=\"badge bg-warning-subtle text-warning-emphasis\">pending</span>";
        }
        String color = "approve".equals(app.finalDecision()) ? "success"
                : "deny".equals(app.finalDecision()) ? "danger" : "secondary";
        String chip = "<span class=\"badge bg-" + color + "-subtle text-" + color + "-emphasis\">"
                + esc(app.finalDecision()) + "</span>";
        if (app.decidedBy() != null) {
            chip += " <span class=\"text-secondary small\">by " + esc(decidedByLabel(app.decidedBy())) + "</span>";
        }
        return chip;
    }

    private static String decidedByLabel(String by) {
        if (by == null || by.isBlank()) return "?";
        return switch (by) {
            case "llm"     -> "LLM";
            case "bot"     -> "bot policy";
            case "web-mod" -> "dashboard mod";
            default        -> by.matches("\\d{15,21}") ? "mod " + by.substring(0, 6) + "..." : by;
        };
    }

    private static String humanizeAction(String action) {
        if (action == null) return "(unknown action)";
        return action.replace('_', ' ');
    }

    private static String dt(String label, String value) {
        return "<dt class=\"col-5 text-secondary fw-normal\">" + esc(label) + "</dt>"
                + "<dd class=\"col-7 mb-1\">" + esc(value == null ? "" : value) + "</dd>";
    }

    private static String activityBootJs(String discordId) {
        // discordId is path-encoded into the URL; we still constrain server-side, so no XSS-via-id concerns.
        String safeId = discordId == null ? "" : discordId.replaceAll("[^0-9]", "");
        return "(async function(){"
                + "  const isDark = (document.documentElement.getAttribute('data-bs-theme')||'light')==='dark';"
                + "  const el = document.getElementById('warden-member-activity');"
                + "  try {"
                + "    const r = await fetch('/dash/api/member/" + safeId + "', {headers:{'Accept':'application/json'}});"
                + "    const d = await r.json();"
                + "    const series = d.mc_linked ? d.series : d.series.filter(s => s.name !== 'Minecraft sessions');"
                + "    const opts = {"
                + "      chart: { type:'area', height:240, toolbar:{show:false}, animations:{enabled:false}, foreColor: isDark?'#cbd2e6':'#3a4264' },"
                + "      series: series,"
                + "      xaxis: { categories: d.labels, type:'datetime', labels:{datetimeUTC:false} },"
                + "      stroke: { curve:'smooth', width:2 },"
                + "      fill: { type:'gradient', gradient:{ opacityFrom:0.45, opacityTo:0.05 } },"
                + "      dataLabels: { enabled:false },"
                + "      legend: { position:'top', horizontalAlign:'right' },"
                + "      colors: ['#5865F2','#22c55e'],"
                + "      grid: { borderColor: isDark?'#2a3158':'#e5e7eb' },"
                + "      tooltip: { theme: isDark?'dark':'light', shared:true }"
                + "    };"
                + "    new ApexCharts(el, opts).render();"
                + "  } catch (e) { el.textContent = 'Failed to load activity: ' + e; }"
                + "})();";
    }

    private static final String TIMELINE_CSS = "<style>"
            + ".timeline{position:relative;padding-left:1.4rem}"
            + ".timeline::before{content:'';position:absolute;left:.4rem;top:.4rem;bottom:.4rem;width:2px;background:var(--bs-border-color)}"
            + ".timeline-row{position:relative;padding:.45rem 0;display:flex;gap:.75rem}"
            + ".timeline-dot{position:absolute;left:-1.05rem;top:.7rem;width:.7rem;height:.7rem;border-radius:50%;background:var(--bs-primary);box-shadow:0 0 0 3px var(--bs-body-bg)}"
            + ".timeline-body{flex:1 1 auto;min-width:0}"
            + ".timeline-body details.raw-payload{margin-top:.25rem}"
            + "</style>";

    /**
     * One-click onboard every cached guild member: marks each non-bot member as
     * APPROVED, assigns the configured full role, removes the gated role.
     * Idempotent so a repeat click just no-ops on members already approved.
     */
    public void bulkOnboard(Context ctx) {
        var svc = services.bulkOnboard();
        if (svc == null) {
            ctx.redirect(Layout.flashRedirect("/dash/members", "error",
                    "Bulk-onboard is not available (DiscordService not ready)."));
            return;
        }
        if (!svc.fullRoleConfigured()) {
            ctx.redirect(Layout.flashRedirect("/dash/members", "warning",
                    "Set a full role on /dash/config first; bulk-onboard had nothing to assign."));
            return;
        }
        String actor = AuditActor.modDiscordId(ctx);
        var result = svc.run(actor == null || actor.isBlank() ? "web-mod" : actor);
        String message = "Bulk onboard finished: "
                + result.approved() + " approved, "
                + result.alreadyApproved() + " already approved, "
                + result.bots() + " bots skipped"
                + (result.failed() > 0 ? ", " + result.failed() + " failed (see server log)" : "")
                + " of " + result.total() + " total.";
        ctx.redirect(Layout.flashRedirect("/dash/members", "success", message));
    }

    public void reonboard(Context ctx) throws Exception {
        String discordId = ctx.pathParam("discordId");
        services.userDao.setState(discordId, OnboardingState.PENDING_LINK);
        services.answerDao.clearFor(discordId);
        services.audit.write("web-mod", "reonboard_reset", discordId,
                AuditActor.payload(ctx, Map.of("note", "state reset to pending_link; answers cleared")));

        OnboardingDelivery delivery = services.delivery();
        if (delivery != null) {
            OnboardingDelivery.ReplayResult r = delivery.replayFor(discordId);
            if (r != OnboardingDelivery.ReplayResult.OK) {
                services.audit.write("web-mod", "reonboard_replay_skipped", discordId,
                        AuditActor.payload(ctx, Map.of("reason", r.name())));
            }
        }
        ctx.redirect("/dash/members?state=pending_link");
    }

    private static String esc(String s) {
        return Layout.escape(s);
    }
}
