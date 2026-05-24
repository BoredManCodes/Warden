package io.warden.onboarding;

import io.warden.audit.AuditService;
import io.warden.data.dao.AnswerDao;
import io.warden.data.dao.ApplicationDao;
import io.warden.data.dao.QuestionDao;
import io.warden.data.dao.SettingsDao;
import io.warden.data.dao.UserDao;
import io.warden.onboarding.model.AnswerValue;
import io.warden.onboarding.model.Question;
import io.warden.onboarding.model.QuestionKind;
import io.warden.onboarding.model.Settings;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates the per-user onboarding state machine. JDA-free so it can be
 * unit-tested without a Discord client. Each public method returns a StepResult
 * the caller (a JDA listener, a web route, etc.) renders into the appropriate
 * surface.
 */
public final class OnboardingService {

    private final UserDao userDao;
    private final QuestionDao questionDao;
    private final AnswerDao answerDao;
    private final ApplicationDao applicationDao;
    private final SettingsDao settingsDao;
    private final AuditService audit;
    private final Logger log;

    /**
     * Hook fired when an application moves to AWAITING_REVIEW (i.e., the user
     * has answered the last question and Submitted is about to be returned).
     * Wired by the plugin to DecisionService::triageAsync so triage kicks off
     * off the JDA thread the moment submission completes.
     */
    private volatile LongConsumer onApplicationSubmitted = id -> {};

    public void setOnApplicationSubmitted(LongConsumer hook) {
        this.onApplicationSubmitted = hook == null ? id -> {} : hook;
    }

    public OnboardingService(
            UserDao userDao,
            QuestionDao questionDao,
            AnswerDao answerDao,
            ApplicationDao applicationDao,
            SettingsDao settingsDao,
            AuditService audit,
            Logger log
    ) {
        this.userDao = userDao;
        this.questionDao = questionDao;
        this.answerDao = answerDao;
        this.applicationDao = applicationDao;
        this.settingsDao = settingsDao;
        this.audit = audit;
        this.log = log;
    }

    public sealed interface StepResult {
        record ShowRules(String rulesMarkdown) implements StepResult {}
        record AskQuestion(Question question, int answered, int total) implements StepResult {}
        record AlreadyDone(String state) implements StepResult {}
        record Submitted(long applicationId) implements StepResult {}
        record InvalidAnswer(Question question, String reason) implements StepResult {}
        record Stopped(String reason) implements StepResult {}
        record NotFound(String discordId) implements StepResult {}
    }

    /**
     * User clicked "Start in Discord" (or its equivalent). Transitions
     * PENDING_LINK / LINKED → LINKED and returns the rules to display.
     * If the user is already past linking, returns the appropriate next step.
     */
    public StepResult onStart(String discordId) throws SQLException {
        Optional<?> userOpt = userDao.findByDiscordId(discordId);
        if (userOpt.isEmpty()) {
            // First contact via DM (e.g., they joined before Warden was running). Record them.
            userDao.upsert(discordId, "(unknown)");
        }
        var user = userDao.findByDiscordId(discordId).orElseThrow();

        return switch (user.state()) {
            case PENDING_LINK, LINKED -> {
                userDao.setState(discordId, OnboardingState.LINKED);
                Settings settings = settingsDao.get();
                audit.write(AuditService.ACTOR_BOT, "onboarding_linked", discordId, Map.of("via", "discord_button"));
                yield new StepResult.ShowRules(settings.rulesMarkdown());
            }
            case AWAITING_ANSWERS -> nextStep(discordId);
            case AWAITING_REVIEW -> new StepResult.Submitted(latestAppIdOrZero(discordId));
            case APPROVED, DENIED -> new StepResult.AlreadyDone(user.state().wireName());
        };
    }

    /** User clicked "I agree" on the rules. Transitions to AWAITING_ANSWERS and asks the first question. */
    public StepResult onAgree(String discordId) throws SQLException {
        var user = userDao.findByDiscordId(discordId).orElse(null);
        if (user == null) return new StepResult.NotFound(discordId);

        // Allow agreeing from LINKED or already-AWAITING_ANSWERS (idempotent).
        switch (user.state()) {
            case LINKED, AWAITING_ANSWERS -> userDao.setState(discordId, OnboardingState.AWAITING_ANSWERS);
            case PENDING_LINK -> {
                // Treat as start + agree.
                userDao.setState(discordId, OnboardingState.AWAITING_ANSWERS);
            }
            case AWAITING_REVIEW -> { return new StepResult.Submitted(latestAppIdOrZero(discordId)); }
            case APPROVED, DENIED -> { return new StepResult.AlreadyDone(user.state().wireName()); }
        }
        audit.write(AuditService.ACTOR_BOT, "rules_accepted", discordId, Map.of());
        return nextStep(discordId);
    }

