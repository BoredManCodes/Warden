package io.warden.moderation;

import java.util.List;

/**
 * Auto-mod settings for one guild. Each filter is independently toggleable;
 * the global {@link #enabled()} flag is a kill switch that short-circuits all
 * message scanning.
 *
 * Thresholds are stored as integers in the database; "percent" fields are
 * 0..100. action_default applies to any rule that triggers; per-rule overrides
 * could be added later but a single default keeps the UI manageable.
 */
public record AutomodConfig(
        boolean enabled,
        boolean spamEnabled,
        int spamThreshold,
        int spamWindowSeconds,
        boolean capsEnabled,
        int capsMinLength,
        int capsPercent,
        boolean badWordsEnabled,
        String badWordsList,
        boolean linksEnabled,
        String linksAllowlist,
        boolean invitesEnabled,
        boolean massMentionEnabled,
        int massMentionThreshold,
        boolean emojiFloodEnabled,
        int emojiFloodThreshold,
        boolean zalgoEnabled,
        String actionDefault,
        List<String> exemptRoleIds,
        List<String> exemptChannelIds,
        String logChannelId,
        List<WarnThreshold> warnThresholds
) {
    public AutomodConfig {
        exemptRoleIds   = exemptRoleIds   == null ? List.of() : List.copyOf(exemptRoleIds);
        exemptChannelIds = exemptChannelIds == null ? List.of() : List.copyOf(exemptChannelIds);
        warnThresholds  = warnThresholds  == null ? List.of() : List.copyOf(warnThresholds);
    }

    public static AutomodConfig blank() {
        return new AutomodConfig(
                false, true, 5, 7,
                true, 10, 70,
                false, "",
                false, "",
                false,
                true, 5,
                true, 8,
                true,
                "delete",
                List.of(), List.of(),
                "",
                List.of()
        );
    }

    /**
     * Threshold at which to take an additional action after N warnings.
     * action is one of: mute, kick, ban, tempban, timeout.
     * durationSeconds is required for tempban/timeout, ignored otherwise.
     */
    public record WarnThreshold(int count, String action, int durationSeconds) {}
}
