package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.grim.GrimBridge;
import io.warden.grim.GrimViolationDao;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * /dash/violations - lists recent Grim violations read directly from
 * Grim's own {@code violations.sqlite} via {@link io.warden.grim.GrimBridge}.
 * When Grim isn't installed the page
 * renders an install prompt linking back to the GrimAC GitHub repo
 * instead of an empty table.
 */
public final class DashGrimHandlers {

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final GrimBridge grim;

    public DashGrimHandlers(GrimBridge grim) {
        this.grim = grim;
    }

    public void page(Context ctx) {
        int limit;
        try {
            limit = Math.min(1000, Math.max(25, Integer.parseInt(
                    ctx.queryParamAsClass("limit", String.class).getOrDefault("200"))));
        } catch (NumberFormatException e) {
            limit = 200;
        }

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Violations · Warden", "violations", ctx));
        h.append("<h1 class=\"h3 mb-3\">Violations</h1>");

        if (!grim.isPresent()) {
            h.append(renderMissingPanel());
            h.append(Layout.foot());
            ctx.html(h.toString());
            return;
        }

        if (grim.status() == GrimBridge.Status.ERROR) {
            h.append("<div class=\"alert alert-warning\" role=alert>")
                    .append("<strong>Grim is installed but its database can't be read:</strong> ")
                    .append(esc(grim.errorMessage()))
                    .append("</div>");
        } else if (grim.status() == GrimBridge.Status.ACTIVE) {
            h.append("<p class=\"text-secondary mb-3\">")
                    .append("Reading directly from Grim's <code>violations.sqlite</code>. ")
                    .append("Each row is one violation Grim flagged on a player.")
                    .append("</p>");
        } else {
            h.append("<div class=\"alert alert-info\" role=alert>")
                    .append("Grim is installed but Warden hasn't opened its database yet. ")
                    .append("This usually clears on its own once Grim finishes loading - reload the page in a moment.")
                    .append("</div>");
        }

        List<GrimViolationDao.Violation> rows = grim.recentViolations(limit);
        long total = grim.totalViolationCount();
        List<GrimViolationDao.CheckCount> top = grim.topChecks(8);

        h.append("<div class=\"row g-3 mb-4\">");
        h.append(statCard("Total recorded", String.valueOf(total)));
        h.append(statCard("Showing", String.valueOf(rows.size())));
        h.append(statCard("Distinct checks", String.valueOf(top.size())));
        h.append("</div>");

        if (!top.isEmpty()) {
            h.append("<div class=\"mb-4\"><div class=\"small text-secondary mb-2\">Top checks</div>");
            h.append("<div class=\"d-flex flex-wrap gap-2\">");
            for (var c : top) {
                h.append("<span class=\"badge text-bg-secondary\">")
                        .append(esc(c.checkName())).append(" <span class=opacity-75>(")
                        .append(c.count()).append(")</span></span>");
            }
            h.append("</div></div>");
        }

        h.append("<form method=get class=\"row g-2 mb-3 align-items-end\">")
                .append("<div class=\"col-sm-6 col-md-2\"><label class=\"form-label small mb-1\">limit</label>")
                .append("<input class=\"form-control form-control-sm\" name=limit type=number min=25 max=1000 value=\"")
                .append(limit).append("\"></div>")
                .append("<div class=\"col-sm-12 col-md-auto\">")
                .append("<button class=\"btn btn-primary btn-sm\" type=submit>refresh</button>")
                .append("</div></form>");

        h.append("<div class=\"table-responsive\"><table class=\"table table-hover table-sm align-top\">")
                .append("<thead><tr>")
                .append("<th>when</th><th>player</th><th>check</th><th class=\"text-end\">vl</th><th>verbose</th>")
                .append("</tr></thead><tbody>");
        if (rows.isEmpty()) {
            h.append("<tr><td colspan=5 class=\"text-secondary fst-italic py-4\">")
                    .append("No violations recorded yet. They'll appear here as Grim flags players.")
                    .append("</td></tr>");
        } else {
            for (var r : rows) {
                String name = resolveName(r.uuid());
                h.append("<tr>")
                        .append("<td class=\"text-nowrap small\"><time>")
                        .append(FMT.format(Instant.ofEpochMilli(r.ts())))
                        .append("</time></td>")
                        .append("<td>").append(esc(name == null ? shortUuid(r.uuid()) : name))
                        .append(name == null ? "" :
                                " <span class=\"small text-secondary\">" + esc(shortUuid(r.uuid())) + "</span>")
                        .append("</td>")
                        .append("<td><code>").append(esc(r.checkName())).append("</code></td>")
                        .append("<td class=\"text-end font-monospace\">").append(r.vl()).append("</td>")
                        .append("<td><span class=\"small text-secondary\">")
                        .append(esc(r.verbose()))
                        .append("</span></td>")
                        .append("</tr>");
            }
        }
        h.append("</tbody></table></div>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private static String renderMissingPanel() {
        return "<div class=\"card border-0 shadow-sm\" style=\"max-width:640px\">"
                + "<div class=\"card-body p-4\">"
                + "<div class=\"d-flex align-items-center mb-3\">"
                + "<div class=\"rounded-circle d-flex align-items-center justify-content-center me-3\" "
                + "style=\"width:48px;height:48px;background:rgba(13,110,253,.12);color:var(--bs-primary);font-size:1.4rem\">"
                + "<i class=\"bi bi-shield-exclamation\"></i></div>"
                + "<div><h2 class=\"h5 mb-0\">GrimAC isn't installed</h2>"
                + "<div class=\"text-secondary small\">Install Grim to start seeing violations here.</div>"
                + "</div></div>"
                + "<p class=\"mb-3\">"
                + "Warden integrates with <a href=\"" + GrimBridge.DOWNLOAD_URL
                + "\" target=_blank rel=\"noopener noreferrer\">Grim Anticheat</a> as a soft "
                + "dependency. When Grim is on your server, every flagged violation is recorded "
                + "and shown on this page; otherwise this tab stays quiet."
                + "</p>"
                + "<div class=\"d-flex flex-wrap gap-2\">"
                + "<a class=\"btn btn-primary\" href=\"" + GrimBridge.DOWNLOAD_URL
                + "\" target=_blank rel=\"noopener noreferrer\">"
                + "<i class=\"bi bi-github me-1\"></i>Download Grim</a>"
                + "<a class=\"btn btn-outline-secondary\" href=\"" + GrimBridge.API_URL
                + "\" target=_blank rel=\"noopener noreferrer\">"
                + "<i class=\"bi bi-code-slash me-1\"></i>GrimAPI docs</a>"
                + "</div>"
                + "<hr class=\"my-4\">"
                + "<div class=\"small text-secondary\">"
                + "After installing Grim, restart your server. Warden picks it up automatically "
                + "via the <code>softdepend</code> on <code>GrimAC</code> in <code>plugin.yml</code>."
                + "</div>"
                + "</div></div>";
    }

    private static String statCard(String label, String value) {
        return "<div class=\"col-sm-6 col-md-3\"><div class=\"card border-0 shadow-sm h-100\">"
                + "<div class=\"card-body py-3\">"
                + "<div class=\"small text-secondary\">" + esc(label) + "</div>"
                + "<div class=\"h4 mb-0\">" + esc(value) + "</div>"
                + "</div></div></div>";
    }

    private static String resolveName(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        try {
            var op = org.bukkit.Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid));
            String n = op.getName();
            return (n == null || n.isBlank()) ? null : n;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String shortUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return "";
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    private static String esc(String s) {
        return Layout.escape(s);
    }
}
