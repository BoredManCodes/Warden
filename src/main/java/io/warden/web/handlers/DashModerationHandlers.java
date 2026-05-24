package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.moderation.AutoModService;
import io.warden.moderation.AutomodConfig;
import io.warden.moderation.AutomodConfigDao;
import io.warden.moderation.ModerationService;
import io.warden.moderation.RaidProtectionDao;
import io.warden.moderation.WarningDao;
import io.warden.web.auth.AuditActor;

import java.util.ArrayList;
import java.util.List;

/**
 * /dash/moderation - one page with three tabs: Auto-mod, Warnings, Raid protection.
 * All settings live in their own DB rows; warnings are immutable history with a
 * "clear" action that nullifies a single row.
 */
public final class DashModerationHandlers {

    private final AutoModService autoMod;
    private final WarningDao warnings;
    private final RaidProtectionDao raidDao;
    private final ModerationService moderation;
    private final GuildLookup lookup;

    public DashModerationHandlers(AutoModService autoMod, WarningDao warnings,
                                  RaidProtectionDao raidDao, ModerationService moderation,
                                  GuildLookup lookup) {
        this.autoMod = autoMod;
        this.warnings = warnings;
        this.raidDao = raidDao;
        this.moderation = moderation;
        this.lookup = lookup;
    }

    private static final java.util.Map<String, String> AUTOMOD_ACTION_TIPS = java.util.Map.of(
            "delete",      "Delete the offending message silently.",
            "delete_warn", "Delete the message and add a warning to the member's record.",
            "timeout",     "Delete the message and timeout (mute) the member for the configured duration.",
            "kick",        "Delete the message and kick the member from the server.",
            "ban",         "Delete the message and ban the member from the server."
    );

    private static final java.util.Map<String, String> RAID_ACTION_TIPS = java.util.Map.of(
            "kick",     "Kick joiners during a raid burst.",
            "ban",      "Ban joiners during a raid burst.",
            "log_only", "Don't act on joiners; only log to the audit channel so mods can react manually."
    );

    private static final java.util.Map<String, String> AUTOMOD_FILTER_TIPS = java.util.Map.of(
            "spam_enabled",         "Flag members who send a lot of messages in a short window.",
            "caps_enabled",         "Flag messages that are mostly uppercase (after a minimum length).",
            "mass_mention_enabled", "Flag messages that @-mention many users/roles at once.",
            "emoji_flood_enabled",  "Flag messages that are mostly emojis.",
            "zalgo_enabled",        "Flag messages with stacks of combining marks (zalgo text).",
            "invites_enabled",      "Block messages containing Discord invite links."
    );

