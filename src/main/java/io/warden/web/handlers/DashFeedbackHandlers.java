package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.discord.DiscordService;
import io.warden.feedback.Feedback;
import io.warden.feedback.FeedbackConfig;
import io.warden.feedback.FeedbackDao;
import io.warden.feedback.FeedbackNote;
import io.warden.feedback.FeedbackService;
import io.warden.feedback.FeedbackStatus;
import io.warden.web.auth.DashAuth;
import io.warden.web.auth.SessionCookie;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DashFeedbackHandlers {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final FeedbackService service;
    private final DiscordService discord;
    private final GuildLookup lookup;

    public DashFeedbackHandlers(FeedbackService service, DiscordService discord, GuildLookup lookup) {
        this.service = service;
        this.discord = discord;
        this.lookup = lookup;
    }

    /* ============================ List ============================ */

    public void list(Context ctx) throws Exception {
        String statusParam = ctx.queryParam("status");
        FeedbackStatus statusFilter = (statusParam == null || statusParam.isBlank() || "all".equalsIgnoreCase(statusParam))
                ? null : FeedbackStatus.fromWire(statusParam);
        FeedbackDao.Sort sort = switch (String.valueOf(ctx.queryParam("sort"))) {
            case "recent" -> FeedbackDao.Sort.RECENT;
            case "oldest" -> FeedbackDao.Sort.OLDEST;
            default -> FeedbackDao.Sort.VOTES_DESC;
        };

        List<Feedback> rows = service.dao().list(statusFilter, sort, 250);
        List<Long> ids = new ArrayList<>(rows.size());
        for (Feedback f : rows) ids.add(f.id());
        Map<Long, FeedbackDao.VoteTally> tallies = service.dao().tallyMany(ids);

        Map<FeedbackStatus, Integer> counts = new HashMap<>();
        for (FeedbackDao.StatusCount sc : service.dao().countsByStatus()) {
            counts.merge(sc.status(), sc.count(), Integer::sum);
        }

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Feedback · Warden", "feedback", ctx));
        h.append("<div class=\"d-flex align-items-center justify-content-between mb-3\">");
        h.append("<h1 class=\"h3 mb-0\">Feedback</h1>");
        h.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/feedback/settings\">Settings</a>");
        h.append("</div>");

        h.append("<div class=\"d-flex flex-wrap gap-2 align-items-center mb-3\">");
        h.append("<div class=\"me-3\">");
        h.append(filterPill("All", null, statusFilter, totalCount(counts)));
        for (FeedbackStatus s : FeedbackStatus.values()) {
            h.append(filterPill(s.label(), s, statusFilter, counts.getOrDefault(s, 0)));
        }
        h.append("</div>");
        h.append("<div class=\"ms-auto small text-secondary\">Sort:</div>");
        h.append("<a class=\"btn btn-sm ").append(sort == FeedbackDao.Sort.VOTES_DESC ? "btn-primary" : "btn-outline-secondary")
                .append("\" href=\"").append(sortHref(statusFilter, "votes")).append("\">Top voted</a>");
        h.append("<a class=\"btn btn-sm ").append(sort == FeedbackDao.Sort.RECENT ? "btn-primary" : "btn-outline-secondary")
                .append("\" href=\"").append(sortHref(statusFilter, "recent")).append("\">Recently updated</a>");
        h.append("<a class=\"btn btn-sm ").append(sort == FeedbackDao.Sort.OLDEST ? "btn-primary" : "btn-outline-secondary")
                .append("\" href=\"").append(sortHref(statusFilter, "oldest")).append("\">Oldest</a>");
        h.append("</div>");

        if (rows.isEmpty()) {
            h.append("<p class=\"text-secondary\">No feedback matches this filter.</p>");
        } else {
            h.append("<div class=\"table-responsive\"><table class=\"table table-hover align-middle\">");
            h.append("<thead><tr><th>#</th><th>Title</th><th>From</th><th>Status</th>"
                    + "<th>Score</th><th>Up</th><th>Down</th><th>Updated</th></tr></thead><tbody>");
            for (Feedback f : rows) {
                FeedbackDao.VoteTally t = tallies.getOrDefault(f.id(), new FeedbackDao.VoteTally(0, 0));
                h.append("<tr style=\"cursor:pointer\" onclick=\"window.location='/dash/feedback/")
                        .append(f.id()).append("'\">")
                        .append("<td><code>#").append(f.id()).append("</code></td>")
                        .append("<td>").append(Layout.escape(f.title())).append("</td>")
                        .append("<td>").append(Layout.escape(displayName(f))).append("</td>")
                        .append("<td>").append(statusBadge(f.status())).append("</td>")
                        .append("<td><strong>").append(t.net()).append("</strong></td>")
                        .append("<td class=\"text-success\">").append(t.up()).append("</td>")
                        .append("<td class=\"text-danger\">").append(t.down()).append("</td>")
                        .append("<td><span class=\"text-secondary small\">").append(TS.format(Instant.ofEpochMilli(f.updatedAt()))).append("</span></td>")
                        .append("</tr>");
            }
            h.append("</tbody></table></div>");
        }

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private static String sortHref(FeedbackStatus status, String sort) {
        StringBuilder s = new StringBuilder("/dash/feedback?");
        if (status != null) s.append("status=").append(status.wire()).append("&");
        s.append("sort=").append(sort);
        return s.toString();
    }

    private static int totalCount(Map<FeedbackStatus, Integer> counts) {
        int sum = 0;
        for (int n : counts.values()) sum += n;
        return sum;
    }

    private static String filterPill(String label, FeedbackStatus status, FeedbackStatus current, int count) {
        String href = "/dash/feedback" + (status == null ? "" : "?status=" + status.wire());
        boolean active = (status == null && current == null) || (status != null && status == current);
        String cls = "btn btn-sm me-1 mb-1 " + (active ? "btn-primary" : "btn-outline-secondary");
        return "<a class=\"" + cls + "\" href=\"" + href + "\">"
                + Layout.escape(label) + " <span class=badge text-bg-light ms-1>" + count + "</span></a>";
    }

    private static String statusBadge(FeedbackStatus s) {
        String cls = switch (s) {
            case OPEN -> "text-bg-primary";
            case UNDER_REVIEW -> "text-bg-info";
            case PLANNED -> "text-bg-purple";
            case IN_PROGRESS -> "text-bg-warning";
            case DONE -> "text-bg-success";
            case DECLINED -> "text-bg-secondary";
            case DUPLICATE -> "text-bg-secondary";
        };
        // text-bg-purple isn't a real Bootstrap class; fall back to a custom inline color.
        if ("text-bg-purple".equals(cls)) {
            return "<span class=\"badge\" style=\"background:#9b59b6;color:#fff\">" + Layout.escape(s.label()) + "</span>";
        }
        return "<span class=\"badge " + cls + "\">" + Layout.escape(s.label()) + "</span>";
    }

    private String displayName(Feedback f) {
        if (f.discordUsername() != null && !f.discordUsername().isBlank()) return f.discordUsername();
        return lookup.userName(f.discordId()).orElseGet(f::discordId);
    }

    /* ============================ Detail ============================ */

    public void detail(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        Feedback f = service.dao().find(id).orElse(null);
        if (f == null) {
            ctx.status(404).html("Feedback not found.");
            return;
        }
        FeedbackDao.VoteTally tally = service.dao().tally(id);
        List<FeedbackNote> notes = service.dao().notes(id);

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Feedback #" + id + " · Warden", "feedback", ctx));
        h.append("<div class=\"d-flex align-items-center mb-3\">");
        h.append("<a class=\"btn btn-sm btn-outline-secondary me-3\" href=\"/dash/feedback\">"
                + "<i class=\"bi bi-arrow-left\"></i> Back</a>");
        h.append("<h1 class=\"h4 mb-0\">#").append(id).append(" ").append(Layout.escape(f.title())).append("</h1>");
        h.append("<span class=\"ms-3\">").append(statusBadge(f.status())).append("</span>");
        h.append("</div>");

        h.append("<div class=\"row g-3 mb-3\"><div class=\"col-md-8\">");
        h.append("<div class=\"card mb-3\"><div class=\"card-body\">");
        h.append("<div class=\"d-flex justify-content-between align-items-start mb-2\">");
        h.append("<div><strong>").append(Layout.escape(displayName(f))).append("</strong></div>");
        h.append("<div class=\"text-secondary small\">").append(TS.format(Instant.ofEpochMilli(f.createdAt()))).append("</div>");
        h.append("</div>");
        h.append("<div class=\"mt-2\" style=\"white-space:pre-wrap\">")
                .append(Layout.escape(f.body())).append("</div>");
        h.append("</div></div>");

        if (f.staffResponse() != null && !f.staffResponse().isBlank()) {
            h.append("<div class=\"card mb-3 border-primary\"><div class=\"card-body\">");
            h.append("<h2 class=\"h6 text-primary\">Current public staff response</h2>");
            h.append("<div style=\"white-space:pre-wrap\">").append(Layout.escape(f.staffResponse())).append("</div>");
            h.append("</div></div>");
        }

        if (!notes.isEmpty()) {
            h.append("<h2 class=\"h6 mt-4\">History</h2>");
            for (FeedbackNote n : notes) {
                if (n.id() == notes.get(0).id() && FeedbackNote.KIND_USER.equals(n.authorKind())) {
                    continue;
                }
                String headerCls = switch (n.authorKind()) {
                    case FeedbackNote.KIND_STAFF -> "bg-primary-subtle";
                    case FeedbackNote.KIND_SYSTEM -> "bg-body-tertiary";
                    default -> "";
                };
                h.append("<div class=\"card mb-2\">");
                h.append("<div class=\"card-header py-2 ").append(headerCls).append("\">")
                        .append("<strong>").append(Layout.escape(noteAuthorLabel(n))).append("</strong>")
                        .append(" <span class=\"text-secondary small ms-2\">")
                        .append(TS.format(Instant.ofEpochMilli(n.createdAt())))
                        .append("</span></div>");
                h.append("<div class=\"card-body py-2\" style=\"white-space:pre-wrap\">")
                        .append(Layout.escape(n.body())).append("</div>");
                h.append("</div>");
            }
        }

        h.append("<form method=post action=\"/dash/feedback/").append(id).append("/response\" class=\"mt-3\">");
        h.append("<label class=form-label>Public staff response")
                .append(Layout.infoIcon("Shown in the Discord embed and DM'd to the reporter (if DM-on-response is enabled). Replaces any earlier response."))
                .append("</label>");
        h.append("<textarea class=\"form-control\" name=response rows=4 placeholder=\"Your public response\">")
                .append(Layout.escape(f.staffResponse() == null ? "" : f.staffResponse()))
                .append("</textarea>");
        h.append("<div class=\"mt-2\"><button class=\"btn btn-primary\">Save response</button></div>");
        h.append("</form>");
        h.append("</div>");

        h.append("<div class=\"col-md-4\">");
        h.append("<div class=\"card\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6\">Votes</h2>");
        h.append("<div class=\"d-flex gap-3 align-items-baseline\">");
        h.append("<div class=\"display-6 mb-0\">").append(tally.net()).append("</div>");
        h.append("<div><span class=\"text-success\"><i class=\"bi bi-hand-thumbs-up\"></i> ")
                .append(tally.up()).append("</span> &nbsp; ")
                .append("<span class=\"text-danger\"><i class=\"bi bi-hand-thumbs-down\"></i> ")
                .append(tally.down()).append("</span></div>");
        h.append("</div></div></div>");

        h.append("<div class=\"card mt-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6\">Details</h2>");
        h.append("<dl class=\"row mb-0 small\">");
        h.append("<dt class=col-5>Reporter</dt><dd class=col-7>").append(Layout.escape(displayName(f))).append("</dd>");
        h.append("<dt class=col-5>Opened</dt><dd class=col-7>").append(TS.format(Instant.ofEpochMilli(f.createdAt()))).append("</dd>");
        h.append("<dt class=col-5>Updated</dt><dd class=col-7>").append(TS.format(Instant.ofEpochMilli(f.updatedAt()))).append("</dd>");
        if (f.channelId() != null && !f.channelId().isBlank() && f.messageId() != null && !f.messageId().isBlank()) {
            String url = "https://discord.com/channels/" + Layout.escape(lookup.guildId()) + "/"
                    + Layout.escape(f.channelId()) + "/" + Layout.escape(f.messageId());
            h.append("<dt class=col-5>Embed</dt><dd class=col-7><a target=_blank rel=noopener href=\"")
                    .append(url).append("\">Open in Discord</a></dd>");
        }
        h.append("</dl></div></div>");

        h.append("<div class=\"card mt-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6\">Status</h2>");
        h.append("<form method=post action=\"/dash/feedback/").append(id).append("/status\" class=\"d-flex gap-2 mb-2\">");
        h.append("<select class=form-select form-select-sm name=status>");
        for (FeedbackStatus s : FeedbackStatus.values()) {
            h.append("<option value=\"").append(s.wire()).append("\"")
                    .append(s == f.status() ? " selected" : "").append(">")
                    .append(Layout.escape(s.label())).append("</option>");
        }
        h.append("</select>");
        h.append("<button class=\"btn btn-sm btn-outline-primary\">Set</button>");
        h.append("</form>");
        h.append("<form method=post action=\"/dash/feedback/").append(id)
                .append("/delete\" data-confirm=\"Delete this feedback and remove the embed?\" data-confirm-kind=\"danger\">");
        h.append("<button class=\"btn btn-sm btn-outline-danger w-100\">Delete</button>");
        h.append("</form>");
        h.append("</div></div>");
        h.append("</div></div>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private static String noteAuthorLabel(FeedbackNote n) {
        String name = n.authorName() == null || n.authorName().isBlank() ? "(unknown)" : n.authorName();
        return switch (n.authorKind()) {
            case FeedbackNote.KIND_STAFF -> name + " (staff)";
            case FeedbackNote.KIND_SYSTEM -> "System";
            default -> name;
        };
    }

    public void changeStatus(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        FeedbackStatus next = FeedbackStatus.fromWire(ctx.formParam("status"));
        var sess = DashAuth.sessionOf(ctx);
        String staffId = sess.map(SessionCookie.Session::discordId).orElse("");
        String staffName = staffName(sess);
        service.changeStatus(discord != null ? discord.jda() : null, id, next, staffId, staffName);
        ctx.redirect("/dash/feedback/" + id);
    }

    public void response(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String response = ctx.formParam("response");
        var sess = DashAuth.sessionOf(ctx);
        String staffId = sess.map(SessionCookie.Session::discordId).orElse("");
        String staffName = staffName(sess);
        service.setStaffResponse(discord != null ? discord.jda() : null, id,
                response == null ? "" : response, staffId, staffName);
        ctx.redirect("/dash/feedback/" + id);
    }

    public void delete(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        var sess = DashAuth.sessionOf(ctx);
        service.delete(discord != null ? discord.jda() : null, id,
                sess.map(SessionCookie.Session::discordId).orElse(""),
                staffName(sess));
        ctx.redirect("/dash/feedback");
    }

    /* ============================ Settings ============================ */

    public void settings(Context ctx) throws Exception {
        FeedbackConfig cfg = service.config().get();
        var channelOpts = lookup.textChannels();

        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head("Feedback settings · Warden", "feedback", ctx));
        h.append("<div class=\"d-flex align-items-center justify-content-between mb-3\">");
        h.append("<h1 class=\"h3 mb-0\">Feedback settings</h1>");
        h.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/feedback\">Back to list</a>");
        h.append("</div>");

        h.append("<form method=post action=\"/dash/feedback/settings\" class=\"row g-3\" style=\"max-width:760px\">");
        h.append("<div class=col-12><label class=form-label>Feedback channel")
                .append(Layout.infoIcon("Where feedback embeds get posted and where members vote."))
                .append("</label>")
                .append(GuildLookup.selectInline("channel_id",
                        GuildLookup.withDefaults(channelOpts, cfg.channelId()),
                        cfg.channelId(), "form-select", "(none)"))
                .append("</div>");

        h.append("<div class=col-md-6><div class=form-check>")
                .append("<input class=form-check-input type=checkbox name=open_via_command value=1 id=open_via_command")
                .append(cfg.openViaCommand() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=open_via_command>Allow members to submit with /feedback</label>")
                .append("</div></div>");
        h.append("<div class=col-md-6><div class=form-check>")
                .append("<input class=form-check-input type=checkbox name=unique value=1 id=unique")
                .append(cfg.requireUniquePerUser() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=unique>One open feedback per user</label>")
                .append("</div></div>");
        h.append("<div class=col-md-6><div class=form-check>")
                .append("<input class=form-check-input type=checkbox name=dm_status value=1 id=dm_status")
                .append(cfg.dmReporterOnStatus() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=dm_status>DM reporter on status change</label>")
                .append("</div></div>");
        h.append("<div class=col-md-6><div class=form-check>")
                .append("<input class=form-check-input type=checkbox name=dm_response value=1 id=dm_response")
                .append(cfg.dmReporterOnResponse() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=dm_response>DM reporter when staff posts a response</label>")
                .append("</div></div>");
        h.append("<div class=col-md-6><div class=form-check>")
                .append("<input class=form-check-input type=checkbox name=locked value=1 id=locked")
                .append(cfg.lockedWhenResolved() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=locked>Lock voting once resolved (done / declined / duplicate)</label>")
                .append("</div></div>");

        h.append("<div class=col-12><button class=\"btn btn-primary\">Save</button></div>");
        h.append("</form>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void settingsSave(Context ctx) throws Exception {
        FeedbackConfig cfg = new FeedbackConfig(
                ctx.formParam("channel_id"),
                "1".equals(ctx.formParam("open_via_command")),
                "1".equals(ctx.formParam("dm_status")),
                "1".equals(ctx.formParam("dm_response")),
                "1".equals(ctx.formParam("unique")),
                "1".equals(ctx.formParam("locked")));
        service.config().save(cfg);
        ctx.redirect("/dash/feedback/settings");
    }

    private static String staffName(java.util.Optional<SessionCookie.Session> sess) {
        return sess.map(s -> {
            String n = s.displayName();
            if (n == null || n.isBlank()) n = s.username();
            return n == null ? "Staff" : n;
        }).orElse("Staff");
    }
}
