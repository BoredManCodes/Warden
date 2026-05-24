package io.warden.alerts;

import java.util.List;

/**
 * A single user-defined alert. Stored in the {@code alerts} table; rendered
 * and dispatched by {@link AlertService} when a matching event fires.
 *
 * Two trigger modes:
 * <ul>
 *   <li><b>Built-in</b>: {@code event} carries an {@link AlertEvent} key,
 *       {@code triggerClass} is blank. The hard-coded Bukkit / Discord path
 *       handles dispatch and populates the standard variables.</li>
 *   <li><b>Custom (DSRV-style)</b>: {@code triggerClass} is a Bukkit event
 *       FQCN (or short name in a known package). {@link AlertManager}
 *       registers a dynamic listener via Bukkit's
 *       {@code registerEvent(Class, ...)}. The {@code event} key is still set
 *       (to anything; usually copied from {@code triggerClass}) so the picker
 *       has something to display but is otherwise ignored for dispatch.</li>
 * </ul>
 *
 * When {@code expressionsEnabled} is true, templates and conditions are
 * evaluated as SpEL against an {@code event} / {@code server} / {@code user}
 * root, matching DSRV's syntax. When false, templates fall back to the simple
 * {@code {var}} substitution that the built-in events populate.
 */
public record Alert(
        long id,
        String name,
        boolean enabled,
        String event,
        String channelId,
        String messageContent,
        boolean embedEnabled,
        String embedTitle,
        String embedDescription,
        String embedColorHex,
        String embedThumbnail,
        String embedImage,
        String embedFooter,
        String embedAuthorName,
        String embedAuthorIconUrl,
        List<AlertEmbedField> embedFields,
        String consoleCommands,
        String asPlayerCommands,
        String papiPlayerUuid,
        String triggerClass,
        String conditions,
        boolean expressionsEnabled,
        boolean asyncDispatch,
        long createdAt,
        long updatedAt
) {
    public Alert {
        if (name == null) name = "";
        if (event == null) event = "";
        if (channelId == null) channelId = "";
        if (messageContent == null) messageContent = "";
        if (embedTitle == null) embedTitle = "";
        if (embedDescription == null) embedDescription = "";
        if (embedColorHex == null || embedColorHex.isBlank()) embedColorHex = "#5865F2";
        if (embedThumbnail == null) embedThumbnail = "";
        if (embedImage == null) embedImage = "";
        if (embedFooter == null) embedFooter = "";
        if (embedAuthorName == null) embedAuthorName = "";
        if (embedAuthorIconUrl == null) embedAuthorIconUrl = "";
        embedFields = embedFields == null ? List.of() : List.copyOf(embedFields);
        if (consoleCommands == null) consoleCommands = "";
        if (asPlayerCommands == null) asPlayerCommands = "";
        if (papiPlayerUuid == null) papiPlayerUuid = "";
        if (triggerClass == null) triggerClass = "";
        if (conditions == null) conditions = "";
    }

    public AlertEvent parsedEvent() {
        return AlertEvent.fromKey(event);
    }

    /** True when the alert dispatches via {@link AlertManager} (dynamic Bukkit subscription). */
    public boolean isCustomTrigger() {
        return triggerClass != null && !triggerClass.isBlank();
    }
}
