package io.warden.alerts;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/** Mirror of {@link AlertBukkitListener} for the Discord side. */
public final class AlertDiscordListener extends ListenerAdapter {

    private final AlertService alerts;
    private final String guildId;

    public AlertDiscordListener(AlertService alerts, String guildId) {
        this.alerts = alerts;
        this.guildId = guildId;
    }

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        if (guildId != null && !guildId.isBlank() && !event.getGuild().getId().equals(guildId)) return;
        Member m = event.getMember();
        User u = m.getUser();
        AlertContext ctx = new AlertContext()
                .set("user", displayOrName(m, u))
                .set("user_id", u.getId())
                .set("user_mention", "<@" + u.getId() + ">")
                .set("user_tag", u.getName());
        alerts.fire(AlertEvent.DISCORD_MEMBER_JOIN, ctx);
    }

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        if (guildId != null && !guildId.isBlank() && !event.getGuild().getId().equals(guildId)) return;
        User u = event.getUser();
        AlertContext ctx = new AlertContext()
                .set("user", u.getEffectiveName())
                .set("user_id", u.getId())
                .set("user_tag", u.getName());
        alerts.fire(AlertEvent.DISCORD_MEMBER_LEAVE, ctx);
    }

    private static String displayOrName(Member m, User u) {
        if (m != null) {
            String nick = m.getEffectiveName();
            if (nick != null && !nick.isBlank()) return nick;
        }
        return u.getEffectiveName();
    }
}
