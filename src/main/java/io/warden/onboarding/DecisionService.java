package io.warden.onboarding;

import io.warden.audit.AuditService;
import io.warden.data.dao.AnswerDao;
import io.warden.data.dao.ApplicationDao;
import io.warden.data.dao.QuestionDao;
import io.warden.data.dao.SettingsDao;
import io.warden.data.dao.UserDao;
import io.warden.discord.DiscordSrvBridge;
import io.warden.llm.LlmVerdict;
import io.warden.llm.LlmVerdictParser;
import io.warden.llm.ManifestClient;
import io.warden.llm.PromptBuilder;
import io.warden.llm.TriagePolicy;
import io.warden.onboarding.model.Application;
import io.warden.onboarding.model.DenyAction;
import io.warden.onboarding.model.FlowConfig;
import io.warden.onboarding.model.Settings;
import io.warden.onboarding.model.TriageMode;
import io.warden.onboarding.Template;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates: triage (LLM call), threshold policy, and final-decision actions.
 *
 * The pipeline:
 *   1. triageAsync(applicationId) - runs Manifest call off the JDA / web thread
 *   2. → applyTriage() (per the configured triage mode) - auto-approve, mod-only, or LLM
 *   3. → applyFinal{Approve,Deny,Escalate}() - touches the guild via DecisionExecutor
 *
 * Mods later call applyManualApprove/applyManualDeny from the Discord embed
 * buttons (M6) or from /dash/pending (M6 web side).
 */
public final class DecisionService {

    private final SettingsDao settingsDao;
    private final ApplicationDao applicationDao;
    private final QuestionDao questionDao;
    private final AnswerDao answerDao;
    private final UserDao userDao;
    private final ManifestClient manifest;
    private final DecisionExecutor executor;
    private final AuditService audit;
    private final ExecutorService bg;
    private final Logger log;
    private final DiscordSrvBridge discordSrv;

    public DecisionService(
            SettingsDao settingsDao,
            ApplicationDao applicationDao,
            QuestionDao questionDao,
            AnswerDao answerDao,
            UserDao userDao,
            ManifestClient manifest,
            DecisionExecutor executor,
            AuditService audit,
            ExecutorService bg,
            Logger log,
            DiscordSrvBridge discordSrv
    ) {
        this.settingsDao = settingsDao;
        this.applicationDao = applicationDao;
        this.questionDao = questionDao;
        this.answerDao = answerDao;
        this.userDao = userDao;
        this.manifest = manifest;
        this.executor = executor;
        this.audit = audit;
        this.bg = bg;
        this.log = log;
        this.discordSrv = discordSrv;
    }

    /** Schedule triage off the calling thread. */
    public void triageAsync(long applicationId) {
        bg.submit(() -> {
            try {
                triageSync(applicationId);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Triage failed for application " + applicationId, e);
                audit.write(AuditService.ACTOR_SYSTEM, "triage_failed", null,
                        Map.of("applicationId", applicationId, "error", e.getMessage()));
            }
        });
    }

    /** Blocking - exposed so tests can drive it deterministically. */
    public void triageSync(long applicationId) throws SQLException {
        Application app = applicationDao.findById(applicationId).orElse(null);
        if (app == null) {
            log.warning("triage: application " + applicationId + " not found");
            return;
        }
        if (app.finalDecision() != null) {
            log.info("triage: application " + applicationId + " already decided (" + app.finalDecision() + "); skipping");
            return;
        }

        Settings settings = settingsDao.get();
        FlowConfig flow = settings.flow();

        switch (flow.triageMode()) {
            case AUTO_APPROVE -> {
                audit.write(AuditService.ACTOR_LLM, "auto_approve_by_policy", app.discordId(),
                        Map.of("applicationId", applicationId, "triageMode", "auto_approve"));
                applyApprove(app, settings, "llm", "Auto-approved (Auto-approve triage mode)");
            }
            case MOD_ONLY -> {
                audit.write(AuditService.ACTOR_LLM, "escalate_by_policy", app.discordId(),
                        Map.of("applicationId", applicationId, "triageMode", "mod_only"));
                escalate(app, settings, null, "Escalated to mods (Mods-only triage mode; no LLM call performed).");
            }
            case LLM_AUTO, LLM_ONLY -> runLlmTriage(app, settings);
        }
    }

