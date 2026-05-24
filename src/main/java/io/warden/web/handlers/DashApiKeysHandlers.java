package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.api.ApiKey;
import io.warden.api.ApiKeyService;
import io.warden.api.ApiScope;
import io.warden.web.auth.AuditActor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /dash/api-keys - mint, list and revoke bearer tokens that authorise calls
 * to /api/v1. Plaintext secrets are only shown once: immediately after
 * creation, the form re-renders with a one-time card that includes the new
 * key. Refreshing the page or navigating away makes the secret unrecoverable,
 * so admins must copy it before leaving.
 */
public final class DashApiKeysHandlers {

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final Services services;

    public DashApiKeysHandlers(Services services) {
        this.services = services;
    }

    public void page(Context ctx) throws Exception {
        renderPage(ctx, null, null, null);
    }

    public void create(Context ctx) throws Exception {
        String label = trim(ctx.formParam("label"));
        List<String> selectedScopes = new ArrayList<>();
        for (ApiScope s : ApiScope.values()) {
            if ("on".equalsIgnoreCase(ctx.formParam("scope__" + s.key()))
                    || "1".equals(ctx.formParam("scope__" + s.key()))) {
                selectedScopes.add(s.key());
            }
        }
        if (selectedScopes.isEmpty()) {
            renderPage(ctx, null,
                    "Pick at least one scope before minting a key. Keys with no scopes "
                            + "can authenticate but cannot reach any /api/v1 endpoint.", label);
            return;
        }
        String createdBy = AuditActor.modDiscordId(ctx);
        ApiKeyService.Created created = services.apiKeys.create(
                label == null ? "" : label, selectedScopes, createdBy);
        renderPage(ctx, created, null, null);
    }

    public void revoke(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String actor = AuditActor.modDiscordId(ctx);
        services.apiKeys.revoke(id, actor);
        ctx.redirect(Layout.flashRedirect("/dash/api-keys", "success", "API key revoked."));
    }

    public void delete(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String actor = AuditActor.modDiscordId(ctx);
        services.apiKeys.delete(id, actor);
        ctx.redirect(Layout.flashRedirect("/dash/api-keys", "success", "API key deleted."));
    }

    private void renderPage(Context ctx, ApiKeyService.Created justCreated,
                            String createError, String preservedLabel) throws Exception {
        List<ApiKey> keys = services.apiKeys.list();

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("API keys · Warden", "api-keys", ctx));

        h.append("<div class=\"d-flex flex-wrap align-items-center justify-content-between mb-3 gap-2\">");
        h.append("<div>");
        h.append("<h1 class=\"h3 mb-1\">API keys</h1>");
        h.append("<p class=\"text-secondary mb-0\">Bearer tokens for the <code>/api/v1</code> JSON surface. "
                + "Each key carries a fixed list of scopes; revoking a key invalidates it immediately.</p>");
        h.append("</div>");
        h.append("<div class=\"d-flex gap-2\">");
        h.append("<a class=\"btn btn-outline-primary\" href=\"/api/docs\" target=\"_blank\" rel=\"noopener\">"
                + "<i class=\"bi bi-book me-1\"></i>Swagger UI</a>");
        h.append("<a class=\"btn btn-outline-secondary\" href=\"/api/v1/openapi.json\" target=\"_blank\" rel=\"noopener\">"
                + "<i class=\"bi bi-filetype-json me-1\"></i>openapi.json</a>");
        h.append("</div>");
        h.append("</div>");

        if (justCreated != null) {
            h.append(renderJustCreated(justCreated));
        }

