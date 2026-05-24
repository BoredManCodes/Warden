package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.levels.LevelConfig;
import io.warden.levels.LevelRewardDao;
import io.warden.levels.LevelService;

import java.util.List;

public final class DashLevelsHandlers {

    private final LevelService levels;
    private final GuildLookup lookup;

    public DashLevelsHandlers(LevelService levels, GuildLookup lookup) {
        this.levels = levels;
        this.lookup = lookup;
    }

    public void page(Context ctx) throws Exception {
        LevelConfig cfg = levels.configDao().get();
        var rewards = levels.rewardDao().listRewards();
        var multipliers = levels.rewardDao().listMultipliers();
        var top = levels.userDao().top(20);
        var roleOpts = lookup.roles();
        var channelOpts = lookup.textChannels();

        StringBuilder h = new StringBuilder(16384);
        h.append(Layout.head("Levels · Warden", "levels", ctx));
        h.append("<h1 class=\"h3 mb-3\">Leveling and XP</h1>");

        h.append("<form method=post action=\"/dash/levels\" class=\"vstack gap-3\">");
        h.append(checkbox("enabled", "Enable XP system", cfg.enabled()));
        h.append("<div class=\"row g-3\">");
        h.append("<div class=col-md-3><label class=form-label>Min XP per message</label>")
                .append("<input class=form-control name=xp_per_message_min type=number value=\"").append(cfg.xpPerMessageMin()).append("\"></div>");
        h.append("<div class=col-md-3><label class=form-label>Max XP per message</label>")
                .append("<input class=form-control name=xp_per_message_max type=number value=\"").append(cfg.xpPerMessageMax()).append("\"></div>");
        h.append("<div class=col-md-3><label class=form-label>Cooldown (seconds)</label>")
                .append("<input class=form-control name=cooldown_seconds type=number value=\"").append(cfg.cooldownSeconds()).append("\"></div>");
        h.append("<div class=col-md-3>").append(checkbox("leaderboard_public", "Public leaderboard", cfg.leaderboardPublic())).append("</div>");
        h.append("<div class=col-md-3>").append(checkbox("mc_xp_enabled",
                        "Award XP for Minecraft chat",
                        cfg.mcXpEnabled()))
                .append("<div class=\"form-text small\">Requires DiscordSRV. Linked players earn XP from in-game chat using the same cooldown as Discord.</div></div>");
        h.append("</div>");

        h.append("<div class=\"row g-3\">");
        h.append("<div class=col-md-3>").append(checkbox("levelup_announce", "Announce level-ups", cfg.levelupAnnounce())).append("</div>");
        h.append("<div class=col-md-3>")
                .append(GuildLookup.selectField("levelup_channel_id", "Announce channel",
                        GuildLookup.withDefaults(channelOpts, cfg.levelupChannelId()), cfg.levelupChannelId(),
                        "Where to post level-up messages. Leave unset to announce in the same channel where the user levelled up."))
                .append("</div>");
        h.append("<div class=col-md-6><label class=form-label>Level-up message template")
                .append(Layout.infoIcon("Placeholders: {user} mention, {level} new level, {xp} total XP."))
                .append("</label>")
                .append("<input class=form-control name=levelup_message_template value=\"")
                .append(Layout.escape(cfg.levelupMessageTemplate())).append("\"></div>");
        h.append("</div>");

        h.append("<div class=\"row g-3\">");
        h.append("<div class=col-md-6>")
                .append(GuildLookup.multiCheckField("no_xp_role_ids", "No-XP roles",
                        roleOpts, cfg.noXpRoleIds(),
                        "Members with any of these roles earn no XP."))
                .append("</div>");
        h.append("<div class=col-md-6>")
                .append(GuildLookup.multiCheckField("no_xp_channel_ids", "No-XP channels",
                        channelOpts, cfg.noXpChannelIds(),
                        "Messages sent in these channels never grant XP."))
                .append("</div>");
        h.append("</div>");

        h.append("<div class=\"row g-3\">");
        h.append("<div class=col-md-3><label class=form-label>Rank card accent</label>")
                .append("<input class=form-control type=color name=rank_card_accent value=\"").append(Layout.escape(cfg.rankCardAccent())).append("\"></div>");
        h.append("<div class=col-md-9><label class=form-label>Rank card background URL</label>")
                .append("<input class=form-control name=rank_card_background value=\"").append(Layout.escape(cfg.rankCardBackground())).append("\"></div>");
        h.append("</div>");
        h.append("<div><button class=\"btn btn-primary\">Save</button></div>");
        h.append("</form>");

        // Role rewards
        h.append("<hr class=\"my-4\">");
        h.append("<h2 class=\"h5\">Role rewards</h2>");
        h.append("<p class=\"text-secondary\">Assign a role when a member reaches a given level. Stack = keep lower-level roles when earning new ones.</p>");
        h.append("<table class=\"table table-sm align-middle\"><thead><tr><th>Level</th><th>Role</th><th>Stack?</th><th></th></tr></thead><tbody>");
        for (LevelRewardDao.Reward r : rewards) {
            h.append("<tr><td>").append(r.level()).append("</td><td>").append(roleLabel(roleOpts, r.roleId())).append("</td>")
                    .append("<td>").append(r.stack() ? "yes" : "no").append("</td>")
                    .append("<td><form method=post action=\"/dash/levels/rewards/delete\" class=m-0>")
                    .append("<input type=hidden name=level value=\"").append(r.level()).append("\">")
                    .append("<input type=hidden name=role_id value=\"").append(Layout.escape(r.roleId())).append("\">")
                    .append("<button class=\"btn btn-sm btn-outline-danger\">Remove</button></form></td></tr>");
        }
        h.append("</tbody></table>");
        h.append("<form method=post action=\"/dash/levels/rewards/add\" class=\"row g-2 align-items-end\">");
        h.append("<div class=col-md-2><label class=form-label mb-1>Level</label><input class=form-control name=level type=number min=1 required></div>");
        h.append("<div class=col-md-6><label class=form-label mb-1>Role</label>")
                .append(GuildLookup.selectInline("role_id", roleOpts, null, "form-select", "(select role)"))
                .append("</div>");
        h.append("<div class=col-md-2><div class=\"form-check form-switch mt-2\"><input class=form-check-input type=checkbox role=switch id=add-stack name=stack value=on><label class=form-check-label for=add-stack>Stack")
                .append(Layout.infoIcon("If on, lower-level reward roles stay when a member earns a higher one. Off = swap out the previous reward role."))
                .append("</label></div></div>");
        h.append("<div class=col-md-2><button class=\"btn btn-primary w-100\">Add reward</button></div>");
        h.append("</form>");

        // Multipliers
        h.append("<hr class=\"my-4\">");
        h.append("<h2 class=\"h5\">XP multipliers</h2>");
        h.append("<p class=\"text-secondary\">Boost XP gained in specific roles or channels (100 = baseline, 200 = double).</p>");
        h.append("<table class=\"table table-sm align-middle\"><thead><tr><th>Kind</th><th>Target</th><th>Multiplier (%)</th><th></th></tr></thead><tbody>");
        for (LevelRewardDao.Multiplier m : multipliers) {
            String targetLabel = "channel".equalsIgnoreCase(m.kind())
                    ? channelLabel(channelOpts, m.targetId())
                    : roleLabel(roleOpts, m.targetId());
            h.append("<tr><td>").append(m.kind()).append("</td><td>").append(targetLabel).append("</td>")
                    .append("<td>").append(m.multiplier()).append("</td>")
                    .append("<td><form method=post action=\"/dash/levels/multipliers/delete\" class=m-0>")
                    .append("<input type=hidden name=kind value=\"").append(m.kind()).append("\">")
                    .append("<input type=hidden name=target_id value=\"").append(Layout.escape(m.targetId())).append("\">")
                    .append("<button class=\"btn btn-sm btn-outline-danger\">Remove</button></form></td></tr>");
        }
        h.append("</tbody></table>");
        h.append("<form method=post action=\"/dash/levels/multipliers/add\" class=\"row g-2 align-items-end mult-form\">");
        h.append("<div class=col-md-2><label class=form-label mb-1>Kind</label>")
                .append("<select name=kind class=\"form-select mult-kind\">")
                .append("<option value=role>role</option>")
                .append("<option value=channel>channel</option>")
                .append("</select></div>");
        h.append("<div class=col-md-6 mult-target-role><label class=form-label mb-1>Role</label>")
                .append(GuildLookup.selectInline("target_id_role", roleOpts, null, "form-select", "(select role)"))
                .append("</div>");
        h.append("<div class=col-md-6 mult-target-channel style=\"display:none\"><label class=form-label mb-1>Channel</label>")
                .append(GuildLookup.selectInline("target_id_channel", channelOpts, null, "form-select", "(select channel)"))
                .append("</div>");
        h.append("<div class=col-md-2><label class=form-label mb-1>Multiplier %")
                .append(Layout.infoIcon("100 = baseline (no change), 150 = +50%, 200 = double XP."))
                .append("</label><input class=form-control name=multiplier type=number min=100 value=\"150\"></div>");
        h.append("<div class=col-md-2><button class=\"btn btn-primary w-100\">Add multiplier</button></div>");
        h.append("</form>");
        h.append("<script>(function(){")
                .append("document.querySelectorAll('.mult-form').forEach(function(f){")
                .append("var kind=f.querySelector('.mult-kind');")
                .append("var roleBox=f.querySelector('.mult-target-role');")
                .append("var chBox=f.querySelector('.mult-target-channel');")
                .append("function sync(){var k=kind.value;")
                .append("roleBox.style.display=k==='role'?'':'none';")
                .append("chBox.style.display=k==='channel'?'':'none';}")
                .append("kind.addEventListener('change',sync);sync();")
                .append("});})();</script>");

        // Leaderboard preview
        h.append("<hr class=\"my-4\"><h2 class=\"h5\">Top 20</h2>");
        h.append("<table class=\"table table-sm\"><thead><tr><th>#</th><th>User</th><th>Level</th><th>XP</th><th>Messages</th></tr></thead><tbody>");
        int rank = 1;
        for (var u : top) {
            String name = lookup.userName(u.discordId()).orElseGet(u::discordId);
            h.append("<tr><td>").append(rank).append("</td>")
                    .append("<td>").append(Layout.escape(name)).append("</td>")
                    .append("<td>").append(u.level()).append("</td>")
                    .append("<td>").append(u.xp()).append("</td>")
                    .append("<td>").append(u.messages()).append("</td></tr>");
            rank++;
        }
        h.append("</tbody></table>");

        h.append("<form method=post action=\"/dash/levels/reset\" class=\"mt-3\" ")
                .append("data-confirm=\"Reset XP for every member? This wipes the entire leaderboard.\" ")
                .append("data-confirm-kind=\"danger\" data-confirm-ok=\"Reset leaderboard\">")
                .append("<button class=\"btn btn-sm btn-outline-danger\">Reset entire leaderboard</button></form>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void save(Context ctx) throws Exception {
        LevelConfig prev = levels.configDao().get();
        LevelConfig next = new LevelConfig(
                bool(ctx, "enabled"),
                intOr(ctx, "xp_per_message_min", prev.xpPerMessageMin()),
                intOr(ctx, "xp_per_message_max", prev.xpPerMessageMax()),
                intOr(ctx, "cooldown_seconds", prev.cooldownSeconds()),
                bool(ctx, "levelup_announce"),
                str(ctx, "levelup_channel_id"),
                str(ctx, "levelup_message_template"),
                bool(ctx, "leaderboard_public"),
                multiVals(ctx, "no_xp_role_ids"),
                multiVals(ctx, "no_xp_channel_ids"),
                str(ctx, "rank_card_accent"),
                str(ctx, "rank_card_background"),
                bool(ctx, "mc_xp_enabled")
        );
        levels.configDao().save(next);
        ctx.redirect("/dash/levels");
    }

    public void addReward(Context ctx) throws Exception {
        int level = intOr(ctx, "level", 0);
        String role = str(ctx, "role_id");
        boolean stack = bool(ctx, "stack");
        if (level > 0 && !role.isBlank()) {
            levels.rewardDao().addReward(level, role, stack);
        }
        ctx.redirect("/dash/levels");
    }

    public void deleteReward(Context ctx) throws Exception {
        levels.rewardDao().deleteReward(intOr(ctx, "level", 0), str(ctx, "role_id"));
        ctx.redirect("/dash/levels");
    }

    public void addMultiplier(Context ctx) throws Exception {
        String kind = str(ctx, "kind");
        String target = "channel".equalsIgnoreCase(kind)
                ? str(ctx, "target_id_channel")
                : str(ctx, "target_id_role");
        int mult = intOr(ctx, "multiplier", 100);
        if (!kind.isBlank() && !target.isBlank()) {
            levels.rewardDao().setMultiplier(kind, target, mult);
        }
        ctx.redirect("/dash/levels");
    }

    public void deleteMultiplier(Context ctx) throws Exception {
        levels.rewardDao().deleteMultiplier(str(ctx, "kind"), str(ctx, "target_id"));
        ctx.redirect("/dash/levels");
    }

    public void resetAll(Context ctx) throws Exception {
        levels.userDao().resetAll();
        ctx.redirect("/dash/levels");
    }

    private static String checkbox(String name, String label, boolean checked) {
        return "<div class=\"form-check form-switch\">"
                + "<input class=form-check-input type=checkbox role=switch id=cb-" + name
                + " name=" + name + " value=on" + (checked ? " checked" : "") + ">"
                + "<label class=\"form-check-label\" for=cb-" + name + ">" + Layout.escape(label) + "</label>"
                + "</div>";
    }

    private static String roleLabel(java.util.List<GuildLookup.Option> opts, String id) {
        if (id == null || id.isBlank()) return "<span class=text-secondary>(unset)</span>";
        for (GuildLookup.Option o : opts) {
            if (o.id().equals(id)) {
                return "<span>@" + Layout.escape(o.name()) + "</span>";
            }
        }
        return "<span class=text-secondary>(unknown role)</span>";
    }

    private static String channelLabel(java.util.List<GuildLookup.Option> opts, String id) {
        if (id == null || id.isBlank()) return "<span class=text-secondary>(unset)</span>";
        for (GuildLookup.Option o : opts) {
            if (o.id().equals(id)) {
                return "<span>" + Layout.escape(o.name()) + "</span>";
            }
        }
        return "<span class=text-secondary>(unknown channel)</span>";
    }

    private static List<String> multiVals(Context ctx, String key) {
        var raw = ctx.formParams(key);
        if (raw == null || raw.isEmpty()) return List.of();
        java.util.List<String> out = new java.util.ArrayList<>(raw.size());
        for (String s : raw) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static boolean bool(Context ctx, String key) {
        var vals = ctx.formParams(key);
        if (vals == null || vals.isEmpty()) return false;
        String last = vals.get(vals.size() - 1);
        return "on".equalsIgnoreCase(last) || "true".equalsIgnoreCase(last) || "1".equals(last);
    }

    private static String str(Context ctx, String key) {
        String v = ctx.formParam(key);
        return v == null ? "" : v;
    }

    private static int intOr(Context ctx, String key, int fallback) {
        try { return Integer.parseInt(ctx.formParam(key)); }
        catch (Exception e) { return fallback; }
    }
}