    private void runLlmTriage(Application app, Settings settings) throws SQLException {
        if (!settings.llmConfigured()) {
            log.warning("triage: LLM api key not set in /dash/config; falling back to escalate for application " + app.id());
            escalate(app, settings, null, "LLM not configured; escalating by default.");
            return;
        }
        var questions = questionDao.listAll();
        var answers = answerDao.listFor(app.discordId());
        var user = userDao.findByDiscordId(app.discordId()).orElse(null);
        String username = user == null ? "(unknown)" : user.username();
        long joinedAgoMin = user == null ? 0 :
                Math.max(0, (System.currentTimeMillis() - user.joinedAt()) / 60_000L);
        String mcName = (discordSrv == null) ? null
                : discordSrv.mcNameFor(app.discordId()).orElse(null);
        var ctx = new PromptBuilder.ApplicantContext(app.discordId(), username, null, joinedAgoMin, mcName);

        String userInput = PromptBuilder.buildUserInput(settings, questions, answers, ctx);
        String systemPrompt = settings.llmSystemPrompt();

        LlmVerdict verdict;
        try {
            ManifestClient.Endpoint endpoint = new ManifestClient.Endpoint(
                    settings.llmApiKey(), settings.llmBaseUrl(), settings.llmModel());
            String text = manifest.requestText(endpoint, systemPrompt, userInput);
            verdict = LlmVerdictParser.parse(text);
        } catch (Exception e) {
            log.log(Level.WARNING, "Manifest call failed for application " + app.id(), e);
            audit.write(AuditService.ACTOR_LLM, "manifest_call_failed", app.discordId(),
                    Map.of("applicationId", app.id(), "error", e.getMessage()));
            escalate(app, settings, null, "LLM call failed: " + e.getMessage());
            return;
        }

        applicationDao.recordLlm(app.id(), verdict.decision().wire(),
                (int) Math.round(verdict.confidence() * 1000), verdict.reasoning());

        if (settings.flow().triageMode() == TriageMode.LLM_ONLY) {
            // No escalation in this mode - take the LLM's decision verbatim (treat escalate as deny).
            // Exception: when auto-deny is prohibited, deny verdicts (and escalate-coerced-to-deny)
            // are escalated to mods so a human can confirm the rejection.
            switch (verdict.decision()) {
                case APPROVE -> applyApprove(app, settings, "llm", verdict.reasoning());
                case DENY -> {
                    if (settings.llmAutoDenyEnabled()) {
                        applyDeny(app, settings, "llm", verdict.reasoning());
                    } else {
                        escalate(app, settings, verdict,
                                "LLM recommends deny; auto-deny disabled. " + verdict.reasoning());
                    }
                }
                case ESCALATE -> {
                    if (settings.llmAutoDenyEnabled()) {
                        applyDeny(app, settings, "llm",
                                "llm_only mode: escalate verdict treated as deny. " + verdict.reasoning());
                    } else {
                        escalate(app, settings, verdict,
                                "llm_only mode with auto-deny disabled: escalate verdict held for mod review. "
                                        + verdict.reasoning());
                    }
                }
            }
            return;
        }

        // LLM_AUTO
        var outcome = TriagePolicy.apply(verdict,
                settings.llmAutoApproveThreshold(), settings.llmAutoDenyThreshold());
        switch (outcome.finalDecision()) {
            case APPROVE -> applyApprove(app, settings, "llm", outcome.verdict().reasoning());
            case DENY -> {
                if (settings.llmAutoDenyEnabled()) {
                    applyDeny(app, settings, "llm", outcome.verdict().reasoning());
                } else {
                    escalate(app, settings, verdict,
                            "LLM recommends deny (confidence above threshold); auto-deny disabled, escalating. "
                                    + outcome.verdict().reasoning());
                }
            }
            case ESCALATE -> escalate(app, settings, verdict, outcome.verdict().reasoning());
        }
    }