        h.append(renderCreateForm(createError, preservedLabel));
        h.append(renderKeyTable(keys));
        h.append(renderUsageHelp());

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private String renderJustCreated(ApiKeyService.Created created) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"card border-success shadow-sm mb-4\">");
        s.append("<div class=\"card-header bg-success-subtle text-success-emphasis\">");
        s.append("<i class=\"bi bi-check-circle me-1\"></i> ")
                .append("<strong>Key created.</strong> Copy the secret below now: it will not be shown again.");
        s.append("</div>");
        s.append("<div class=\"card-body\">");
        s.append("<label class=\"form-label small text-secondary mb-1\">Bearer token</label>");
        s.append("<div class=\"d-flex gap-2 align-items-stretch\">");
        s.append("<input id=\"warden-new-key\" type=\"text\" readonly class=\"form-control font-monospace\" value=\"")
                .append(Layout.escape(created.plaintext())).append("\" onclick=\"this.select();\">");
        s.append("<button type=\"button\" class=\"btn btn-outline-success\""
                + " onclick=\"navigator.clipboard.writeText(document.getElementById('warden-new-key').value);"
                + "WardenToast&&WardenToast.show({kind:'success',message:'Copied to clipboard.'});\">"
                + "<i class=\"bi bi-clipboard\"></i> Copy</button>");
        s.append("</div>");
        s.append("<div class=\"small text-secondary mt-2\">Prefix <code>")
                .append(Layout.escape(created.key().prefix()))
                .append("</code> · ")
                .append(created.key().scopes().size()).append(" scope(s)</div>");
        s.append("</div></div>");
        return s.toString();
    }

    private String renderCreateForm(String error, String preservedLabel) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"card shadow-sm mb-4\">");
        s.append("<div class=\"card-header\"><strong>Mint a new key</strong></div>");
        s.append("<div class=\"card-body\">");
        if (error != null) {
            s.append("<div class=\"alert alert-warning\">").append(Layout.escape(error)).append("</div>");
        }
        s.append("<form method=\"post\" action=\"/dash/api-keys/new\">");

        s.append("<div class=\"mb-3\">");
        s.append("<label class=\"form-label\" for=\"api-key-label\">Label</label>");
        s.append("<input type=\"text\" class=\"form-control\" id=\"api-key-label\" name=\"label\""
                + " maxlength=\"80\" placeholder=\"e.g. moderation-bot, analytics-export\" value=\"")
                .append(Layout.escape(preservedLabel == null ? "" : preservedLabel)).append("\">");
        s.append("<div class=\"form-text\">Just for your reference. Shown in the table below.</div>");
        s.append("</div>");

        s.append("<label class=\"form-label\">Scopes</label>");
        s.append("<div class=\"form-text mb-2\">Pick everything this key needs. Less is safer.</div>");

        Map<String, List<ApiScope>> grouped = ApiScope.grouped();
        for (var entry : grouped.entrySet()) {
            String module = entry.getKey();
            s.append("<div class=\"mb-3\">");
            s.append("<div class=\"small text-secondary text-uppercase mb-1\">").append(Layout.escape(module)).append("</div>");
            s.append("<div class=\"d-flex flex-wrap gap-2\">");
            for (ApiScope sc : entry.getValue()) {
                String id = "scope-" + sc.key().replace(':', '-');
                s.append("<div class=\"form-check\" style=\"min-width:18rem\">");
                s.append("<input class=\"form-check-input\" type=\"checkbox\" id=\"").append(id).append("\""
                        + " name=\"scope__").append(sc.key()).append("\" value=\"on\">");
                s.append("<label class=\"form-check-label\" for=\"").append(id).append("\">");
                s.append("<code>").append(Layout.escape(sc.key())).append("</code> ");
                s.append("<span class=\"text-secondary small\">").append(Layout.escape(sc.label())).append("</span>");
                s.append("</label>");
                s.append("</div>");
            }
            s.append("</div></div>");
        }

        s.append("<button class=\"btn btn-primary\" type=\"submit\">"
                + "<i class=\"bi bi-plus-circle me-1\"></i>Create key</button>");
        s.append("</form>");
        s.append("</div></div>");
        return s.toString();
    }

    private String renderKeyTable(List<ApiKey> keys) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"card shadow-sm mb-4\">");
        s.append("<div class=\"card-header\"><strong>Existing keys</strong></div>");
        if (keys.isEmpty()) {
            s.append("<div class=\"card-body text-secondary\">No API keys yet. Mint one above.</div>");
        } else {
            s.append("<div class=\"table-responsive\"><table class=\"table table-hover align-middle mb-0\">");
            s.append("<thead><tr>");
            s.append("<th>Label</th><th>Prefix</th><th>Scopes</th><th>Created</th>"
                    + "<th>Last used</th><th>Status</th><th class=\"text-end\">Actions</th>");
            s.append("</tr></thead><tbody>");
            for (ApiKey k : keys) {
                s.append("<tr>");
                String label = k.label() == null || k.label().isBlank() ? "(unlabelled)" : k.label();
                s.append("<td>").append(Layout.escape(label)).append("</td>");
                s.append("<td><code>").append(Layout.escape(k.prefix())).append("…</code></td>");
                s.append("<td><div class=\"d-flex flex-wrap gap-1\">");
                for (String sc : k.scopes()) {
                    s.append("<span class=\"badge text-bg-secondary\">").append(Layout.escape(sc)).append("</span>");
                }
                s.append("</div></td>");
                s.append("<td class=\"small\">").append(fmt(k.createdAt())).append("</td>");
                s.append("<td class=\"small\">").append(k.lastUsedAt() == null ? "<span class=\"text-secondary\">never</span>" : fmt(k.lastUsedAt())).append("</td>");
                if (k.isRevoked()) {
                    s.append("<td><span class=\"badge text-bg-danger\">Revoked</span> "
                            + "<span class=\"small text-secondary d-block\">")
                            .append(fmt(k.revokedAt())).append("</span></td>");
                } else {
                    s.append("<td><span class=\"badge text-bg-success\">Active</span></td>");
                }
                s.append("<td class=\"text-end\">");
                if (!k.isRevoked()) {
                    s.append("<form method=\"post\" action=\"/dash/api-keys/").append(k.id())
                            .append("/revoke\" class=\"d-inline\""
                                    + " data-confirm=\"Revoke this key? Any client using it will start getting 401s immediately.\""
                                    + " data-confirm-kind=\"danger\" data-confirm-ok=\"Revoke\">");
                    s.append("<button class=\"btn btn-sm btn-outline-warning\" type=\"submit\">"
                            + "<i class=\"bi bi-shield-slash\"></i> Revoke</button>");
                    s.append("</form> ");
                }
                s.append("<form method=\"post\" action=\"/dash/api-keys/").append(k.id())
                        .append("/delete\" class=\"d-inline\""
                                + " data-confirm=\"Delete this key entirely? This removes its audit-friendly row.\""
                                + " data-confirm-kind=\"danger\" data-confirm-ok=\"Delete\">");
                s.append("<button class=\"btn btn-sm btn-outline-danger\" type=\"submit\">"
                        + "<i class=\"bi bi-trash\"></i> Delete</button>");
                s.append("</form>");
                s.append("</td>");
                s.append("</tr>");
            }
            s.append("</tbody></table></div>");
        }
        s.append("</div>");
        return s.toString();
    }

    private String renderUsageHelp() {
        String publicUrl = Layout.escape(services.config.webPublicUrl());
        return "<div class=\"card shadow-sm\">"
                + "<div class=\"card-header\"><strong>Calling the API</strong></div>"
                + "<div class=\"card-body\">"
                + "<p class=\"mb-2\">Pass the token as a bearer header:</p>"
                + "<pre class=\"bg-body-tertiary p-3 rounded mb-3\"><code>"
                + "curl -H \"Authorization: Bearer WRDN-xxxx.xxxx\" \\\n"
                + "     \"" + publicUrl + "/api/v1/members?limit=25\"\n"
                + "</code></pre>"
                + "<p class=\"mb-2\">Full reference + a try-it-out console live at "
                + "<a href=\"/api/docs\" target=\"_blank\" rel=\"noopener\"><strong>/api/docs</strong></a> "
                + "(Swagger UI, backed by <code>/api/v1/openapi.json</code>). "
                + "Click the <em>Authorize</em> button there, paste your token, and every endpoint "
                + "becomes callable from the page.</p>"
                + "<p class=\"mb-0 text-secondary small\">"
                + "Tokens start with <code>WRDN-</code> and split on a dot. "
                + "The dashboard only ever sees the prefix half; the secret half is hashed at rest."
                + "</p>"
                + "</div></div>";
    }

    private static String fmt(long ms) {
        return FMT.format(Instant.ofEpochMilli(ms));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
