package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.WardenPlugin;
import io.warden.debug.DebugReport;
import io.warden.debug.DebugService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class DashDebugHandlers {

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final Services     services;
    private final DebugService debugService;

    public DashDebugHandlers(WardenPlugin plugin, Services services) {
        this.services     = services;
        this.debugService = new DebugService(plugin, services, services.debugReportDao);
    }

    // ── GET /dash/debug ───────────────────────────────────────────────────────

    public void list(Context ctx) throws Exception {
        List<DebugReport> reports = services.debugReportDao.listAll();

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Debug reports · Warden", "debug", ctx));
        h.append("<div class=\"d-flex align-items-center gap-2 mb-3\">")
         .append("<h1 class=\"h3 mb-0\">Debug reports</h1>")
         .append("</div>");

        // Generate form
        h.append("<div class=\"card mb-4\">")
         .append("<div class=\"card-header d-flex align-items-center gap-2\">")
         .append("<i class=\"bi bi-bug-fill text-danger\"></i>")
         .append("<span class=\"fw-semibold\">Generate a new debug report</span>")
         .append("</div>")
         .append("<div class=\"card-body\">")
         .append("<p class=\"text-secondary small mb-3\">")
         .append("Captures a full snapshot of the running plugin: server info, config (tokens redacted), ")
         .append("Discord status, web server, database stats, and active modules. ")
         .append("The report is encrypted; only someone with the share URL can read it. ")
         .append("An AI analysis runs automatically if an LLM is configured.")
         .append("</p>")
         .append("<form method=\"post\" action=\"/dash/debug/generate\" class=\"row g-2 align-items-end\">")
         .append("<div class=\"col-sm-8 col-md-5\">")
         .append("<label class=\"form-label small mb-1\">Label <span class=\"text-secondary\">(optional)</span></label>")
         .append("<input class=\"form-control\" name=\"label\" type=\"text\" maxlength=\"120\" placeholder=\"e.g. after-restart, support-request-123\">")
         .append("</div>")
         .append("<div class=\"col-auto\">")
         .append("<button class=\"btn btn-danger\" type=\"submit\">")
         .append("<i class=\"bi bi-bug-fill me-1\"></i>Generate report")
         .append("</button>")
         .append("</div>")
         .append("</form>")
         .append("</div></div>");

        // Table of existing reports
        if (reports.isEmpty()) {
            h.append("<div class=\"text-center text-secondary py-5\">")
             .append("<i class=\"bi bi-inbox display-6 d-block mb-2\"></i>")
             .append("<div>No debug reports yet.</div>")
             .append("</div>");
        } else {
            h.append("<div class=\"card\">")
             .append("<div class=\"card-header\"><span class=\"fw-semibold\">Reports</span></div>")
             .append("<div class=\"table-responsive\">")
             .append("<table class=\"table table-hover table-sm mb-0 align-middle\">")
             .append("<thead><tr>")
             .append("<th>Date</th><th>Label</th><th>Analysis</th><th class=\"text-end\">Actions</th>")
             .append("</tr></thead><tbody>");

            for (DebugReport r : reports) {
                String date    = FMT.format(Instant.ofEpochMilli(r.createdAt()));
                String label   = r.label() != null && !r.label().isBlank() ? r.label() : "<span class=\"text-secondary\">(none)</span>";
                String url     = debugService.viewerUrl(r.id(), r.decryptKey());
                String statChip = statusChip(r.analysisStatus());

                h.append("<tr>")
                 .append("<td class=\"text-nowrap small\"><time>").append(esc(date)).append("</time></td>")
                 .append("<td>").append(r.label() != null && !r.label().isBlank() ? esc(r.label()) : label).append("</td>")
                 .append("<td>").append(statChip).append("</td>")
                 .append("<td class=\"text-end text-nowrap\">")
                 // Copy URL button
                 .append("<button class=\"btn btn-outline-secondary btn-sm me-1\" type=\"button\"")
                 .append(" title=\"Copy share URL\" onclick=\"copyDebugUrl(").append(jsStr(url)).append(")\">")
                 .append("<i class=\"bi bi-share\"></i> Share")
                 .append("</button>")
                 // Open button
                 .append("<a class=\"btn btn-outline-primary btn-sm me-1\" href=\"").append(esc(url)).append("\" target=\"_blank\" rel=\"noopener\">")
                 .append("<i class=\"bi bi-box-arrow-up-right\"></i> Open")
                 .append("</a>")
                 // Delete form
                 .append("<form method=\"post\" action=\"/dash/debug/").append(esc(r.id())).append("/delete\" class=\"d-inline\"")
                 .append(" data-confirm=\"Delete this debug report permanently? This cannot be undone.\">")
                 .append("<button class=\"btn btn-outline-danger btn-sm\" type=\"submit\">")
                 .append("<i class=\"bi bi-trash3\"></i> Delete")
                 .append("</button>")
                 .append("</form>")
                 .append("</td></tr>");
            }

            h.append("</tbody></table></div></div>");
        }

        h.append("<script>")
         .append("function copyDebugUrl(url){")
         .append("if(navigator.clipboard&&window.isSecureContext){")
         .append("navigator.clipboard.writeText(url).then(function(){WardenToast.show({kind:'success',message:'Share URL copied to clipboard.'});},function(){fallbackCopy(url);});")
         .append("}else{fallbackCopy(url);}")
         .append("}")
         .append("function fallbackCopy(url){")
         .append("var t=document.createElement('textarea');t.value=url;t.style.position='fixed';t.style.opacity='0';")
         .append("document.body.appendChild(t);t.focus();t.select();")
         .append("try{document.execCommand('copy');WardenToast.show({kind:'success',message:'Share URL copied.'});}catch(_){}")
         .append("document.body.removeChild(t);")
         .append("}")
         .append("</script>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    // ── POST /dash/debug/generate ─────────────────────────────────────────────

    public void generate(Context ctx) throws Exception {
        String label = ctx.formParam("label");
        var result = debugService.generate(label);
        String url = debugService.viewerUrl(result.id(), result.keyB64Url());
        ctx.redirect(Layout.flashRedirect(
                "/dash/debug",
                "success",
                "Debug report generated. Share URL: " + url));
    }

    // ── POST /dash/debug/{id}/delete ──────────────────────────────────────────

    public void delete(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        debugService.deleteReport(id);
        ctx.redirect(Layout.flashRedirect("/dash/debug", "success", "Debug report deleted."));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String statusChip(String status) {
        if (status == null) return "";
        return switch (status) {
            case "pending" -> "<span class=\"badge text-bg-warning\"><i class=\"bi bi-hourglass-split me-1\"></i>Pending</span>";
            case "done"    -> "<span class=\"badge text-bg-success\"><i class=\"bi bi-check2-circle me-1\"></i>Done</span>";
            case "failed"  -> "<span class=\"badge text-bg-danger\"><i class=\"bi bi-x-circle me-1\"></i>Failed</span>";
            case "skipped" -> "<span class=\"badge text-bg-secondary\"><i class=\"bi bi-dash-circle me-1\"></i>Skipped</span>";
            default        -> "<span class=\"badge text-bg-light\">" + esc(status) + "</span>";
        };
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
