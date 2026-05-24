package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.onboarding.model.Answer;
import io.warden.onboarding.model.Application;
import io.warden.onboarding.model.Question;
import io.warden.onboarding.model.Settings;
import io.warden.onboarding.model.TriageMode;
import io.warden.onboarding.model.UserRecord;
import io.warden.web.auth.AuditActor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-rendered HTML for the pending-applications queue. Bootstrap/AdminLTE
 * markup; the shell lives in {@link Layout}.
 */
public final class DashPendingHandlers {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final Services services;

    public DashPendingHandlers(Services services) {
        this.services = services;
    }

    public void list(Context ctx) throws Exception {
        List<Application> pending = services.applicationDao.listPending();
        List<Question> questions = services.questionDao.listAll();
        Map<Long, Question> qById = new HashMap<>();
        for (var q : questions) qById.put(q.id(), q);

        StringBuilder html = new StringBuilder(4096);
        html.append(Layout.head("Pending applications · Warden", "pending", ctx));
        html.append("<h1 class=\"h3 mb-1\">Pending applications</h1>");
        html.append("<p class=\"text-secondary mb-4\">")
                .append(pending.size()).append(" awaiting review · ")
                .append("<a href=\"/dash/audit\">audit log</a> · ")
                .append("<a href=\"/dash/members\">members</a></p>");

        if (pending.isEmpty()) {
            Settings settings = services.settingsDao.get();
            TriageMode mode = settings.flow().triageMode();
            String hint = switch (mode) {
                case MOD_ONLY -> "no applications have been submitted yet.";
                case LLM_AUTO -> "the LLM is auto-deciding confident cases - check the audit log for recent activity.";
                case LLM_ONLY -> "the LLM is taking every decision verbatim - check the audit log for recent activity.";
                case AUTO_APPROVE -> "auto-approve is on, so every submission gets through without a review - check the audit log.";
            };
            html.append("<div class=\"alert alert-secondary text-center\" role=alert>")
                    .append("Nothing pending. Current triage mode: <strong>")
                    .append(escape(mode.label()))
                    .append("</strong> - ")
                    .append(hint)
                    .append(" <a href=\"/dash/audit\">View audit log</a>.</div>");
        }

        var srv = services.discordSrv();
        for (Application app : pending) {
            UserRecord user = services.userDao.findByDiscordId(app.discordId()).orElse(null);
            List<Answer> answers = services.answerDao.listFor(app.discordId());
            String mcName = (srv == null) ? null : srv.mcNameFor(app.discordId()).orElse(null);

            html.append("<div class=\"card mb-3 shadow-sm\">");
            html.append("<div class=\"card-header d-flex flex-wrap gap-2 justify-content-between align-items-center\">");
            html.append("<div><span class=\"fw-semibold\">")
                    .append(escape(user == null ? "(unknown user)" : user.username())).append("</span>");
            if (mcName != null) {
                html.append(" <span class=mc-chip title=\"Linked Minecraft account (DiscordSRV)\">&#9935; ")
                        .append(escape(mcName)).append("</span>");
            }
            html.append("</div>");
            html.append("<small class=\"text-secondary\">App #").append(app.id())
                    .append(" · joined ").append(FMT.format(Instant.ofEpochMilli(app.submittedAt())))
                    .append(" · ").append(escape(app.discordId())).append("</small>");
            html.append("</div>");

            html.append("<div class=card-body>");

            if (app.llmDecision() != null) {
                String conf = app.llmConfidenceX1000() == null
                        ? "?"
                        : String.format(Locale.ROOT, "%.2f", app.llmConfidenceX1000() / 1000.0);
                html.append("<div class=llm-verdict>")
                        .append("<strong>LLM:</strong> ").append(escape(app.llmDecision()))
                        .append(" · conf <code>").append(conf).append("</code>")
                        .append("<br><em class=\"text-secondary\">")
                        .append(escape(app.llmReasoning() == null ? "" : app.llmReasoning()))
                        .append("</em></div>");
            }

            html.append("<dl class=mb-0>");
            for (Answer a : answers) {
                Question q = qById.get(a.questionId());
                String prompt = q == null ? "(deleted question " + a.questionId() + ")" : q.prompt();
                String val = a.value().display();
                html.append("<dt class=\"text-secondary text-uppercase\" style=\"font-size:.78em;letter-spacing:.04em;margin-top:.75rem\">")
                        .append(escape(prompt)).append("</dt>")
                        .append("<dd class=\"mb-0\" style=\"white-space:pre-wrap\">")
                        .append(escape(val.isEmpty() ? "(blank)" : val)).append("</dd>");
            }
            html.append("</dl>");

            html.append("</div>");

            html.append("<div class=\"card-footer d-flex flex-wrap gap-2 align-items-center\">");
            html.append("<form method=post action=\"/dash/pending/").append(app.id()).append("/approve\" class=\"m-0\">")
                    .append("<button class=\"btn btn-success\" type=submit>Approve</button></form>");
            html.append("<form method=post action=\"/dash/pending/").append(app.id()).append("/deny\" class=\"m-0 d-flex flex-wrap gap-2 align-items-center flex-grow-1\" style=\"min-width:0\">")
                    .append("<input class=\"form-control\" type=text name=note placeholder=\"internal reason (optional)\" maxlength=1000 style=\"flex:1 1 200px;min-width:180px\">")
                    .append("<input class=\"form-control\" type=text name=user_dm placeholder=\"message to user / DM (optional)\" maxlength=1000 style=\"flex:1 1 240px;min-width:200px\">")
                    .append("<button class=\"btn btn-danger\" type=submit>Deny</button></form>");
            html.append("</div>");

            html.append("</div>");
        }
        html.append(Layout.foot());
        ctx.html(html.toString());
    }

    public void approve(Context ctx) throws Exception {
        long appId = Long.parseLong(ctx.pathParam("id"));
        String modId = AuditActor.modDiscordId(ctx);
        services.decisionService().applyManualApprove(appId,
                modId == null ? "web-mod" : modId, ctx.formParam("note"));
        ctx.redirect("/dash/pending");
    }

    public void deny(Context ctx) throws Exception {
        long appId = Long.parseLong(ctx.pathParam("id"));
        String modId = AuditActor.modDiscordId(ctx);
        services.decisionService().applyManualDeny(appId,
                modId == null ? "web-mod" : modId,
                ctx.formParam("note"),
                ctx.formParam("user_dm"));
        ctx.redirect("/dash/pending");
    }

    private static String escape(String s) {
        return Layout.escape(s);
    }
}
