package io.warden.onboarding;

import io.warden.audit.AuditService;
import io.warden.config.WardenConfig;
import io.warden.data.Database;
import io.warden.data.SchemaLoader;
import io.warden.data.dao.AnswerDao;
import io.warden.data.dao.ApplicationDao;
import io.warden.data.dao.AuditDao;
import io.warden.data.dao.QuestionDao;
import io.warden.data.dao.SettingsDao;
import io.warden.data.dao.UserDao;
import io.warden.onboarding.OnboardingService.StepResult;
import io.warden.onboarding.model.AnswerValue;
import io.warden.onboarding.model.Question;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingServiceTest {

    @TempDir Path tmp;
    Database db;
    OnboardingService service;
    UserDao userDao;
    QuestionDao questionDao;
    AnswerDao answerDao;
    ApplicationDao applicationDao;

    @BeforeEach
    void setup() throws Exception {
        db = new Database(testConfig(tmp.resolve("test.db")));
        new SchemaLoader(db, Logger.getLogger("test")).initialise();
        userDao = new UserDao(db);
        questionDao = new QuestionDao(db);
        answerDao = new AnswerDao(db);
        applicationDao = new ApplicationDao(db);
        var settingsDao = new SettingsDao(db);
        var auditDao = new AuditDao(db);
        var audit = new AuditService(auditDao, Logger.getLogger("test"));
        service = new OnboardingService(userDao, questionDao, answerDao, applicationDao,
                settingsDao, audit, Logger.getLogger("test"));
    }

    @AfterEach
    void teardown() {
        if (db != null) db.close();
    }

    @Test
    void happyPath_startToSubmit_traversesAllSeededQuestions() throws Exception {
        String uid = "u-happy";
        userDao.upsert(uid, "alice");

        // 1. Start → ShowRules
        StepResult s1 = service.onStart(uid);
        var rules = assertInstanceOf(StepResult.ShowRules.class, s1);
        assertTrue(rules.rulesMarkdown().startsWith("# Server Rules"),
                "should pull seeded rules markdown");
        assertEquals(OnboardingState.LINKED, userDao.findByDiscordId(uid).orElseThrow().state());

        // 2. Agree → first question
        StepResult s2 = service.onAgree(uid);
        var q1 = assertInstanceOf(StepResult.AskQuestion.class, s2);
        assertEquals(0, q1.answered());
        assertEquals(4, q1.total(), "should match V2 seeded question count");
        assertEquals(OnboardingState.AWAITING_ANSWERS, userDao.findByDiscordId(uid).orElseThrow().state());

        // 3..N. Answer each in order. The seeded set is: short_text, long_text, single_choice, long_text(optional).
        List<Question> all = questionDao.listActive();
        Question prev = q1.question();
        for (int i = 0; i < all.size(); i++) {
            Question current = (i == 0) ? q1.question() : prev;
            AnswerValue val = sensibleAnswerFor(current);
            StepResult s = service.submitAnswer(uid, current.id(), val);
            if (i < all.size() - 1) {
                var next = assertInstanceOf(StepResult.AskQuestion.class, s, "expected another question at i=" + i);
                assertEquals(i + 1, next.answered());
                prev = next.question();
            } else {
                var submitted = assertInstanceOf(StepResult.Submitted.class, s);
                assertTrue(submitted.applicationId() > 0);
                assertEquals(OnboardingState.AWAITING_REVIEW, userDao.findByDiscordId(uid).orElseThrow().state());
                assertNotNull(applicationDao.findById(submitted.applicationId()).orElseThrow());
            }
        }
    }

    @Test
    void disagree_returnsStoppedReason() throws Exception {
        userDao.upsert("u-no", "bob");
        StepResult r = service.onDisagree("u-no");
        var stopped = assertInstanceOf(StepResult.Stopped.class, r);
        assertEquals("user_declined_rules", stopped.reason());
    }

    @Test
    void invalidAnswer_rejectsAndDoesNotAdvance() throws Exception {
        userDao.upsert("u-bad", "carol");
        service.onStart("u-bad");
        service.onAgree("u-bad");
        Question singleChoice = questionDao.listActive().stream()
                .filter(q -> q.kind() == io.warden.onboarding.model.QuestionKind.SINGLE_CHOICE)
                .findFirst().orElseThrow();
        StepResult r = service.submitAnswer("u-bad", singleChoice.id(), AnswerValue.of("not-a-real-choice"));
        var invalid = assertInstanceOf(StepResult.InvalidAnswer.class, r);
        assertEquals("not_in_choices", invalid.reason());
        // Make sure state didn't advance + nothing got stored
        assertEquals(0, answerDao.listFor("u-bad").size());
    }

    @Test
    void startWhileApproved_returnsAlreadyDone() throws Exception {
        userDao.upsert("u-app", "approved-user");
        userDao.setState("u-app", OnboardingState.APPROVED);
        StepResult r = service.onStart("u-app");
        var done = assertInstanceOf(StepResult.AlreadyDone.class, r);
        assertEquals("approved", done.state());
    }

    @Test
    void agreeIsIdempotent_secondCallStillReturnsAQuestion() throws Exception {
        userDao.upsert("u-2x", "double-clicker");
        service.onStart("u-2x");
        StepResult first = service.onAgree("u-2x");
        StepResult second = service.onAgree("u-2x");
        assertInstanceOf(StepResult.AskQuestion.class, first);
        assertInstanceOf(StepResult.AskQuestion.class, second);
    }

    /**
     * Build a benign answer per question kind so the happy-path test doesn't
     * have to hardcode the seeded prompts.
     */
    private static AnswerValue sensibleAnswerFor(Question q) {
        return switch (q.kind()) {
            case SHORT_TEXT, LONG_TEXT -> AnswerValue.of("ok");
            case SINGLE_CHOICE -> AnswerValue.of(q.choices().get(0));
            case MULTI_CHOICE -> AnswerValue.of(List.of(q.choices().get(0)));
        };
    }

    private static WardenConfig testConfig(Path dbFile) {
        return new WardenConfig(
                "", "", "", "", "",
                "127.0.0.1", 0, "http://localhost", "",
                dbFile, dbFile.getParent().resolve("www"),
                new WardenConfig.Ssl(false, 8443,
                        dbFile.getParent().resolve("ssl/fullchain.pem"),
                        dbFile.getParent().resolve("ssl/privkey.pem"),
                        true),
                new WardenConfig.GeoIp(false, "", "GeoLite2-Country", 7,
                        dbFile.getParent().resolve("geoip")),
                WardenConfig.Modules.allOn()
        );
    }
}
