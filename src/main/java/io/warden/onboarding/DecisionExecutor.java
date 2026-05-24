package io.warden.onboarding;

import io.warden.onboarding.model.Answer;
import io.warden.onboarding.model.Application;
import io.warden.onboarding.model.Question;
import io.warden.onboarding.model.Settings;

import java.util.List;
import java.util.Optional;

/**
 * Side-effecting actions the DecisionService needs to perform on the Discord guild.
 *
 * Production implementation is io.warden.discord.JdaDecisionExecutor (uses JDA).
 * Tests use a fake recording calls without touching JDA.
 */
public interface DecisionExecutor {

    void assignRole(String discordId, String roleId, String reason);
    void removeRole(String discordId, String roleId, String reason);
    void sendDm(String discordId, String content);
    void sendChannel(String channelId, String content);
    void kickMember(String discordId, String reason);
    void banMember(String discordId, String reason);

    /**
     * Post a "Pending review" embed in the mod channel and return its messageId
     * (so we can edit/delete later when a mod decides). Empty if posting failed
     * or no JDA guild is available.
     */
    Optional<String> postModReviewEmbed(
            String channelId,
            Application application,
            List<Question> questions,
            List<Answer> answers,
            Settings settings,
            String discordUsername,
            String reasoning,
            double confidence
    );

    /** Edit the mod review embed to reflect a final mod decision (used after mod approves/denies). */
    default void markModReviewEmbedDecided(
            String channelId, String messageId,
            String finalDecision, String decidedBy, String note
    ) {}

    /**
     * Resolved guild display name, used for the {guild_name} placeholder in
     * approve/deny templates. Falls back to "the server" when JDA isn't connected
     * or the configured guild can't be found.
     */
    default String guildName() {
        return "the server";
    }
}
