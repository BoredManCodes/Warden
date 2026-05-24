package io.warden.llm;

import io.warden.onboarding.model.Answer;
import io.warden.onboarding.model.Question;
import io.warden.onboarding.model.Settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PromptBuilder {

    public record ApplicantContext(
            String discordId,
            String username,
            Integer accountAgeDays,
            long guildJoinedAgoMinutes,
            String minecraftName
    ) {}

    private PromptBuilder() {}

    /**
     * Assembles the user-side prompt: rules + applicant identity + Q/A pairs.
     * The system prompt is taken straight from settings.llmSystemPrompt
     * (seeded by V0002).
     */
    public static String buildUserInput(
            Settings settings,
            List<Question> questions,
            List<Answer> answers,
            ApplicantContext applicant
    ) {
        Map<Long, Question> byId = new HashMap<>();
        for (Question q : questions) byId.put(q.id(), q);

        StringBuilder sb = new StringBuilder(2048);
        sb.append("=== SERVER RULES ===\n");
        sb.append(safe(settings.rulesMarkdown(), "(no rules configured)")).append('\n');
        sb.append('\n');
        sb.append("=== APPLICANT ===\n");
        sb.append("Discord username: ").append(applicant.username()).append('\n');
        sb.append("Discord ID: ").append(applicant.discordId()).append('\n');
        sb.append("Account age: ").append(
                applicant.accountAgeDays() == null ? "unknown" : (applicant.accountAgeDays() + " days")
        ).append('\n');
        sb.append("Joined server: ").append(applicant.guildJoinedAgoMinutes()).append(" minutes ago\n");
        if (applicant.minecraftName() != null && !applicant.minecraftName().isBlank()) {
            sb.append("Linked Minecraft account: ").append(applicant.minecraftName()).append('\n');
        }
        sb.append('\n');
        sb.append("=== QUESTIONS AND ANSWERS ===\n");
        for (Answer a : answers) {
            Question q = byId.get(a.questionId());
            if (q == null) continue;
            sb.append("Q (").append(q.kind().wire()).append("): ").append(q.prompt()).append('\n');
            sb.append("A: ").append(a.value().display()).append('\n');
            sb.append('\n');
        }
        sb.append("=== END ===\n");
        sb.append("Return the JSON object now.\n");
        return sb.toString();
    }

    private static String safe(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }
}
