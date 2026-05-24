package io.warden.levels;

import java.util.List;

public record LevelConfig(
        boolean enabled,
        int xpPerMessageMin,
        int xpPerMessageMax,
        int cooldownSeconds,
        boolean levelupAnnounce,
        String levelupChannelId,
        String levelupMessageTemplate,
        boolean leaderboardPublic,
        List<String> noXpRoleIds,
        List<String> noXpChannelIds,
        String rankCardAccent,
        String rankCardBackground,
        boolean mcXpEnabled
) {
    public LevelConfig {
        noXpRoleIds   = noXpRoleIds   == null ? List.of() : List.copyOf(noXpRoleIds);
        noXpChannelIds = noXpChannelIds == null ? List.of() : List.copyOf(noXpChannelIds);
    }

    public static LevelConfig blank() {
        return new LevelConfig(
                false, 15, 25, 60,
                true, "",
                "GG {user_mention}, you reached level **{level}**!",
                true,
                List.of(), List.of(),
                "#5865F2", "",
                false);
    }

    /** XP required to reach a given level from level 0. Mirrors the Mee6/Discord-style curve. */
    public static long xpForLevel(int level) {
        if (level <= 0) return 0;
        long total = 0;
        for (int l = 0; l < level; l++) {
            total += 5L * l * l + 50L * l + 100L;
        }
        return total;
    }

    public static int levelForXp(long xp) {
        int level = 0;
        long need = 0;
        while (true) {
            need += 5L * level * level + 50L * level + 100L;
            if (xp < need) return level;
            level++;
            if (level > 1000) return level;
        }
    }
}
