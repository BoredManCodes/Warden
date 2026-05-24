package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.web.auth.PageAccess;
import io.warden.web.auth.PageAccessDao;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /dash/permissions - owner / config-admin surface for picking which Discord
 * roles unlock each /dash/* page. An empty role list means "fall back to the
 * legacy mod role" (the gate that's been in place since the dashboard
 * shipped) so existing installs keep working without any action.
 */
public final class DashPermissionsHandlers {

    private final PageAccessDao dao;
    private final GuildLookup lookup;

    public DashPermissionsHandlers(PageAccessDao dao, GuildLookup lookup) {
        this.dao = dao;
        this.lookup = lookup;
    }

    public void page(Context ctx) throws Exception {
        Map<String, List<String>> current = dao.loadAll();
        var roleOpts = lookup.roles();

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Page permissions · Warden", "config", ctx));
        h.append("<h1 class=\"h3 mb-2\">Dashboard page permissions</h1>");
        h.append("<p class=\"text-secondary\">Pick which Discord roles can see each dashboard page. "
                + "Leave a page empty to fall back to the mod role (the default). "
                + "Owners and config admins can always reach every page.</p>");

        h.append("<form method=post action=\"/dash/permissions\">");
        h.append("<div class=\"table-responsive\"><table class=\"table align-middle\">");
        h.append("<thead><tr><th style=\"width:14rem\">Page</th><th>Roles allowed</th></tr></thead><tbody>");
        for (String key : PageAccess.KEYS) {
            String label = PageAccess.LABELS.getOrDefault(key, key);
            List<String> selected = current.getOrDefault(key, List.of());
            h.append("<tr><td><strong>").append(Layout.escape(label)).append("</strong>")
                    .append("<div class=\"text-secondary small\"><code>").append(Layout.escape(key)).append("</code></div></td>");
            h.append("<td>")
                    .append(GuildLookup.multiCheckField("roles__" + key, "",
                            roleOpts, selected, null))
                    .append("</td></tr>");
        }
        h.append("</tbody></table></div>");
        h.append("<button class=\"btn btn-primary\">Save permissions</button>");
        h.append("</form>");
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void save(Context ctx) throws Exception {
        for (String key : PageAccess.KEYS) {
            List<String> picked = ctx.formParams("roles__" + key);
            List<String> cleaned = new ArrayList<>();
            for (String r : picked) {
                if (r != null && !r.isBlank()) cleaned.add(r.trim());
            }
            dao.save(key, cleaned);
        }
        ctx.redirect("/dash/permissions");
    }
}
