package io.warden.levels;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class LevelListener extends ListenerAdapter {

    private final LevelService levels;
    private final Logger log;

    public LevelListener(LevelService levels, Logger log) {
        this.levels = levels;
        this.log = log;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        Member member = event.getMember();
        if (member == null) return;
        MessageChannel channel = event.getChannel();
        int newLevel = levels.onMessage(member, channel, event.getMessage().getContentRaw());
        if (newLevel < 0) return;

        LevelConfig cfg;
        try { cfg = levels.configDao().get(); } catch (Exception e) { return; }

        applyRoleRewards(member, newLevel);

        if (cfg.levelupAnnounce()) {
            String announceChannelId = cfg.levelupChannelId();
            MessageChannel announceCh = (announceChannelId != null && !announceChannelId.isBlank())
                    ? event.getGuild().getTextChannelById(announceChannelId)
                    : (TextChannel) channel;
            if (announceCh == null) announceCh = (TextChannel) channel;
            String msg = renderTemplate(cfg.levelupMessageTemplate(), member, newLevel);
            announceCh.sendMessage(msg).queue(ok -> {}, err -> {});
        }
    }

    private void applyRoleRewards(Member member, int level) {
        try {
            var rewards = levels.rewardDao().listRewards();
            for (var r : rewards) {
                if (r.level() <= level) {
                    Role role = member.getGuild().getRoleById(r.roleId());
                    if (role != null && !member.getRoles().contains(role)) {
                        member.getGuild().addRoleToMember(member, role).queue(ok -> {}, err -> {});
                    }
                    if (!r.stack()) {
                        // Strip lower-level reward roles when not stacking.
                        for (var other : rewards) {
                            if (other.level() < r.level()) {
                                Role o = member.getGuild().getRoleById(other.roleId());
                                if (o != null && member.getRoles().contains(o)) {
                                    member.getGuild().removeRoleFromMember(member, o).queue(ok -> {}, err -> {});
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "level reward apply failed", e);
        }
    }

    private static String renderTemplate(String tpl, Member member, int level) {
        String base = (tpl == null || tpl.isBlank())
                ? "GG {user_mention}, you reached level **{level}**!" : tpl;
        return base
                .replace("{user_mention}", member.getAsMention())
                .replace("{user}", member.getEffectiveName())
                .replace("{username}", member.getUser().getName())
                .replace("{level}", String.valueOf(level));
    }
}
