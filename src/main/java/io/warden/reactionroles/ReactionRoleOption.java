package io.warden.reactionroles;

public record ReactionRoleOption(
        long id,
        long groupId,
        String roleId,
        String label,
        String emoji,
        String description,
        int orderIndex
) {}