    /* ---------- final actions ---------- */

    public void applyApprove(Application app, Settings settings, String decidedBy, String note) throws SQLException {
        applicationDao.recordFinal(app.id(), "approve", decidedBy, note);
        userDao.setState(app.discordId(), OnboardingState.APPROVED);
        audit.write(AuditService.ACTOR_BOT, "application_approved", app.discordId(),
                decisionPayload(app.id(), decidedBy, null));

        FlowConfig flow = settings.flow();
        // Role swap
        if (!settings.fullRoleId().isBlank()) {
            executor.assignRole(app.discordId(), settings.fullRoleId(), "Warden: approved");
        }
        if (!settings.gatedRoleId().isBlank()) {
            executor.removeRole(app.discordId(), settings.gatedRoleId(), "Warden: approved (was gated)");
        }
        // Extra roles
        for (String r : flow.approveExtraRoles()) {
            if (!r.isBlank()) executor.assignRole(app.discordId(), r, "Warden: approval extra role");
        }
        // DM welcome
        if (flow.approveDmEnabled()) {
            String dm = Template.render(flow.approveDmTemplate(), Map.of(
                    "username", username(app.discordId()),
                    "guild_name", executor.guildName()));
            if (!dm.isBlank()) executor.sendDm(app.discordId(), dm);
        }
        // Channel announce
        if (flow.approveChannelAnnounce() && !settings.welcomeChannelId().isBlank()) {
            String body = Template.render(flow.approveChannelTemplate(), Map.of(
                    "username", username(app.discordId()),
                    "mention", "<@" + app.discordId() + ">",
                    "guild_name", executor.guildName()));
            if (!body.isBlank()) executor.sendChannel(settings.welcomeChannelId(), body);
        }
        // Mark mod review embed (if there is one) as decided.
        if (app.modMessageId() != null && !settings.modReviewChannelId().isBlank()) {
            executor.markModReviewEmbedDecided(settings.modReviewChannelId(), app.modMessageId(),
                    "approve", decidedBy, note);
        }
    }

    public void applyDeny(Application app, Settings settings, String decidedBy, String note) throws SQLException {
        applyDeny(app, settings, decidedBy, note, null);
    }

    /**
     * Same as {@link #applyDeny(Application, Settings, String, String)}, but with an explicit
     * user-facing DM body. When {@code userDmMessage} is non-blank it is sent verbatim
     * (with {username}/{guild_name} substitution) and overrides the templated deny DM.
     * When blank/null, falls back to the existing template behaviour.
     */
    public void applyDeny(Application app, Settings settings, String decidedBy, String note,
                          String userDmMessage) throws SQLException {
        applicationDao.recordFinal(app.id(), "deny", decidedBy, note);
        userDao.setState(app.discordId(), OnboardingState.DENIED);
        boolean customDm = userDmMessage != null && !userDmMessage.isBlank();
        audit.write(AuditService.ACTOR_BOT, "application_denied", app.discordId(),
                decisionPayload(app.id(), decidedBy, customDm));

        FlowConfig flow = settings.flow();
        if (customDm) {
            String dm = Template.render(userDmMessage, Map.of(
                    "username", username(app.discordId()),
                    "guild_name", executor.guildName()));
            if (!dm.isBlank()) executor.sendDm(app.discordId(), dm);
        } else if (flow.denyDmEnabled()) {
            String dm = Template.render(flow.denyDmTemplate(), Map.of(
                    "username", username(app.discordId()),
                    "guild_name", executor.guildName()));
            if (!dm.isBlank()) executor.sendDm(app.discordId(), dm);
        }
        DenyAction action = flow.denyAction();
        switch (action) {
            case STRIP_GATED -> {
                if (!settings.gatedRoleId().isBlank()) {
                    executor.removeRole(app.discordId(), settings.gatedRoleId(), "Warden: denied");
                }
            }
            case KICK -> executor.kickMember(app.discordId(), "Warden: denied");
            case BAN  -> executor.banMember(app.discordId(), "Warden: denied");
            case LEAVE_GATED -> { /* no-op; user keeps gated role */ }
        }
        if (app.modMessageId() != null && !settings.modReviewChannelId().isBlank()) {
            executor.markModReviewEmbedDecided(settings.modReviewChannelId(), app.modMessageId(),
                    "deny", decidedBy, note);
        }
    }

