package io.warden.onboarding;

import io.warden.audit.AuditService;
import io.warden.data.dao.SettingsDao;
import io.warden.data.dao.UserDao;
import io.warden.discord.DiscordService;
import io.warden.onboarding.model.Settings;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-click "let everyone already in the server through the gate" helper. Marks
 * every non-bot member as APPROVED in the users table, swaps the full role on
 * and the gated role off, and writes a single audit summary. Skips members who
 * are already approved so a repeat run is cheap and idempotent.
 *
 * Does NOT create an applications row, run the LLM, send approve DMs, or post
 * to the welcome channel - that would be noisy across an entire existing
 * member list. The intent is "open the gate", not "celebrate everyone".
 */
public final class BulkOnboardService {

    private final UserDao userDao;
    private final SettingsDao settingsDao;
    private final AuditService audit;
    private final DiscordService discord;
    private final String guildId;
    private final Logger log;

    public BulkOnboardService(UserDao userDao, SettingsDao settingsDao,
                              AuditService audit, DiscordService discord,
                              String guildId, Logger log) {
        this.userDao = userDao;
        this.settingsDao = settingsDao;
        this.audit = audit;
        this.discord = discord;
        this.guildId = guildId;
        this.log = log;
    }

    public record Result(int total, int approved, int alreadyApproved, int bots, int failed) {}

    /**
     * Run the bulk-onboard across every cached guild member. Requires the JDA
     * Members intent (Warden already enables it). When JDA isn't connected,
     * returns a zero-count Result; the caller surfaces the explanation.
     */
    public Result run(String actor) {
        JDA jda = discord == null ? null : discord.jda();
        if (jda == null) {
            log.warning("bulk-onboard: JDA not connected; nothing to do");
            return new Result(0, 0, 0, 0, 0);
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            log.warning("bulk-onboard: guild " + guildId + " not visible to bot");
            return new Result(0, 0, 0, 0, 0);
        }
        Settings settings;
        try { settings = settingsDao.get(); }
        catch (SQLException e) {
            log.log(Level.WARNING, "bulk-onboard: settings load failed", e);
            return new Result(0, 0, 0, 0, 0);
        }
        Role fullRole = settings.fullRoleId().isBlank() ? null : guild.getRoleById(settings.fullRoleId());
        Role gatedRole = settings.gatedRoleId().isBlank() ? null : guild.getRoleById(settings.gatedRoleId());

        int total = 0, approved = 0, alreadyApproved = 0, bots = 0, failed = 0;
        for (Member member : guild.getMembers()) {
            total++;
            if (member.getUser().isBot()) { bots++; continue; }
            String id = member.getId();
            String username = member.getUser().getName();
            try {
                var existing = userDao.findByDiscordId(id).orElse(null);
                if (existing != null && existing.state() == OnboardingState.APPROVED) {
                    alreadyApproved++;
                    ensureRoles(guild, member, fullRole, gatedRole);
                    continue;
                }
                userDao.upsert(id, username);
                userDao.setState(id, OnboardingState.APPROVED);
                ensureRoles(guild, member, fullRole, gatedRole);
                approved++;
            } catch (Exception e) {
                failed++;
                log.log(Level.WARNING, "bulk-onboard: failed for " + id, e);
            }
        }

        try {
            audit.write(actor == null || actor.isBlank() ? "web-mod" : actor,
                    "bulk_onboard", null, Map.of(
                            "total", total,
                            "approved", approved,
                            "alreadyApproved", alreadyApproved,
                            "bots", bots,
                            "failed", failed,
                            "fullRoleId", settings.fullRoleId(),
                            "gatedRoleId", settings.gatedRoleId()));
        } catch (Exception e) {
            log.log(Level.WARNING, "bulk-onboard: audit write failed", e);
        }
        return new Result(total, approved, alreadyApproved, bots, failed);
    }

    private void ensureRoles(Guild guild, Member member, Role fullRole, Role gatedRole) {
        if (fullRole != null && !member.getRoles().contains(fullRole)) {
            guild.addRoleToMember(member, fullRole).reason("Warden: bulk onboard")
                    .queue(ok -> {}, err -> {});
        }
        if (gatedRole != null && member.getRoles().contains(gatedRole)) {
            guild.removeRoleFromMember(member, gatedRole).reason("Warden: bulk onboard")
                    .queue(ok -> {}, err -> {});
        }
    }

    /** True when the guild full-role is configured. Without it bulk onboard cannot grant access. */
    public boolean fullRoleConfigured() {
        try { return !settingsDao.get().fullRoleId().isBlank(); }
        catch (Exception e) { return false; }
    }
}
