package io.warden.autoresponder;

import java.util.List;

/**
 * One autoresponder rule. The pattern is interpreted according to {@link #matchMode()}:
 * {@code exact}, {@code contains}, {@code prefix}, {@code suffix}, or {@code regex}.
 * Response delivery is either plain {@code content} or a branded {@code embed}.
 *
 * Channel/role allow lists restrict where the rule fires; deny lists override.
 * Cooldown is per-rule across the whole guild (a simple last-fired timestamp
 * lives in {@link AutoresponderService}, not in the DB).
 */
public record Autoresponder(
        long id,
        String name,
        boolean enabled,
        String matchMode,
        String pattern,
        boolean caseInsensitive,
        String responseMode,
        String content,
        String embedTitle,
        String embedDescription,
        String embedColor,
        String embedImageUrl,
        String embedThumbnailUrl,
        String embedAuthorName,
        String embedAuthorIcon,
        String embedFooterText,
        String embedFooterIcon,
        List<String> extraImageUrls,
        List<String> allowChannelIds,
        List<String> denyChannelIds,
        List<String> allowRoleIds,
        List<String> denyRoleIds,
        int cooldownSeconds,
        boolean replyToTrigger,
        boolean deleteTrigger,
        boolean mentionAuthor,
        int priority,
        long createdAt,
        long updatedAt
) {
    public Autoresponder {
        if (name == null) name = "";
        if (matchMode == null || matchMode.isBlank()) matchMode = "contains";
        if (pattern == null) pattern = "";
        if (responseMode == null || responseMode.isBlank()) responseMode = "content";
        if (content == null) content = "";
        if (embedTitle == null) embedTitle = "";
        if (embedDescription == null) embedDescription = "";
        if (embedColor == null || embedColor.isBlank()) embedColor = "#5865F2";
        if (embedImageUrl == null) embedImageUrl = "";
        if (embedThumbnailUrl == null) embedThumbnailUrl = "";
        if (embedAuthorName == null) embedAuthorName = "";
        if (embedAuthorIcon == null) embedAuthorIcon = "";
        if (embedFooterText == null) embedFooterText = "";
        if (embedFooterIcon == null) embedFooterIcon = "";
        extraImageUrls = extraImageUrls == null ? List.of() : List.copyOf(extraImageUrls);
        allowChannelIds = allowChannelIds == null ? List.of() : List.copyOf(allowChannelIds);
        denyChannelIds = denyChannelIds == null ? List.of() : List.copyOf(denyChannelIds);
        allowRoleIds = allowRoleIds == null ? List.of() : List.copyOf(allowRoleIds);
        denyRoleIds = denyRoleIds == null ? List.of() : List.copyOf(denyRoleIds);
    }

    public static Autoresponder blank() {
        long now = System.currentTimeMillis();
        return new Autoresponder(
                0L, "", true,
                "contains", "", true,
                "content", "",
                "", "", "#5865F2", "", "", "", "", "", "",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                0, false, false, false, 0, now, now);
    }

    public boolean isEmbed() { return "embed".equalsIgnoreCase(responseMode); }
}