    /**
     * Move to mod-review state and post the review embed in mod_review_channel_id (if set).
     * The verdict may be null when escalating outside the LLM path (e.g., mod_only mode).
     */
    public void escalate(Application app, Settings settings, LlmVerdict verdict, String reasoning) throws SQLException {
        // State stays AWAITING_REVIEW (set when the application was created in OnboardingService).
        if (verdict != null) {
            applicationDao.recordLlm(app.id(), "escalate",
                    (int) Math.round(verdict.confidence() * 1000),
                    verdict.reasoning());
        }
        audit.write(AuditService.ACTOR_BOT, "application_escalated", app.discordId(),
                Map.of("applicationId", app.id(), "reasoning", reasoning));

        if (settings.modReviewChannelId().isBlank()) {
            log.warning("escalate: mod_review_channel_id not set; application " + app.id() + " is in queue but mods won't get the embed");
            return;
        }
        var questions = questionDao.listAll();
        var answers = answerDao.listFor(app.discordId());
        String username = username(app.discordId());
        Optional<String> messageId = executor.postModReviewEmbed(
                settings.modReviewChannelId(), app, questions, answers, settings,
                username, reasoning, verdict == null ? -1 : verdict.confidence());
        messageId.ifPresent(id -> {
            try { applicationDao.setModMessageId(app.id(), id); }
            catch (SQLException e) { log.log(Level.WARNING, "setModMessageId failed", e); }
        });
    }

    /* ---------- mod-initiated convenience wrappers ---------- */

    public void applyManualApprove(long applicationId, String modDiscordId, String note) throws SQLException {
        Application app = applicationDao.findById(applicationId).orElseThrow();
        Settings settings = settingsDao.get();
        applyApprove(app, settings, modDiscordId, note == null ? "" : note);
    }

    public void applyManualDeny(long applicationId, String modDiscordId, String note) throws SQLException {
        applyManualDeny(applicationId, modDiscordId, note, null);
    }

    public void applyManualDeny(long applicationId, String modDiscordId, String note, String userDmMessage)
            throws SQLException {
        Application app = applicationDao.findById(applicationId).orElseThrow();
        Settings settings = settingsDao.get();
        applyDeny(app, settings, modDiscordId, note == null ? "" : note, userDmMessage);
    }

    private String username(String discordId) {
        try {
            return userDao.findByDiscordId(discordId).map(u -> u.username()).orElse("(unknown)");
        } catch (SQLException e) {
            return "(unknown)";
        }
    }

    /**
     * Build the audit payload for an approve/deny event. When {@code decidedBy} looks
     * like a Discord snowflake, resolve and attach {@code modName} so the audit log can
     * render the moderator's name instead of an opaque id.
     */
    private Map<String, Object> decisionPayload(long applicationId, String decidedBy, Boolean customDm) {
        Map<String, Object> p = new HashMap<>();
        p.put("applicationId", applicationId);
        p.put("decidedBy", decidedBy);
        if (decidedBy != null && decidedBy.matches("\\d{15,20}")) {
            try {
                userDao.findByDiscordId(decidedBy).ifPresent(u -> {
                    if (u.username() != null && !u.username().isBlank()) {
                        p.put("modName", u.username());
                        p.put("modId", decidedBy);
                    }
                });
            } catch (SQLException ignored) {
                // best-effort; the display layer can still resolve later
            }
        }
        if (customDm != null) p.put("customUserDm", customDm);
        return p;
    }
}
