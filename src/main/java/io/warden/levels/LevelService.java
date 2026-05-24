package io.warden.levels;

import io.warden.data.dao.UserDao;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Grants XP for messages, calculates level-ups, applies role rewards. Runs
 * synchronously off the JDA message thread; keeps DB writes minimal (one
 * upsert + maybe a level update) so it doesn't drag on chat throughput.
 */
public final class LevelService {

    private final LevelConfigDao configDao;
    private final LevelUserDao userDao;
    private final LevelRewardDao rewardDao;
    private final UserDao wardenUsers;
    private final Logger log;

    public LevelService(LevelConfigDao configDao, LevelUserDao userDao,
                        LevelRewardDao rewardDao, UserDao wardenUsers, Logger log) {
        this.configDao = configDao;
        this.userDao = userDao;
        this.rewardDao = rewardDao;
        this.wardenUsers = wardenUsers;
        this.log = log;
    }

    public LevelConfigDao configDao() { return configDao; }
    public LevelUserDao userDao() { return userDao; }
    public LevelRewardDao rewardDao() { return rewardDao; }

    /**
     * Grant XP for one message. Returns the new level on level-up, -1 if no
     * level change, -2 if XP was not granted (cooldown / exempt / disabled).
     */
    public int onMessage(Member member, MessageChannel channel, String content) {
        if (member == null || member.getUser().isBot()) return -2;
        LevelConfig cfg;
        try { cfg = configDao.get(); } catch (Exception e) { return -2; }
        if (!cfg.enabled()) return -2;
        if (cfg.noXpChannelIds().contains(channel.getId())) return -2;
        for (Role r : member.getRoles()) {
            if (cfg.noXpRoleIds().contains(r.getId())) return -2;
        }

        String userId = member.getId();
        long now = System.currentTimeMillis();
        try {
            var existing = userDao.find(userId);
            if (existing.isPresent()) {
                long since = now - existing.get().lastGrantAt();
                if (since < cfg.cooldownSeconds() * 1000L) return -2;
            }
            int min = Math.max(1, cfg.xpPerMessageMin());
            int max = Math.max(min, cfg.xpPerMessageMax());
            int base = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);

            int multiplier = 100;
            try {
                for (var m : rewardDao.listMultipliers()) {
                    if ("channel".equals(m.kind()) && m.targetId().equals(channel.getId())) {
                        multiplier = Math.max(multiplier, m.multiplier());
                    } else if ("role".equals(m.kind())) {
                        for (Role r : member.getRoles()) {
                            if (r.getId().equals(m.targetId())) {
                                multiplier = Math.max(multiplier, m.multiplier());
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            long grant = Math.max(1, base * multiplier / 100);
            userDao.grant(userId, grant);

            var after = userDao.find(userId).orElse(null);
            if (after == null) return -1;
            int newLevel = LevelConfig.levelForXp(after.xp());
            if (newLevel > after.level()) {
                userDao.setLevel(userId, newLevel);
                return newLevel;
            }
            return -1;
        } catch (Exception e) {
            log.log(Level.WARNING, "level grant failed", e);
            return -2;
        }
    }

    /**
     * Grant XP for one Minecraft chat message tied to a Discord account. Shares
     * the same cooldown bucket as Discord chat so a player can't double-dip by
     * chatting on both sides at once. Channel/role multipliers don't apply
     * (the message didn't happen in a Discord channel); the no-xp lists are
     * also bypassed here because we can't reliably check the user's Discord
     * roles off the chat-event thread.
     *
     * Returns the new level on level-up, -1 if no level change, -2 if XP was
     * not granted (cooldown / disabled / MC XP toggle off).
     */
    public int onMinecraftMessage(String discordId) {
        if (discordId == null || discordId.isBlank()) return -2;
        LevelConfig cfg;
        try { cfg = configDao.get(); } catch (Exception e) { return -2; }
        if (!cfg.enabled() || !cfg.mcXpEnabled()) return -2;

        long now = System.currentTimeMillis();
        try {
            var existing = userDao.find(discordId);
            if (existing.isPresent()) {
                long since = now - existing.get().lastGrantAt();
                if (since < cfg.cooldownSeconds() * 1000L) return -2;
            }
            int min = Math.max(1, cfg.xpPerMessageMin());
            int max = Math.max(min, cfg.xpPerMessageMax());
            int base = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            userDao.grant(discordId, base);

            var after = userDao.find(discordId).orElse(null);
            if (after == null) return -1;
            int newLevel = LevelConfig.levelForXp(after.xp());
            if (newLevel > after.level()) {
                userDao.setLevel(discordId, newLevel);
                return newLevel;
            }
            return -1;
        } catch (Exception e) {
            log.log(Level.WARNING, "level grant (mc) failed", e);
            return -2;
        }
    }

    public List<LevelRewardDao.Reward> rewardsForLevel(int level) {
        try { return rewardDao.listRewards().stream().filter(r -> r.level() <= level).toList(); }
        catch (Exception e) { return List.of(); }
    }
}
