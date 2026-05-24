package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.data.dao.InviteDao;
import io.warden.web.auth.AuditActor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * /dash/invites: list current and historical guild invites and let mods attach
 * a human label (Reddit, TikTok, ...). The label is the only field the mod can
 * edit; everything else is mirrored from Discord by {@link io.warden.analytics.InviteTracker}.
 */
public final class DashInvitesHandlers {

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final Services services;
    private final GuildLookup lookup;

    public DashInvitesHandlers(Services services, GuildLookup lookup) {
        this.services = services;
        this.lookup = lookup;
    }

    public void list(Context ctx) throws Exception {
        List<InviteDao.Invite> all = services.inviteDao.listAll();

        // 30-day joins-per-code for the right-hand chip on each row.
        long now = System.currentTimeMillis();
        long thirtyDaysAgo = now - TimeUnit.DAYS.toMillis(30);
        Map<String, Long> joins30d = new HashMap<>();
        for (var r : services.inviteDao.joinsByInvite(thirtyDaysAgo, now)) {
            joins30d.put(r.code(), r.count());
        }

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Invites · Warden", "invites", ctx));
        h.append("<h1 class=\"h3 mb-2\">Invites</h1>");
        h.append("<p class=\"text-secondary mb-4\">Live snapshot of the configured guild's invites. ")
                .append("Add a label (Reddit, TikTok, X, friend, ...) so the joins-by-source chart on ")
                .append("the stats page groups them sensibly. The bot needs <code>MANAGE_GUILD</code> ")
                .append("to read this list, and the <code>GUILD_INVITES</code> intent.</p>");

        if (all.isEmpty()) {
            h.append("<div class=\"alert alert-info\">No invites tracked yet. They will appear here ")
                    .append("the next time the bot connects to a guild with active invites.</div>");
            h.append(Layout.foot());
            ctx.html(h.toString());
            return;
        }

        h.append("<div class=\"table-responsive\"><table class=\"table table-hover table-sm align-middle\">");
        h.append("<thead><tr>")
                .append("<th>code</th>")
                .append("<th>label").append(Layout.infoIcon("Human label you set. Used to group invites in analytics, "
                        + "so 'Reddit', 'TikTok', 'YouTube' will be aggregated even across multiple invite codes."))
                .append("</th>")
                .append("<th class=\"text-end\">uses</th>")
                .append("<th class=\"text-end\">joins (30d)</th>")
                .append("<th>channel</th>")
                .append("<th>created</th>")
                .append("<th>status</th>")
                .append("</tr></thead><tbody>");

        for (InviteDao.Invite inv : all) {
            String status = inv.deletedAt() != null ? "deleted"
                    : (inv.expiresAt() != null && inv.expiresAt() < now) ? "expired"
                    : (inv.maxUses() > 0 && inv.uses() >= inv.maxUses()) ? "exhausted"
                    : "active";
            String statusBadge = switch (status) {
                case "active"    -> "bg-success-subtle text-success-emphasis";
                case "expired"   -> "bg-secondary-subtle text-secondary-emphasis";
                case "exhausted" -> "bg-warning-subtle text-warning-emphasis";
                default          -> "bg-danger-subtle text-danger-emphasis";
            };

            h.append("<tr>");
            h.append("<td><code>").append(esc(inv.code())).append("</code></td>");
            h.append("<td>")
                    .append("<form method=post action=\"/dash/invites/").append(esc(inv.code()))
                    .append("/label\" class=\"d-flex gap-2 m-0\" style=\"max-width:280px\">")
                    .append("<input class=\"form-control form-control-sm\" name=\"label\" ")
                    .append("placeholder=\"Reddit, TikTok, ...\" value=\"").append(esc(inv.label())).append("\">")
                    .append("<button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">save</button>")
                    .append("</form>")
                    .append("</td>");
            h.append("<td class=\"text-end\">").append(inv.uses());
            if (inv.maxUses() > 0) {
                h.append(" <span class=\"text-secondary small\">/ ").append(inv.maxUses()).append("</span>");
            }
            h.append("</td>");
            h.append("<td class=\"text-end fw-semibold\">")
                    .append(joins30d.getOrDefault(inv.code(), 0L))
                    .append("</td>");
            h.append("<td>")
                    .append(inv.channelId() == null ? "<span class=\"text-secondary small\">&ndash;</span>"
                            : esc(lookup.channelName(inv.channelId()).orElseGet(inv::channelId)))
                    .append("</td>");
            h.append("<td class=\"text-nowrap small\"><time>")
                    .append(FMT.format(Instant.ofEpochMilli(inv.createdAt())))
                    .append("</time></td>");
            h.append("<td><span class=\"badge ").append(statusBadge).append("\">").append(status).append("</span></td>");
            h.append("</tr>");
        }
        h.append("</tbody></table></div>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void saveLabel(Context ctx) throws Exception {
        String code = ctx.pathParam("code");
        String label = ctx.formParam("label");
        services.inviteDao.setLabel(code, label);
        services.audit.write("web-mod", "invite_label_set", null,
                AuditActor.payload(ctx, Map.of("code", code, "label", label == null ? "" : label)));
        ctx.redirect("/dash/invites");
    }

    private static String esc(String s) {
        return Layout.escape(s);
    }
}
