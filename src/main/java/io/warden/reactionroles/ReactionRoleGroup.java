package io.warden.reactionroles;

import java.util.List;

public record ReactionRoleGroup(
        long id,
        String name,
        String channelId,
        String messageId,
        String mode,
        String style,
        String title,
        String description,
        String colorHex,
        int maxSelections,
        String requiredRole,
        long createdAt,
        long updatedAt,
        List<ReactionRoleOption> options
) {
    public ReactionRoleGroup {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** normal | unique | verify | reversed | limit | binding */
    public Mode parsedMode() {
        try { return Mode.valueOf((mode == null ? "normal" : mode).toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception e) { return Mode.NORMAL; }
    }

    public enum Mode {
        /** add or remove freely */
        NORMAL,
        /** at most one role from the group at a time */
        UNIQUE,
        /** clicking adds the role, can never remove */
        VERIFY,
        /** reactions add when removed and vice versa */
        REVERSED,
        /** maxSelections roles at most */
        LIMIT,
        /** clicking adds, can never remove or change */
        BINDING
    }
}