    public void page(Context ctx) throws Exception {
        AutomodConfig cfg = autoMod.configDao().get();
        RaidProtectionDao.Config rp = raidDao.get();
        var recentWarnings = warnings.listRecent(50);
        var roleOpts = lookup.roles();
        var channelOpts = lookup.textChannels();

        String tab = ctx.queryParam("tab");
        if (tab == null || tab.isBlank()) tab = "automod";

        StringBuilder h = new StringBuilder(16384);
        h.append(Layout.head("Moderation · Warden", "moderation", ctx));
        h.append("<h1 class=\"h3 mb-3\">Moderation</h1>");

        h.append("<div class=\"cfg-tabs\">");
        h.append("<input class=cfg-tabradio name=tab type=radio id=mod-automod ").append(tab.equals("automod") ? "checked" : "").append(">");
        h.append("<input class=cfg-tabradio name=tab type=radio id=mod-raid ").append(tab.equals("raid") ? "checked" : "").append(">");
        h.append("<input class=cfg-tabradio name=tab type=radio id=mod-warn ").append(tab.equals("warn") ? "checked" : "").append(">");
        h.append("<div class=cfg-tab-bar>");
        h.append("<label for=mod-automod class=cfg-tablabel>Auto-moderation</label>");
        h.append("<label for=mod-raid class=cfg-tablabel>Raid protection</label>");
        h.append("<label for=mod-warn class=cfg-tablabel>Warnings (").append(recentWarnings.size()).append(")</label>");
        h.append("</div>");

        h.append("<div class=cfg-tab-panels>");

        // Auto-mod tab
        h.append("<div class=cfg-tab-panel data-tab=automod>");
        h.append("<form method=post action=\"/dash/moderation/automod\" class=\"vstack gap-3\">");
        h.append(checkbox("enabled", "Enable auto-moderation", cfg.enabled(), null));
        h.append(filterRow("spam_enabled", "Spam (rate-limit)", cfg.spamEnabled(),
                "spam_threshold", cfg.spamThreshold(), "messages",
                "spam_window_seconds", cfg.spamWindowSeconds(), "seconds",
                AUTOMOD_FILTER_TIPS.get("spam_enabled")));
        h.append(filterRow("caps_enabled", "Excessive caps", cfg.capsEnabled(),
                "caps_min_length", cfg.capsMinLength(), "min message length",
                "caps_percent", cfg.capsPercent(), "% uppercase",
                AUTOMOD_FILTER_TIPS.get("caps_enabled")));
        h.append(filterRow("mass_mention_enabled", "Mass mentions", cfg.massMentionEnabled(),
                "mass_mention_threshold", cfg.massMentionThreshold(), "mentions max", null, 0, null,
                AUTOMOD_FILTER_TIPS.get("mass_mention_enabled")));
        h.append(filterRow("emoji_flood_enabled", "Emoji flood", cfg.emojiFloodEnabled(),
                "emoji_flood_threshold", cfg.emojiFloodThreshold(), "emojis max", null, 0, null,
                AUTOMOD_FILTER_TIPS.get("emoji_flood_enabled")));
        h.append(filterRow("zalgo_enabled", "Zalgo / combining marks", cfg.zalgoEnabled(),
                null, 0, null, null, 0, null,
                AUTOMOD_FILTER_TIPS.get("zalgo_enabled")));
        h.append(filterRow("invites_enabled", "Block Discord invites", cfg.invitesEnabled(),
                null, 0, null, null, 0, null,
                AUTOMOD_FILTER_TIPS.get("invites_enabled")));

        h.append("<div class=\"row g-3\">");
        h.append("<div class=col-md-6>").append(checkbox("bad_words_enabled", "Banned words filter", cfg.badWordsEnabled(), null));
        h.append("<label class=form-label d-flex align-items-center justify-content-between\">")
                .append("<span>Banned words (comma or newline-separated)</span>")
                .append("<button type=\"button\" id=\"ai-words-toggle\" class=\"btn btn-sm btn-outline-secondary\">")
                .append("<i class=\"bi bi-stars me-1\"></i>Suggest with AI</button>")
                .append("</label>");
        h.append("<div id=\"ai-words-panel\" class=\"border rounded p-2 mb-2 bg-body-tertiary\" style=\"display:none\">")
                .append("<div class=\"input-group input-group-sm\">")
                .append("<input id=\"ai-words-brief\" class=\"form-control\" type=\"text\" maxlength=\"400\" ")
                .append("placeholder=\"e.g. slurs, common spam phrases, NSFW link bait, leetspeak variants\">")
                .append("<button type=\"button\" id=\"ai-words-go\" class=\"btn btn-primary\">")
                .append("<i class=\"bi bi-magic me-1\"></i>Generate</button>")
                .append("</div>")
                .append("<div class=\"form-check form-check-inline small mt-1\">")
                .append("<input class=\"form-check-input\" type=\"checkbox\" id=\"ai-words-replace\">")
                .append("<label class=\"form-check-label\" for=\"ai-words-replace\">Replace existing list (otherwise append)</label>")
                .append("</div>")
                .append("<div id=\"ai-words-status\" class=\"small text-secondary mt-1\"></div>")
                .append("</div>");
        h.append("<textarea id=ai-bad-words name=bad_words_list rows=3 class=form-control>").append(Layout.escape(cfg.badWordsList())).append("</textarea>");
        h.append("</div>");
        h.append("<div class=col-md-6>").append(checkbox("links_enabled", "Block links (allowlist below)", cfg.linksEnabled(), null));
        h.append("<label class=form-label>Allowed link domains (comma or newline-separated)</label>");
        h.append("<textarea name=links_allowlist rows=3 class=form-control>").append(Layout.escape(cfg.linksAllowlist())).append("</textarea>");
        h.append("</div></div>");

        h.append("<div class=\"row g-3\">");
        h.append("<div class=col-md-6><label class=form-label>Default action")
                .append(Layout.infoIcon("What auto-mod does when a filter is triggered.")).append("</label>");
        h.append("<select name=action_default class=form-select>");
        for (String a : List.of("delete", "delete_warn", "timeout", "kick", "ban")) {
            String tip = AUTOMOD_ACTION_TIPS.get(a);
            h.append("<option value=\"").append(a).append("\"")
                    .append(a.equals(cfg.actionDefault()) ? " selected" : "")
                    .append(tip == null ? "" : " title=\"" + Layout.escape(tip) + "\"")
                    .append(">").append(a).append("</option>");
        }
        h.append("</select></div>");
        h.append("<div class=col-md-6>")
                .append(GuildLookup.selectField("log_channel_id", "Log channel",
                        GuildLookup.withDefaults(channelOpts, cfg.logChannelId()), cfg.logChannelId(),
                        "Channel where auto-mod actions are logged. Leave unset to skip logging."))
                .append("</div>");
        h.append("</div>");

        h.append("<div class=\"row g-3\">");
        h.append("<div class=col-md-6>")
                .append(GuildLookup.multiCheckField("exempt_role_ids", "Exempt roles",
                        roleOpts, cfg.exemptRoleIds(),
                        "Members with any of these roles are skipped by every auto-mod filter."))
                .append("</div>");
        h.append("<div class=col-md-6>")
                .append(GuildLookup.multiCheckField("exempt_channel_ids", "Exempt channels",
                        channelOpts, cfg.exemptChannelIds(),
                        "Auto-mod never fires in these channels."))
                .append("</div>");
        h.append("</div>");

        h.append(warnThresholdsEditor(cfg.warnThresholds()));

        h.append("<div><button class=\"btn btn-primary\">Save auto-mod</button></div>");
        h.append("</form>");
        h.append("</div>");

        // Raid protection tab
        h.append("<div class=cfg-tab-panel data-tab=raid>");
        h.append("<form method=post action=\"/dash/moderation/raid\" class=\"vstack gap-3\">");
        h.append(checkbox("enabled", "Enable raid protection", rp.enabled(), null));
        h.append("<div class=\"row g-3\">");
        h.append("<div class=col-md-4><label class=form-label>Joins to trigger</label>")
                .append("<input class=form-control name=joins_threshold type=number min=2 value=\"").append(rp.joinsThreshold()).append("\"></div>");
        h.append("<div class=col-md-4><label class=form-label>Window (seconds)</label>")
                .append("<input class=form-control name=joins_window_seconds type=number min=1 value=\"").append(rp.joinsWindowSeconds()).append("\"></div>");
        h.append("<div class=col-md-4><label class=form-label>Min account age (days)</label>")
                .append("<input class=form-control name=account_age_min_days type=number min=0 value=\"").append(rp.accountAgeMinDays()).append("\"></div>");
        h.append("</div>");
        h.append("<div class=\"row g-3\">");
        h.append("<div class=col-md-4><label class=form-label>Lockdown action")
                .append(Layout.infoIcon("What raid protection does to joiners while a raid burst is detected.")).append("</label>")
                .append("<select name=lockdown_action class=form-select>");
        for (String a : List.of("kick", "ban", "log_only")) {
            String tip = RAID_ACTION_TIPS.get(a);
            h.append("<option value=\"").append(a).append("\"")
                    .append(a.equalsIgnoreCase(rp.lockdownAction()) ? " selected" : "")
                    .append(tip == null ? "" : " title=\"" + Layout.escape(tip) + "\"")
                    .append(">").append(a).append("</option>");
        }
        h.append("</select></div>");
        h.append("<div class=col-md-4><label class=form-label>Auto-disable (minutes)")
                .append(Layout.infoIcon("How long lockdown stays on before auto-clearing. New joiners after this point are treated normally."))
                .append("</label>")
                .append("<input class=form-control name=auto_disable_minutes type=number min=1 value=\"").append(rp.autoDisableMinutes()).append("\"></div>");
        h.append("<div class=col-md-4>")
                .append(GuildLookup.selectField("log_channel_id", "Log channel",
                        GuildLookup.withDefaults(channelOpts, rp.logChannelId()), rp.logChannelId(),
                        "Channel where raid bursts and joiner actions are logged."))
                .append("</div>");
        h.append("</div>");
        if (rp.lockdownUntil() != null && rp.lockdownUntil() > System.currentTimeMillis()) {
            h.append("<div class=\"alert alert-warning\">Lockdown active until <code>")
                    .append(rp.lockdownUntil() / 1000).append("</code> (epoch). ")
                    .append("<button formaction=\"/dash/moderation/raid/clear\" class=\"btn btn-sm btn-outline-warning ms-2\">Clear lockdown</button></div>");
        }
        h.append("<div><button class=\"btn btn-primary\">Save raid protection</button></div>");
        h.append("</form>");
        h.append("</div>");

        // Warnings tab
        h.append("<div class=cfg-tab-panel data-tab=warn>");
        h.append("<p class=\"text-secondary\">Recent warnings. Use the in-Discord /warn, /unwarn, /warnings commands to manage.</p>");
        h.append("<div class=\"table-responsive\"><table class=\"table table-sm align-middle\">");
        h.append("<thead><tr><th>#</th><th>User</th><th>By</th><th>Reason</th><th>When</th><th>Status</th><th></th></tr></thead><tbody>");
        for (var w : recentWarnings) {
            String userLabel = lookup.userName(w.discordId()).orElseGet(w::discordId);
            String modLabel = lookup.userName(w.moderatorId()).orElseGet(w::moderatorId);
            h.append("<tr>")
                    .append("<td>").append(w.id()).append("</td>")
                    .append("<td>").append(Layout.escape(userLabel)).append("</td>")
                    .append("<td>").append(Layout.escape(modLabel)).append("</td>")
                    .append("<td>").append(Layout.escape(w.reason())).append("</td>")
                    .append("<td><time>").append(w.createdAt() / 1000).append("</time></td>")
                    .append("<td>").append(w.clearedAt() == null ? "<span class=\"badge bg-warning-subtle text-warning-emphasis\">active</span>"
                            : "<span class=\"badge bg-secondary-subtle text-secondary-emphasis\">cleared</span>").append("</td>")
                    .append("<td>");
            if (w.clearedAt() == null) {
                h.append("<form method=post action=\"/dash/moderation/warn/").append(w.id())
                        .append("/clear\" class=m-0><button class=\"btn btn-sm btn-outline-secondary\">Clear</button></form>");
            }
            h.append("</td></tr>");
        }
        h.append("</tbody></table></div>");
        h.append("</div>");

        h.append("</div></div>");

        h.append("<script>(function(){")
                .append("var tog=document.getElementById('ai-words-toggle');")
                .append("var pane=document.getElementById('ai-words-panel');")
                .append("var go=document.getElementById('ai-words-go');")
                .append("var brIn=document.getElementById('ai-words-brief');")
                .append("var replaceEl=document.getElementById('ai-words-replace');")
                .append("var stat=document.getElementById('ai-words-status');")
                .append("var list=document.getElementById('ai-bad-words');")
                .append("if(tog){tog.addEventListener('click',function(){")
                .append("var open=pane.style.display!=='none';pane.style.display=open?'none':'';")
                .append("if(!open){setTimeout(function(){brIn&&brIn.focus();},0);}")
                .append("});}")
                .append("if(go){go.addEventListener('click',function(){")
                .append("var brief=(brIn.value||'').trim();")
                .append("if(!brief){stat.textContent='Describe what to ban first.';return;}")
                .append("go.disabled=true;stat.textContent='Asking the AI...';")
                .append("var b=new URLSearchParams();b.set('brief',brief);")
                .append("fetch('/dash/ai/automod-words',{method:'POST',credentials:'same-origin',")
                .append("headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b.toString()})")
                .append(".then(function(r){return r.json().catch(function(){return{ok:false,message:'HTTP '+r.status};});})")
                .append(".then(function(j){")
                .append("if(!j||!j.ok){stat.textContent='Failed: '+((j&&j.message)||'unknown');return;}")
                .append("var words=(j.words||[]).filter(function(w){return w&&String(w).trim().length>0;});")
                .append("if(!words.length){stat.textContent='AI returned no words.';return;}")
                .append("var existing=replaceEl&&replaceEl.checked?[]:(list.value||'').split(/[,\\n]+/).map(function(s){return s.trim();}).filter(Boolean);")
                .append("var seen={};var merged=[];")
                .append("existing.concat(words).forEach(function(w){var k=String(w).toLowerCase();if(!seen[k]){seen[k]=1;merged.push(w);}});")
                .append("list.value=merged.join('\\n');")
                .append("stat.textContent='Added '+words.length+' word(s). Review before saving.';")
                .append("})")
                .append(".catch(function(e){stat.textContent='Network error: '+(e.message||e);})")
                .append(".finally(function(){go.disabled=false;});")
                .append("});}")
                .append("})();</script>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void saveAutomod(Context ctx) throws Exception {
        AutomodConfig prev = autoMod.configDao().get();
        List<AutomodConfig.WarnThreshold> thresholds = parseThresholdRows(ctx);
        AutomodConfig next = new AutomodConfig(
                bool(ctx, "enabled"),
                bool(ctx, "spam_enabled"),
                intOr(ctx, "spam_threshold", prev.spamThreshold()),
                intOr(ctx, "spam_window_seconds", prev.spamWindowSeconds()),
                bool(ctx, "caps_enabled"),
                intOr(ctx, "caps_min_length", prev.capsMinLength()),
                intOr(ctx, "caps_percent", prev.capsPercent()),
                bool(ctx, "bad_words_enabled"),
                str(ctx, "bad_words_list"),
                bool(ctx, "links_enabled"),
                str(ctx, "links_allowlist"),
                bool(ctx, "invites_enabled"),
                bool(ctx, "mass_mention_enabled"),
                intOr(ctx, "mass_mention_threshold", prev.massMentionThreshold()),
                bool(ctx, "emoji_flood_enabled"),
                intOr(ctx, "emoji_flood_threshold", prev.emojiFloodThreshold()),
                bool(ctx, "zalgo_enabled"),
                str(ctx, "action_default"),
                multiVals(ctx, "exempt_role_ids"),
                multiVals(ctx, "exempt_channel_ids"),
                str(ctx, "log_channel_id"),
                thresholds
        );
        autoMod.configDao().save(next);
        ctx.redirect("/dash/moderation?tab=automod");
    }

    public void saveRaid(Context ctx) throws Exception {
        RaidProtectionDao.Config prev = raidDao.get();
        RaidProtectionDao.Config next = new RaidProtectionDao.Config(
                bool(ctx, "enabled"),
                intOr(ctx, "joins_threshold", prev.joinsThreshold()),
                intOr(ctx, "joins_window_seconds", prev.joinsWindowSeconds()),
                intOr(ctx, "account_age_min_days", prev.accountAgeMinDays()),
                str(ctx, "lockdown_action"),
                prev.lockdownUntil(),
                str(ctx, "log_channel_id"),
                intOr(ctx, "auto_disable_minutes", prev.autoDisableMinutes())
        );
        raidDao.save(next);
        ctx.redirect("/dash/moderation?tab=raid");
    }

    public void clearLockdown(Context ctx) throws Exception {
        raidDao.setLockdownUntil(null);
        ctx.redirect("/dash/moderation?tab=raid");
    }

    public void clearWarning(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        warnings.clear(id);
        ctx.redirect("/dash/moderation?tab=warn");
    }

    /* ------------- helpers ------------- */

    private static final java.util.List<String> WARN_ACTIONS =
            java.util.List.of("mute", "timeout", "kick", "tempban", "ban");

    private static final java.util.Map<String, String> WARN_ACTION_TIPS = java.util.Map.of(
            "mute",    "Apply the gated/muted role for the duration below.",
            "timeout", "Native Discord timeout for the duration below.",
            "kick",    "Kick the member from the server. Duration is ignored.",
            "tempban", "Ban the member for the duration below, then auto-unban.",
            "ban",     "Permanently ban the member. Duration is ignored."
    );

    /** Actions that don't consume a duration field. */
    private static final java.util.Set<String> WARN_ACTIONS_NO_DURATION =
            java.util.Set.of("kick", "ban");

    /** Convert seconds back into the largest matching unit so reloads round-trip cleanly. */
    private record DurationDisplay(int value, String unit) {}
    private static DurationDisplay bestUnit(int seconds) {
        if (seconds <= 0) return new DurationDisplay(10, "m");
        if (seconds % 86400 == 0) return new DurationDisplay(seconds / 86400, "d");
        if (seconds % 3600  == 0) return new DurationDisplay(seconds / 3600,  "h");
        if (seconds % 60    == 0) return new DurationDisplay(seconds / 60,    "m");
        return new DurationDisplay(seconds, "s");
    }

    private static String warnThresholdsEditor(List<AutomodConfig.WarnThreshold> thresholds) {
        StringBuilder h = new StringBuilder();
        h.append("<div>");
        h.append("<label class=form-label>Warning thresholds")
                .append(Layout.infoIcon("Auto-escalation ladder. When a member's active warning count exactly matches the count on a row, the listed action fires. Drag rows to reorder; the count value (not row order) decides when each rule triggers."))
                .append("</label>");
        h.append("<div class=warn-thresh-list id=warn-thresh-list>");
        if (thresholds != null) {
            for (var t : thresholds) h.append(warnThresholdRow(t));
        }
        h.append("</div>");
        h.append("<button type=button class=\"btn btn-sm btn-outline-primary mt-1\" id=warn-thresh-add>")
                .append("<i class=\"bi bi-plus-lg me-1\"></i>Add threshold</button>");
        h.append("<template id=warn-thresh-template>").append(warnThresholdRow(null)).append("</template>");
        h.append(WARN_THRESH_ASSETS);
        h.append("</div>");
        return h.toString();
    }

    private static String warnThresholdRow(AutomodConfig.WarnThreshold t) {
        int count = t == null ? 3 : t.count();
        String action = t == null ? "mute" : (t.action() == null ? "" : t.action().toLowerCase(java.util.Locale.ROOT));
        DurationDisplay dur = bestUnit(t == null ? 0 : t.durationSeconds());

        StringBuilder s = new StringBuilder();
        s.append("<div class=\"warn-thresh-row\" draggable=true>");
        s.append("<span class=\"warn-thresh-handle\" title=\"Drag to reorder\" aria-label=\"drag to reorder\">&#8801;</span>");
        s.append("<span class=warn-thresh-when>At</span>");
        s.append("<input type=number min=1 name=warn_count class=\"form-control form-control-sm warn-thresh-count\" value=\"")
                .append(count).append("\">");
        s.append("<span class=warn-thresh-when>warnings</span>");
        s.append("<select name=warn_action class=\"form-select form-select-sm warn-thresh-action\">");
        for (String a : WARN_ACTIONS) {
            s.append("<option value=\"").append(a).append("\"")
                    .append(a.equals(action) ? " selected" : "")
                    .append(" title=\"").append(Layout.escape(WARN_ACTION_TIPS.getOrDefault(a, ""))).append("\">")
                    .append(a).append("</option>");
        }
        s.append("</select>");
        s.append("<span class=warn-thresh-for>for</span>");
        s.append("<input type=number min=0 name=warn_duration class=\"form-control form-control-sm warn-thresh-duration\" value=\"")
                .append(dur.value()).append("\">");
        s.append("<select name=warn_duration_unit class=\"form-select form-select-sm warn-thresh-unit\">");
        s.append("<option value=s").append(dur.unit().equals("s") ? " selected" : "").append(">seconds</option>");
        s.append("<option value=m").append(dur.unit().equals("m") ? " selected" : "").append(">minutes</option>");
        s.append("<option value=h").append(dur.unit().equals("h") ? " selected" : "").append(">hours</option>");
        s.append("<option value=d").append(dur.unit().equals("d") ? " selected" : "").append(">days</option>");
        s.append("</select>");
        s.append("<button type=button class=\"btn btn-sm btn-outline-danger warn-thresh-remove\" title=\"Remove threshold\" aria-label=\"Remove\">")
                .append("<i class=\"bi bi-x-lg\"></i></button>");
        s.append("</div>");
        return s.toString();
    }

    private static final String WARN_THRESH_ASSETS =
            "<style>"
            + ".warn-thresh-list{display:flex;flex-direction:column;gap:.4rem;margin:.4rem 0 .6rem}"
            + ".warn-thresh-row{display:flex;align-items:center;flex-wrap:wrap;gap:.4rem;"
            + "background:var(--bs-tertiary-bg);border:1px solid var(--bs-border-color);"
            + "border-radius:.4rem;padding:.4rem .55rem}"
            + ".warn-thresh-row.dragging{opacity:.4}"
            + ".warn-thresh-row.drop-above{box-shadow:inset 0 2px 0 0 var(--bs-primary,#0d6efd)}"
            + ".warn-thresh-row.drop-below{box-shadow:inset 0 -2px 0 0 var(--bs-primary,#0d6efd)}"
            + ".warn-thresh-handle{cursor:grab;user-select:none;color:var(--bs-secondary-color);"
            + "font-size:1.2rem;line-height:1;padding:.1rem .35rem;border-radius:.25rem;flex:0 0 auto}"
            + ".warn-thresh-row:hover .warn-thresh-handle{background:rgba(127,127,127,.12)}"
            + ".warn-thresh-handle:active{cursor:grabbing}"
            + ".warn-thresh-when,.warn-thresh-for{color:var(--bs-secondary-color);font-size:.88rem;flex:0 0 auto}"
            + ".warn-thresh-count{width:5rem;flex:0 0 auto}"
            + ".warn-thresh-action{width:auto;min-width:7rem;flex:0 0 auto}"
            + ".warn-thresh-duration{width:5rem;flex:0 0 auto}"
            + ".warn-thresh-unit{width:auto;min-width:6.5rem;flex:0 0 auto}"
            + ".warn-thresh-row .warn-thresh-remove{margin-left:auto;flex:0 0 auto;line-height:1;padding:.25rem .55rem}"
            + ".warn-thresh-row[data-no-duration=\"1\"] .warn-thresh-for,"
            + ".warn-thresh-row[data-no-duration=\"1\"] .warn-thresh-duration,"
            + ".warn-thresh-row[data-no-duration=\"1\"] .warn-thresh-unit{display:none}"
            + "</style>"
            + "<script>(function(){"
            + "var list=document.getElementById('warn-thresh-list');"
            + "var addBtn=document.getElementById('warn-thresh-add');"
            + "var tpl=document.getElementById('warn-thresh-template');"
            + "if(!list||!addBtn||!tpl)return;"
            + "var NO_DUR={kick:1,ban:1};"
            + "var dragging=null;"
            + "function clearMarkers(){list.querySelectorAll('.warn-thresh-row').forEach(function(r){r.classList.remove('drop-above','drop-below');});}"
            + "function reflectAction(row){"
            + "var act=row.querySelector('.warn-thresh-action');"
            + "if(!act)return;"
            + "row.dataset.noDuration=NO_DUR[act.value]?'1':'0';"
            + "}"
            + "function wireRow(row){"
            + "var rm=row.querySelector('.warn-thresh-remove');"
            + "if(rm)rm.addEventListener('click',function(){row.remove();});"
            + "var act=row.querySelector('.warn-thresh-action');"
            + "if(act)act.addEventListener('change',function(){reflectAction(row);});"
            + "row.addEventListener('dragstart',function(e){"
            + "dragging=row;row.classList.add('dragging');"
            + "if(e.dataTransfer){e.dataTransfer.effectAllowed='move';try{e.dataTransfer.setData('text/plain','x');}catch(_){}}"
            + "});"
            + "row.addEventListener('dragend',function(){if(dragging)dragging.classList.remove('dragging');dragging=null;clearMarkers();});"
            + "reflectAction(row);"
            + "}"
            + "list.addEventListener('dragover',function(e){"
            + "if(!dragging)return;"
            + "var row=e.target.closest('.warn-thresh-row');if(!row||row===dragging)return;"
            + "e.preventDefault();if(e.dataTransfer)e.dataTransfer.dropEffect='move';"
            + "var rect=row.getBoundingClientRect();var before=(e.clientY-rect.top)<(rect.height/2);"
            + "clearMarkers();row.classList.add(before?'drop-above':'drop-below');"
            + "});"
            + "list.addEventListener('dragleave',function(e){"
            + "var row=e.target.closest('.warn-thresh-row');if(row)row.classList.remove('drop-above','drop-below');"
            + "});"
            + "list.addEventListener('drop',function(e){"
            + "if(!dragging)return;"
            + "var row=e.target.closest('.warn-thresh-row');if(!row||row===dragging){clearMarkers();return;}"
            + "e.preventDefault();"
            + "var rect=row.getBoundingClientRect();var before=(e.clientY-rect.top)<(rect.height/2);"
            + "if(before){row.parentNode.insertBefore(dragging,row);}else{row.parentNode.insertBefore(dragging,row.nextSibling);}"
            + "clearMarkers();"
            + "});"
            + "list.querySelectorAll('.warn-thresh-row').forEach(wireRow);"
            + "addBtn.addEventListener('click',function(){"
            + "var frag=tpl.content.cloneNode(true);"
            + "var row=frag.querySelector('.warn-thresh-row');"
            + "list.appendChild(frag);"
            + "if(row){wireRow(row);var inp=row.querySelector('.warn-thresh-count');if(inp){inp.focus();inp.select();}}"
            + "});"
            + "})();</script>";

    private static String filterRow(String enableKey, String label, boolean checked,
                                    String key1, int v1, String unit1,
                                    String key2, int v2, String unit2,
                                    String tip) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"row g-3 align-items-end\">");
        s.append("<div class=col-md-4>").append(checkbox(enableKey, label, checked, tip)).append("</div>");
        if (key1 != null) {
            s.append("<div class=col-md-4><label class=form-label>").append(unit1).append("</label>")
                    .append("<input class=form-control name=").append(key1)
                    .append(" type=number value=\"").append(v1).append("\"></div>");
        } else s.append("<div class=col-md-4></div>");
        if (key2 != null) {
            s.append("<div class=col-md-4><label class=form-label>").append(unit2).append("</label>")
                    .append("<input class=form-control name=").append(key2)
                    .append(" type=number value=\"").append(v2).append("\"></div>");
        } else s.append("<div class=col-md-4></div>");
        s.append("</div>");
        return s.toString();
    }

    private static String checkbox(String name, String label, boolean checked, String help) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"form-check form-switch\">");
        s.append("<input class=form-check-input type=checkbox role=switch id=cb-").append(name)
                .append(" name=").append(name).append(" value=on").append(checked ? " checked" : "").append(">");
        s.append("<label class=\"form-check-label\" for=cb-").append(name).append(">").append(Layout.escape(label));
        s.append(Layout.infoIcon(help));
        s.append("</label></div>");
        return s.toString();
    }

    /**
     * Zip the four parallel arrays posted by the drag-and-drop editor
     * ({@code warn_count}, {@code warn_action}, {@code warn_duration},
     * {@code warn_duration_unit}) into {@link AutomodConfig.WarnThreshold}
     * rows. Order on the wire matches the user's visible row order, so the
     * resulting list reflects what they see. Rows with an unparseable count
     * or unknown action are silently skipped.
     */
    private static List<AutomodConfig.WarnThreshold> parseThresholdRows(Context ctx) {
        var counts    = ctx.formParams("warn_count");
        var actions   = ctx.formParams("warn_action");
        var durations = ctx.formParams("warn_duration");
        var units     = ctx.formParams("warn_duration_unit");
        if (counts == null) counts = List.of();
        if (actions == null) actions = List.of();
        if (durations == null) durations = List.of();
        if (units == null) units = List.of();
        int n = Math.min(counts.size(), actions.size());
        List<AutomodConfig.WarnThreshold> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int count;
            try { count = Integer.parseInt(counts.get(i).trim()); }
            catch (NumberFormatException e) { continue; }
            String action = actions.get(i) == null ? "" : actions.get(i).trim().toLowerCase(java.util.Locale.ROOT);
            if (action.isEmpty()) continue;
            int durValue = 0;
            if (i < durations.size()) {
                try { durValue = Integer.parseInt(durations.get(i).trim()); }
                catch (NumberFormatException ignored) {}
            }
            String unit = i < units.size() && units.get(i) != null ? units.get(i).trim() : "s";
            int durSeconds = switch (unit) {
                case "m" -> durValue * 60;
                case "h" -> durValue * 3600;
                case "d" -> durValue * 86400;
                default  -> durValue;
            };
            if (WARN_ACTIONS_NO_DURATION.contains(action)) durSeconds = 0;
            out.add(new AutomodConfig.WarnThreshold(count, action, durSeconds));
        }
        return out;
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static List<String> multiVals(Context ctx, String key) {
        var raw = ctx.formParams(key);
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
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
