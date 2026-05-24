package io.warden.levels;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.warden.discord.DiscordService;
import io.warden.discord.DiscordSrvBridge;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Counts Minecraft chat messages toward the leaderboard for the player's
 * linked Discord account. Resolves the link via DiscordSRV; players without a
 * link are silently ignored. The cooldown enforced by {@link LevelService} is
 * shared with the Discord side, so chatting in both at once doesn't double-up.
 */
public final class LevelMcChatListener implements Listener {

    private final LevelService levels;
    private final DiscordSrvBridge discordSrv;
    private final DiscordService discord;
    private final String guildId;
    private final Logger log;

    public LevelMcChatListener(LevelService levels, DiscordSrvBridge discordSrv,
                               DiscordService discord, String guildId, Logger log) {
        this.levels = levels;
        this.discordSrv = discordSrv;
        this.discord = discord;
        this.guildId = guildId;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        Optional<String> linked;
        try { linked = discordSrv == null ? Optional.empty() : discordSrv.discordIdFor(player.getUniqueId()); }
        catch (Exception e) { return; }
        if (linked.isEmpty()) return;
        String discordId = linked.get();

        int newLevel = levels.onMinecraftMessage(discordId);
        if (newLevel < 0) return;

        LevelConfig cfg;
        try { cfg = levels.configDao().get(); } catch (Exception e) { return; }
        applyLevelUp(discordId, player.getName(), newLevel, cfg);
    }

    private void applyLevelUp(String discordId, String playerName, int newLevel, LevelConfig cfg) {
        if (discord == null) return;
        JDA jda = discord.jda();
        if (jda == null) return;
        Guild guild;
        try { guild = jda.getGuildById(guildId); }
        catch (Exception e) { return; }
        if (guild == null) return;

        guild.retrieveMemberById(discordId).queue(
                member -> {
                    try { applyRoleRewards(guild, member, newLevel); }
                    catch (Exception e) { log.log(Level.WARNING, "mc level reward apply failed", e); }
                    if (cfg.levelupAnnounce()) {
                        announce(guild, member, playerName, newLevel, cfg);
                    }
                },
                err -> {
                    if (cfg.levelupAnnounce()) {
                        announceWithoutMember(guild, discordId, playerName, newLevel, cfg);
                    }
                });
    }

    private void applyRoleRewards(Guild guild, Member member, int level) {
        try {
            var rewards = levels.rewardDao().listRewards();
            for (var r : rewards) {
                if (r.level() <= level) {
                    Role role = guild.getRoleById(r.roleId());
                    if (role != null && !member.getRoles().contains(role)) {
                        guild.addRoleToMember(member, role).queue(ok -> {}, err -> {});
                    }
                    if (!r.stack()) {
                        for (var other : rewards) {
                            if (other.level() < r.level()) {
                                Role o = guild.getRoleById(other.roleId());
                                if (o != null && member.getRoles().contains(o)) {
                                    guild.removeRoleFromMember(member, o).queue(ok -> {}, err -> {});
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "mc level reward apply failed", e);
        }
    }

    private void announce(Guild guild, Member member, String playerName, int newLevel, LevelConfig cfg) {
        String channelId = cfg.levelupChannelId();
        if (channelId == null || channelId.isBlank()) return;
        TextChannel ch = guild.getTextChannelById(channelId);
        if (ch == null) return;
        String tpl = (cfg.levelupMessageTemplate() == null || cfg.levelupMessageTemplate().isBlank())
                ? "GG {user_mention}, you reached level **{level}**!"
                : cfg.levelupMessageTemplate();
        String msg = tpl
                .replace("{user_mention}", member.getAsMention())
                .replace("{user}", member.getEffectiveName())
                .replace("{username}", member.getUser().getName())
                .replace("{player}", playerName == null ? member.getEffectiveName() : playerName)
                .replace("{level}", String.valueOf(newLevel));
        ch.sendMessage(msg).queue(ok -> {}, err -> {});
    }

    private void announceWithoutMember(Guild guild, String discordId, String playerName, int newLevel, LevelConfig cfg) {
        String channelId = cfg.levelupChannelId();
        if (channelId == null || channelId.isBlank()) return;
        TextChannel ch = guild.getTextChannelById(channelId);
        if (ch == null) return;
        String tpl = (cfg.levelupMessageTemplate() == null || cfg.levelupMessageTemplate().isBlank())
                ? "GG {user_mention}, you reached level **{level}**!"
                : cfg.levelupMessageTemplate();
        String msg = tpl
                .replace("{user_mention}", "<@" + discordId + ">")
                .replace("{user}", playerName == null ? discordId : playerName)
                .replace("{username}", playerName == null ? discordId : playerName)
                .replace("{player}", playerName == null ? discordId : playerName)
                .replace("{level}", String.valueOf(newLevel));
        ch.sendMessage(msg).queue(ok -> {}, err -> {});
    }
}