    /** User clicked "I have questions" / "I don't agree". For v1 we just stop with a reason. */
    public StepResult onDisagree(String discordId) throws SQLException {
        audit.write(AuditService.ACTOR_BOT, "rules_declined", discordId, Map.of());
        return new StepResult.Stopped("user_declined_rules");
    }

    /**
     * Persists a single answer (validated) and returns the next step in the flow.
     * Called by both the DM modal listener and the web POST handler.
     */
    public StepResult submitAnswer(String discordId, long questionId, AnswerValue value) throws SQLException {
        Optional<Question> qOpt = questionDao.findById(questionId);
        if (qOpt.isEmpty()) {
            return new StepResult.InvalidAnswer(
                    new Question(questionId, 0, "(unknown)", QuestionKind.SHORT_TEXT, List.of(), true, false),
                    "unknown_question");
        }
        Question question = qOpt.get();

        String validationError = validate(question, value);
        if (validationError != null) {
            return new StepResult.InvalidAnswer(question, validationError);
        }

        var user = userDao.findByDiscordId(discordId).orElse(null);
        if (user == null) return new StepResult.NotFound(discordId);

        // Make sure the user is in a state where answering is meaningful.
        switch (user.state()) {
            case PENDING_LINK, LINKED -> userDao.setState(discordId, OnboardingState.AWAITING_ANSWERS);
            case AWAITING_ANSWERS -> { /* normal path */ }
            case AWAITING_REVIEW -> { return new StepResult.Submitted(latestAppIdOrZero(discordId)); }
            case APPROVED, DENIED -> { return new StepResult.AlreadyDone(user.state().wireName()); }
        }

        answerDao.upsert(discordId, questionId, value);
        audit.write(AuditService.ACTOR_BOT, "answer_recorded", discordId,
                Map.of("questionId", questionId));
        return nextStep(discordId);
    }

    /**
     * Looks up the next unanswered active question. If none remain, submits an
     * application, transitions to AWAITING_REVIEW, and returns Submitted.
     */
    public StepResult nextStep(String discordId) throws SQLException {
        List<Question> questions = questionDao.listActive();
        Set<Long> answered = new HashSet<>();
        for (var a : answerDao.listFor(discordId)) answered.add(a.questionId());

        for (Question q : questions) {
            if (!answered.contains(q.id())) {
                return new StepResult.AskQuestion(q, answered.size(), questions.size());
            }
        }
        // All active questions answered - submit.
        long appId = applicationDao.create(discordId);
        userDao.setState(discordId, OnboardingState.AWAITING_REVIEW);
        audit.write(AuditService.ACTOR_BOT, "application_submitted", discordId,
                Map.of("applicationId", appId, "answerCount", answered.size()));
        try {
            onApplicationSubmitted.accept(appId);
        } catch (Exception e) {
            log.log(Level.WARNING, "onApplicationSubmitted hook threw for app " + appId, e);
        }
        return new StepResult.Submitted(appId);
    }

    /**
     * Minimal answer validation. Kind-specific rules live here so listeners
     * don't have to know about question semantics.
     */
    private String validate(Question q, AnswerValue value) {
        return switch (q.kind()) {
            case SHORT_TEXT -> {
                if (!(value instanceof AnswerValue.Single s)) yield "expected_text";
                String trimmed = s.value() == null ? "" : s.value().trim();
                if (trimmed.length() > 280) yield "too_long_280";
                if (q.required() && trimmed.isEmpty()) yield "required";
                yield null;
            }
            case LONG_TEXT -> {
                if (!(value instanceof AnswerValue.Single s)) yield "expected_text";
                String trimmed = s.value() == null ? "" : s.value().trim();
                if (trimmed.length() > 4000) yield "too_long_4000";
                if (q.required() && trimmed.isEmpty()) yield "required";
                yield null;
            }
            case SINGLE_CHOICE -> {
                if (!(value instanceof AnswerValue.Single s)) yield "expected_choice";
                if (!q.choices().contains(s.value())) yield "not_in_choices";
                yield null;
            }
            case MULTI_CHOICE -> {
                List<String> vs = (value instanceof AnswerValue.Multi m)
                        ? m.values()
                        : (value instanceof AnswerValue.Single s ? List.of(s.value()) : List.of());
                for (String v : vs) {
                    if (!q.choices().contains(v)) yield "not_in_choices: " + v;
                }
                if (q.required() && vs.isEmpty()) yield "required";
                yield null;
            }
        };
    }

    private long latestAppIdOrZero(String discordId) {
        try {
            return applicationDao.latestFor(discordId).map(a -> a.id()).orElse(0L);
        } catch (SQLException e) {
            log.log(Level.WARNING, "latestFor lookup failed for " + discordId, e);
            return 0L;
        }
    }
}
