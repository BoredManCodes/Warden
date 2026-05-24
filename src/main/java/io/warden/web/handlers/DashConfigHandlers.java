package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.data.Json;
import io.warden.onboarding.model.DenyAction;
import io.warden.onboarding.model.FlowConfig;
import io.warden.onboarding.model.LandingFaq;
import io.warden.onboarding.model.LandingFeature;
import io.warden.onboarding.model.Question;
import io.warden.onboarding.model.QuestionKind;
import io.warden.onboarding.model.Settings;
import io.warden.onboarding.model.TriageMode;
import io.warden.web.auth.AuditActor;
import io.warden.web.auth.DashAuth;
import io.warden.web.auth.SessionCookie;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /dash/config - single big settings form covering all 7 customisability axes,
 * plus questions CRUD on /dash/config/questions/*. Bootstrap/AdminLTE markup.
 *
 * Tabs are CSS-only (hidden radio inputs + sibling selectors) rather than
 * Bootstrap's JS tabs, so that all 8 settings panels can sit inside one form
 * and be saved together by a single submit.
 */
public final class DashConfigHandlers {

    private final Services services;
    private final GuildLookup lookup;
    private final io.warden.WardenPlugin plugin;

    public DashConfigHandlers(Services services, GuildLookup lookup, io.warden.WardenPlugin plugin) {
        this.services = services;
        this.lookup = lookup;
        this.plugin = plugin;
    }

    public void form(Context ctx) throws Exception {
        Settings s = services.settingsDao.get();
        FlowConfig f = s.flow();
        List<Question> questions = services.questionDao.listAll();

        var roleOpts = lookup.roles();
        var channelOpts = lookup.textChannels();

        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        boolean canEditConfig  = sess != null && sess.canEditConfig();
        boolean canEditLanding = sess != null && sess.canEditLanding();
        boolean isOwner        = sess != null && sess.owner();

        String activeTab = ctx.queryParam("tab");
        if (activeTab == null || activeTab.isBlank()) {
            // Web-manager-only users start on the only tab they can edit.
            activeTab = canEditConfig ? "roles" : "landing";
        }
        // Force web-manager-only users onto the landing tab regardless of query string.
        if (!canEditConfig) activeTab = "landing";

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Config · Warden", "config", ctx));

        if (!lookup.discordConnected()) {
            h.append("<div class=\"alert alert-warning\" role=alert>Discord isn't connected; role and channel dropdowns are empty. ")
                    .append("Set the Discord bot token and guild id in <code>plugins/Warden/config.yml</code> and restart.</div>");
        }
        if ("ok".equals(ctx.queryParam("saved"))) {
            h.append("<div class=\"alert alert-success\" role=alert>Settings saved.</div>");
        }

        h.append("<h1 class=\"h3 mb-3\">Configuration</h1>");
        if (!canEditConfig) {
            h.append("<div class=\"alert alert-info\" role=alert>You're signed in as a "
                    + "<strong>Web manager</strong>. You can edit the public Landing page only; "
                    + "Roles, Delivery, Triage, and other tabs are restricted to the Config admin role.</div>");
        }

        // Tabs visible to this user. Web managers only see Landing.
        List<TabDef> visibleTabs = canEditConfig ? TABS : List.of(new TabDef("landing", "Landing"));

        // --- Tab radios (must precede .cfg-tab-bar and .cfg-tab-panels as siblings for the CSS) ---
        h.append("<div class=cfg-tabs>");
        for (var t : visibleTabs) {
            h.append("<input type=radio name=\"__cfg_tab\" id=\"cfg-").append(t.id())
                    .append("\" class=cfg-tabradio")
                    .append(t.id().equals(activeTab) ? " checked" : "")
                    .append(">");
        }
        h.append("<div class=cfg-tab-bar>");
        for (var t : visibleTabs) {
            h.append("<label for=\"cfg-").append(t.id()).append("\" class=cfg-tablabel>")
                    .append(escape(t.label())).append("</label>");
        }
        h.append("</div>");

        h.append("<div class=cfg-tab-panels>");
        h.append("<form method=post action=\"/dash/config\">");
        h.append("<input type=hidden name=active_tab value=\"\" id=active_tab_field>");

        if (canEditConfig) {
        // --- Roles tab ---
        h.append("<div class=cfg-tab-panel data-tab=roles>");
        h.append(section("Roles and channels",
                "<p class=\"text-secondary\">Tell Warden which Discord roles and channels to use. "
                + "Until these are picked, Warden has nothing to add, remove, or post to, so gating and "
                + "delivery silently do nothing.</p>"));
        h.append(selectField("gated_role_id", "Gated role (newcomers)",
                GuildLookup.withDefaults(roleOpts, s.gatedRoleId()), s.gatedRoleId(), true,
                "Applied to every new member the moment they join. Should be a role with very limited channel access "
                + "(e.g. only a #welcome channel). Warden removes this and grants the Full member role on approval."));
        h.append(selectField("full_role_id", "Full member role (after approval)",
                GuildLookup.withDefaults(roleOpts, s.fullRoleId()), s.fullRoleId(), true,
                "Granted on approval. This is the role that unlocks normal server channels. "
                + "Set channel permissions so this role can see what newcomers shouldn't."));
        h.append(selectField("mod_role_id", "Mod role (dashboard + Discord buttons)",
                GuildLookup.withDefaults(roleOpts, s.modRoleId()), s.modRoleId(), true,
                "Members with this role see /dash/pending, /dash/audit, /dash/members and can click Approve/Deny on "
                + "review embeds in Discord. Mods do NOT see /dash/config. Server owners and ADMINISTRATOR-permissioned "
                + "members are always treated as mods and as config admins."));

        // Access-control roles: only the server owner / ADMINISTRATOR can set these,
        // so mods can't escalate themselves to config access. Non-owners see the
        // controls but disabled, with a tooltip explaining the restriction.
        h.append(section("Access roles (server owner only)",
                "<p class=\"text-secondary\">These two roles gate access to the Configuration page itself. "
                + "Only the Discord server owner (or anyone with the ADMINISTRATOR permission in the guild) "
                + "can change them - mods cannot escalate themselves into the config.</p>"
                + (isOwner ? "" : "<div class=\"alert alert-warning py-2\" role=alert>You're signed in as a "
                    + "Config admin, not the server owner. The two fields below are read-only for you.</div>")));
        h.append(restrictedSelect("config_admin_role_id", "Config admin role (full access to /dash/config)",
                GuildLookup.withDefaults(roleOpts, s.configAdminRoleId()), s.configAdminRoleId(),
                "Members with this role get the full Configuration page: roles, delivery, triage, "
                + "approve/deny actions, rules, landing, questions, AI gateway. Leave unset to keep "
                + "config access owner-only.",
                isOwner));
        h.append(restrictedSelect("web_manager_role_id", "Web manager role (landing page only)",
                GuildLookup.withDefaults(roleOpts, s.webManagerRoleId()), s.webManagerRoleId(),
                "Members with this role can edit ONLY the public Landing tab (server name, hero image, "
                + "FAQ, etc.). They cannot touch onboarding, triage, or any sensitive flow setting. "
                + "Good for delegating cosmetic / marketing edits without granting full config access.",
                isOwner));
        h.append(selectField("welcome_channel_id", "Welcome / public greet channel",
                GuildLookup.withDefaults(channelOpts, s.welcomeChannelId()), s.welcomeChannelId(), true,
                "Used by the optional approval announcement (Approve actions > 'Announce in welcome channel'). "
                + "Pick a channel newcomers can see after they've been approved."));
        h.append(selectField("mod_review_channel_id", "Mod review queue channel",
                GuildLookup.withDefaults(channelOpts, s.modReviewChannelId()), s.modReviewChannelId(), true,
                "Where Warden posts the review embed (applicant + answers + Approve/Deny buttons) when an "
                + "application is escalated. Make this channel visible to mods only."));
        h.append("</div>");

        // --- Delivery tab ---
        h.append("<div class=cfg-tab-panel data-tab=delivery>");
        h.append(section("Delivery", "<p class=\"text-secondary\">Where Warden reaches out to a new joiner.</p>"));
        h.append(checkbox("flow_delivery_via_dm", "DM the new member directly", f.deliveryViaDm(),
                "Bot opens a DM and sends the delivery message. Fails silently if the user has DMs from server "
                + "members disabled - in that case, also turn on 'Post a public welcome' so they don't slip through."));
        h.append(checkbox("flow_delivery_via_channel", "Post a public welcome (mentions them)", f.deliveryViaChannel(),
                "Posts the delivery message in the chosen channel and pings the new member. Reaches users with "
                + "closed DMs but means everyone in the channel sees the welcome."));
        h.append(selectField("flow_delivery_channel_id", "Delivery channel (when public-post is on)",
                GuildLookup.withDefaults(channelOpts, f.deliveryChannelId()), f.deliveryChannelId(), true,
                "The channel the public welcome goes in. Only used when 'Post a public welcome' is on."));
        h.append(templateField("flow_delivery_message_template", "Delivery message template",
                f.deliveryMessageTemplate(), 6,
                "Placeholders: {username} {guild_name} {code} {web_url} {web_url_with_code} {entry_options} {code_note}",
                "The message a new joiner sees in their DM (or in the channel post). Edit for branding or a different "
                + "tone. Unknown placeholders render as empty so you can't break it by typo.",
                List.of("{username}", "{guild_name}", "{code}", "{web_url}", "{web_url_with_code}",
                        "{entry_options}", "{code_note}")));
        h.append("</div>");

        // --- Entry tab ---
        h.append("<div class=cfg-tab-panel data-tab=entry>");
        h.append(section("Entry methods", "<p class=\"text-secondary\">How the user can start onboarding once they get the message.</p>"));
        h.append(checkbox("flow_entry_via_discord_button", "\"Start in Discord\" button (DM Q&A flow)", f.entryViaDiscordButton(),
                "Shows a 'Start in Discord' button. Click runs the rules + question flow entirely in DMs using modal "
                + "dialogs (text) and select menus (choices). No browser needed."));
        h.append(checkbox("flow_entry_via_web_code", "Web + code (paste the DM'd code at /onboard)", f.entryViaWebCode(),
                "Adds an 8-character code to the delivery message that the user pastes at /onboard. Codes are "
                + "single-use and expire after 15 minutes. Best for users who prefer browsers to Discord modals."));
        h.append(checkbox("flow_entry_via_web_oauth", "Web + Discord OAuth", f.entryViaWebOauth(),
                "Adds a 'Sign in with Discord' option to /onboard. The user OAuths to identify themselves - no code "
                + "to paste. Requires the Discord OAuth client secret and the web session secret to be set in "
                + "<code>plugins/Warden/config.yml</code>."));
        h.append("</div>");

        // --- GeoIP tab ---
        h.append("<div class=cfg-tab-panel data-tab=geoip>");
        h.append(section("GeoIP (country lookup on the Geography stats page)",
                "<p class=\"text-secondary\">Optional. When enabled, Warden uses MaxMind's free GeoLite2 data to "
                + "tag Minecraft logins with the player's country and powers the <a href=\"/dash/stats/geo\">Geography "
                + "stats page</a>. No data is sent to MaxMind at lookup time - the database is downloaded once and "
                + "refreshed weekly in the background. Discord-side geography is never available because the Discord "
                + "API doesn't expose user IPs to bots.</p>"
                + "<p class=\"text-secondary\">To get a licence key, sign up free at "
                + "<a href=\"https://www.maxmind.com/en/geolite2/signup\" target=\"_blank\" rel=\"noopener\">"
                + "maxmind.com/en/geolite2/signup</a> and generate a key under <em>Manage License Keys</em>. "
                + "Changes here take effect at the next plugin restart.</p>"));
        h.append(checkbox("geoip_enabled", "Enable GeoIP", s.geoipEnabled(),
                "When on (and a licence key is set), Warden downloads the GeoLite2 database into "
                + "<code>plugins/Warden/data/geoip/</code> at startup and tags new Minecraft sessions with a "
                + "country code. When off, the Geography stats page shows a 'not configured' panel."));
        boolean geoKeySet = s.geoipLicenseKey() != null && !s.geoipLicenseKey().isBlank();
        h.append("<div class=mb-3><label class=\"form-label\" for=f_geoip_license_key>MaxMind licence key"
                + Layout.infoIcon("Stored in the settings row of the SQLite database, never logged or rendered "
                        + "back to the page. Leave blank on save to keep the current key; type a new value to "
                        + "replace it. The WARDEN_GEOIP_LICENSE_KEY env var still wins if set, for ops who "
                        + "prefer secrets outside the database.")
                + (geoKeySet
                        ? " <span class=\"badge text-bg-success-subtle text-success-emphasis\">saved</span>"
                        : " <span class=\"badge text-bg-warning-subtle text-warning-emphasis\">not set</span>")
                + "</label>"
                + "<input class=\"form-control\" id=f_geoip_license_key type=password autocomplete=off "
                + "name=geoip_license_key value=\"\" placeholder=\""
                + (geoKeySet ? "(current key kept - type to replace)" : "Paste your MaxMind licence key here")
                + "\"></div>");
        h.append("</div>");

        // --- Gating tab ---
        h.append("<div class=cfg-tab-panel data-tab=gating>");
        h.append(section("Gating", "<p class=\"text-secondary\">Apply the gated role on join? Off = informational-only.</p>"));
        h.append(checkbox("flow_gating_enabled", "Apply the gated role on join", f.gatingEnabled(),
                "ON: the gated role is applied the instant someone joins, locking them out of normal channels until "
                + "approved. OFF: Warden silently records the join and runs onboarding for informational purposes "
                + "only - users have full access from second one."));
        h.append("</div>");

        // --- Triage tab ---
        h.append("<div class=cfg-tab-panel data-tab=triage>");
        h.append(section("Triage mode", "<p class=\"text-secondary\">How the LLM (Manifest) participates after a user submits answers.</p>"));
        h.append(radioGroup("flow_triage_mode", List.of(
                new RadioOpt(TriageMode.MOD_ONLY.wire(),
                        "Mods only - every submission goes to the pending queue, no LLM call",
                        "Every application waits for a human. Use if you don't have a Manifest key, or want full "
                        + "human control over who gets in."),
                new RadioOpt(TriageMode.LLM_AUTO.wire(),
                        "LLM auto - approve or deny by threshold, escalate otherwise (Recommended)",
                        "Manifest scores the application. Confident approves and confident denies happen "
                        + "automatically; uncertain ones go to mods. Tune the thresholds below."),
                new RadioOpt(TriageMode.LLM_ONLY.wire(),
                        "LLM only - take its decision verbatim, no escalation",
                        "Manifest's verdict is final. Escalate verdicts are coerced to deny. Don't pick this "
                        + "unless you trust the model's calibration on your specific server."),
                new RadioOpt(TriageMode.AUTO_APPROVE.wire(),
                        "Auto-approve - every submission gets through (no LLM)",
                        "Every applicant gets approved. The LLM is not called and mods never see anything. "
                        + "Useful when you just want a rules-acknowledgement audit trail without real gating.")
        ), f.triageMode().wire()));
        h.append(numberField("llm_auto_approve_threshold", "Auto-approve threshold (0-1)",
                String.format(Locale.ROOT, "%.2f", s.llmAutoApproveThreshold()), "0", "1", "0.01",
                "Minimum LLM confidence required for an automatic approve. Approve verdicts at or above this "
                + "value auto-apply; below it, escalate to mods. Default 0.85 is conservative."));
        h.append(numberField("llm_auto_deny_threshold", "Auto-deny threshold (0-1; higher = more confident-deny required)",
                String.format(Locale.ROOT, "%.2f", s.llmAutoDenyThreshold()), "0", "1", "0.01",
                "How confident the LLM must be to auto-deny. Inverted: higher value = MORE confidence required = "
                + "fewer auto-denies. Default 0.15 requires the LLM to be ~85% confident. Denials are severe so a "
                + "high bar is sensible. Ignored when 'Allow LLM to auto-deny' is off."));
        h.append(checkbox("llm_auto_deny_enabled", "Allow LLM to auto-deny", s.llmAutoDenyEnabled(),
                "ON (default): confident deny verdicts apply automatically per the threshold above. "
                + "OFF: the LLM can recommend a deny, but every deny verdict is escalated to mods for a human "
                + "to confirm. Auto-approve is unaffected. Useful when you want the LLM as a fast-track for "
                + "easy approvals but never want it to reject a real person without review."));
        h.append(textareaField("llm_system_prompt", "LLM system prompt", s.llmSystemPrompt(), 14,
                "Must instruct the model to return JSON {decision, confidence, reasoning}.",
                "Instructions sent to Manifest before the applicant's data. Default works fine; tweak to add "
                + "server-specific guidance (e.g. 'pay extra attention to attempts to evade a previous ban')."));
        h.append("</div>");

        // --- AI tab ---
        h.append("<div class=cfg-tab-panel data-tab=ai>");
        h.append(section("AI gateway",
                "<p class=\"text-secondary\">Point Warden at any OpenAI-Responses-compatible gateway. "
                + "Used by the LLM triage modes on the Triage tab. The endpoint URL receives "
                + "<code>POST {base_url}/responses</code> with a Bearer token, the model name, and the "
                + "system + user prompt concatenated into <code>input</code>.</p>"));
        boolean keySet = s.llmApiKey() != null && !s.llmApiKey().isBlank();
        h.append("<div class=mb-3><label class=\"form-label\" for=f_llm_api_key>API key"
                + Layout.infoIcon("Bearer token sent in the Authorization header. Stored in the settings "
                + "row of the SQLite database, never logged or rendered back to the page. Leave blank "
                + "on save to keep the current key; type a new value to replace it.")
                + (keySet ? " <span class=\"badge text-bg-success-subtle text-success-emphasis\">saved</span>"
                          : " <span class=\"badge text-bg-warning-subtle text-warning-emphasis\">not set</span>")
                + "</label>"
                + "<input class=\"form-control\" id=f_llm_api_key type=password autocomplete=off "
                + "name=llm_api_key value=\"\" placeholder=\""
                + (keySet ? "(current key kept - type to replace)"
                          : "Paste your API key here")
                + "\"></div>");
        h.append(textField("llm_base_url", "Base URL",
                s.llmBaseUrl() == null || s.llmBaseUrl().isBlank() ? "https://app.manifest.build/v1" : s.llmBaseUrl(),
                "Without the trailing slash. Examples: https://api.openai.com/v1 · "
                + "https://app.manifest.build/v1 · https://openrouter.ai/api/v1 · "
                + "http://localhost:11434/v1 (Ollama)."));
        h.append(textField("llm_model", "Model",
                s.llmModel() == null || s.llmModel().isBlank() ? "auto" : s.llmModel(),
                "The model id the gateway expects. Examples: gpt-4o-mini, auto, "
                + "anthropic/claude-haiku-4-5, llama3.2."));
        h.append("<div class=\"mt-3 mb-2 d-flex align-items-center gap-2 flex-wrap\">"
                + "<button type=button id=cfg-llm-test class=\"btn btn-outline-primary\">"
                + "<i class=\"bi bi-stars\"></i> Test connection (ask for a joke)</button>"
                + "<span class=\"text-secondary small\">Uses the values currently in the form, "
                + "even if you haven't hit Save yet.</span></div>");
        h.append("<div id=cfg-llm-test-result class=\"cfg-llm-test-result\" role=status aria-live=polite></div>");
        h.append("</div>");

        // --- Approve tab ---
        h.append("<div class=cfg-tab-panel data-tab=approve>");
        h.append(section("Approve actions", "<p class=\"text-secondary\">What happens when an application is approved.</p>"));
        h.append(checkbox("flow_approve_dm_enabled", "DM the approved user", f.approveDmEnabled(),
                "Sends a friendly DM after approval. Off = silent role-swap; the user finds out they're in by "
                + "suddenly seeing more channels."));
        h.append(templateField("flow_approve_dm_template", "Approve DM template", f.approveDmTemplate(), 4,
                "Placeholders: {username} {guild_name}",
                "What the approval DM says. Keep it warm.",
                List.of("{username}", "{guild_name}")));
        h.append(checkbox("flow_approve_channel_announce", "Announce in welcome channel", f.approveChannelAnnounce(),
                "Posts a public welcome message in the Welcome channel. Good for community vibes; turn off for a "
                + "quieter onboarding feel."));
        h.append(templateField("flow_approve_channel_template", "Approve channel template", f.approveChannelTemplate(), 3,
                "Placeholders: {username} {mention} {guild_name}",
                "What the public welcome post says. {mention} pings the user with a clickable @ link.",
                List.of("{username}", "{mention}", "{guild_name}")));
        h.append(multiCheckboxField("flow_approve_extra_roles", "Extra roles to add on approve",
                roleOpts, f.approveExtraRoles(),
                "Additional roles to grant on approval, beyond the Full member role. "
                + "Useful for tagging 'verified' / 'level 1' style stacking roles."));
        h.append("</div>");

        // --- Deny tab ---
        h.append("<div class=cfg-tab-panel data-tab=deny>");
        h.append(section("Deny actions", "<p class=\"text-secondary\">What happens when an application is denied.</p>"));
        h.append(checkbox("flow_deny_dm_enabled", "DM the denied user", f.denyDmEnabled(),
                "Sends a polite rejection DM. Off = silent deny; the user is just left in the gated state with "
                + "no explanation."));
        h.append(templateField("flow_deny_dm_template", "Deny DM template", f.denyDmTemplate(), 3,
                "Placeholders: {username} {guild_name}",
                "What the rejection DM says. Keep it polite - it represents your community even on a 'no'.",
                List.of("{username}", "{guild_name}")));
        h.append(radioGroup("flow_deny_action", List.of(
                new RadioOpt(DenyAction.LEAVE_GATED.wire(),
                        "Leave gated - keep the gated role, no removal",
                        "Applicant is denied but keeps the gated role. They stay in the server with limited access. "
                        + "Easiest to reverse if you change your mind."),
                new RadioOpt(DenyAction.STRIP_GATED.wire(),
                        "Strip gated - remove the gated role only",
                        "Removes the gated role but doesn't kick. The user falls back to whatever @everyone sees "
                        + "- often nothing useful. They can re-apply via /warden reonboard."),
                new RadioOpt(DenyAction.KICK.wire(),
                        "Kick from the server",
                        "Removes the user from the server. They can rejoin with a fresh invite and re-apply."),
                new RadioOpt(DenyAction.BAN.wire(),
                        "Ban from the server",
                        "Permanent ban. Use only for clearly malicious applicants - your audit log will be the "
                        + "evidence trail if they appeal.")
        ), f.denyAction().wire()));
        h.append("</div>");

        // --- Rules tab ---
        h.append("<div class=cfg-tab-panel data-tab=rules>");
        h.append(section("Rules", "<p class=\"text-secondary\">Shown in the rules embed before questions begin.</p>"));
        h.append(templateField("rules_markdown", "Rules markdown", s.rulesMarkdown(), 12,
                "Standard Discord markdown is supported in the embed body.",
                "Shown to every applicant as the first step of onboarding. They click 'I agree' to continue. "
                + "Standard Discord markdown (**bold**, *italics*, headings, lists, links) is supported.",
                List.of()));
        h.append("</div>");
        } // end canEditConfig

        // --- Landing tab ---
        io.warden.onboarding.model.LandingConfig l = s.landing();
        h.append("<div class=cfg-tab-panel data-tab=landing>");
        h.append(section("Public landing page",
                "<p class=\"text-secondary\">Shown to visitors at <code>/</code> once Warden is fully set up. "
                + "The HTML and CSS live at <code>plugins/Warden/www/landing.html</code> and "
                + "<code>plugins/Warden/www/landing.css</code> - edit those files to change layout, fonts, "
                + "colours, copy, or to bring your own design. Values below are substituted into the template "
                + "as <code>{{server_name}}</code>, <code>{{tagline}}</code>, etc.</p>"));
        h.append(templateFieldsReference());
        h.append(radioGroup("landing_mode", List.of(
                new RadioOpt("enabled",
                        "Enabled - render the landing page",
                        "Visitors at \"/\" see plugins/Warden/www/landing.html with live stats and the buttons below."),
                new RadioOpt("disabled",
                        "Disabled - return 404 at /",
                        "Visitors get a 404 at the root. The dashboard (/dash) and onboarding (/onboard) still work."),
                new RadioOpt("redirect",
                        "Redirect to another URL",
                        "302-redirects the root to the URL below. Warning: once redirecting, mods can no longer click "
                        + "through to the dashboard from this server's root - they have to remember to visit "
                        + "<public_url>/dash directly. Keep that link handy.")
        ), l == null || l.mode() == null || l.mode().isBlank() ? "enabled" : l.mode()));
        h.append(textField("landing_redirect_url", "Redirect URL (used when mode = Redirect)",
                l == null ? "" : l.redirectUrl(),
                "Where to send visitors when redirect mode is on. Mods can still reach the dashboard "
                + "at <public_url>/dash but no link points there from here - bookmark it."));
        h.append(textField("landing_server_name", "Server name",
                l == null ? "" : l.serverName(),
                "Shown as the headline on the landing page. Substituted into the template as {{server_name}}."));
        h.append(textField("landing_server_address", "Server address (play.example.com)",
                l == null ? "" : l.serverAddress(),
                "The Minecraft connect address players paste into their client. Substituted as {{server_address}}. "
                + "Leave blank to hide the address row."));
        h.append(textField("landing_tagline", "Tagline / subtitle",
                l == null ? "" : l.tagline(),
                "Short line under the server name. Substituted as {{tagline}}. Leave blank to hide."));
        h.append(textField("landing_join_url", "Join button URL",
                l == null ? "" : l.joinUrl(),
                "Where the Join Now button points. Common choices: a minecraft:// connect link, a store / shop URL, "
                + "or your wiki. Leave blank to hide the button. Substituted as {{join_url}}."));

        // Brand + hero images. Brand falls back to the Discord guild's icon at
        // render time when blank (see WelcomeHandlers#renderLanding). Hero stays
        // blank when unset and the template can choose to skip the slot.
        h.append(textField("landing_brand_image_url", "Brand image URL",
                l == null ? "" : l.brandImageUrl(),
                "Logo / wordmark shown at the top of the landing page. Blank = use the Discord guild's icon. "
                + "Substituted as {{brand_image_url}} in landing.html. Use any https URL; CDN paths work."));
        h.append(textField("landing_hero_image_url", "Hero image URL",
                l == null ? "" : l.heroImageUrl(),
                "Large image embedded in the hero section. Substituted as {{hero_image_url}}. "
                + "A YouTube URL (watch, youtu.be, shorts, embed) is detected automatically and "
                + "plays muted and looped behind the hero text. "
                + "Leave blank to suppress the hero image; the template's gradient/grid stays in place."));

        h.append(textField("landing_promo_video_url", "Promo video URL (YouTube)",
                l == null ? "" : l.promoVideoUrl(),
                "Optional YouTube link rendered as a standalone embed section on the landing page. "
                + "Accepts watch, youtu.be, shorts, and embed URLs. "
                + "Leave blank to hide the section entirely."));

        // Accent colour. Hex string applied as {{accent_color}} in the template.
        String accent = l == null ? "" : l.accentColor();
        if (accent == null || accent.isBlank()) accent = "#39beff";
        h.append("<div class=mb-3><label class=\"form-label\" for=\"f_landing_accent_color\">Accent color"
                + Layout.infoIcon("Hex colour used for buttons, links, and highlights on the landing page. "
                + "Substituted as {{accent_color}} in landing.html and landing.css.")
                + "</label>"
                + "<div class=\"d-flex align-items-center gap-2\">"
                + "<input class=\"form-control form-control-color\" id=\"f_landing_accent_color\" type=color"
                + " name=\"landing_accent_color\" value=\"" + escape(accent) + "\" style=\"width:4rem;height:2.4rem\">"
                + "<input class=\"form-control\" id=\"f_landing_accent_color_text\" type=text"
                + " value=\"" + escape(accent) + "\" maxlength=7 style=\"max-width:9rem\" aria-label=\"Accent color hex\">"
                + "<span class=\"text-secondary small\">Default <code>#39beff</code></span>"
                + "</div></div>"
                // Tiny script: keep the colour picker and the hex text input in sync.
                + "<script>(function(){var c=document.getElementById('f_landing_accent_color');"
                + "var t=document.getElementById('f_landing_accent_color_text');if(!c||!t)return;"
                + "c.addEventListener('input',function(){t.value=c.value;});"
                + "t.addEventListener('input',function(){var v=t.value.trim();"
                + "if(/^#?[0-9a-fA-F]{6}$/.test(v)){c.value=v.charAt(0)==='#'?v:'#'+v;}});})();</script>");

        // Stats block: each of the two counters gets a label and a value source
        // (built-in or PAPI placeholder). Both pieces are independent: you can
        // rename the label without touching the value, or vice versa.
        h.append(section("Stats - labels and value sources",
                "<p class=\"text-secondary\">Each live-stat counter on the landing has a <strong>label</strong> "
                + "(the small caption underneath the number) and a <strong>value source</strong> (the number "
                + "itself). Labels are free text. Values default to Bukkit's online-player count and the "
                + "configured Discord guild's member count, but either can be overridden with any "
                + "<a href=\"https://github.com/PlaceholderAPI/PlaceholderAPI\" target=\"_blank\" rel=\"noreferrer\">PlaceholderAPI</a> "
                + "placeholder (e.g. <code>%server_online%</code>, <code>%bedwars_active_games%</code>, "
                + "<code>%vault_eco_balance%</code>). Warden runs fine without PAPI installed - missing "
                + "placeholders silently fall back to the built-in value.</p>"));

        // Players-online counter
        h.append("<h3 class=\"h6 mt-3 mb-2\">First counter (default: players online)</h3>");
        h.append(textField("landing_stat_players_label", "Label",
                l == null ? "Players online" : l.statPlayersLabelOrDefault(),
                "The text caption shown under the number. Substituted into landing.html as {{players_label}}. "
                + "Default 'Players online'. Plain text only - no HTML."));
        h.append(textField("landing_papi_players_online", "Value (PAPI placeholder)",
                l == null ? "" : l.papiPlayersOnline(),
                "Leave blank to use Bukkit's online-player count. Example: %server_online% or "
                + "%bedwars_active_games%. The resolved string replaces {{players_online}}."));

        // Discord-members counter
        h.append("<h3 class=\"h6 mt-3 mb-2\">Second counter (default: Discord members)</h3>");
        h.append(textField("landing_stat_members_label", "Label",
                l == null ? "Discord members" : l.statMembersLabelOrDefault(),
                "The text caption shown under the number. Substituted into landing.html as {{members_label}}. "
                + "Default 'Discord members'. Plain text only - no HTML."));
        h.append(textField("landing_papi_discord_members", "Value (PAPI placeholder)",
                l == null ? "" : l.papiDiscordMembers(),
                "Leave blank to use the configured Discord guild's member count. Any PAPI placeholder string "
                + "is accepted. Replaces {{discord_members}}."));
        h.append("<p class=\"text-secondary small mb-0\">Live stats - "
                + "{{players_online}}, {{players_max}}, {{discord_members}} - "
                + "are read at request time from the running Minecraft server and the Discord guild, no config needed. "
                + "{{signin_url}} and {{dash_url}} are also available.</p>");

        // Analytics + consent banner. Both are opt-in: blank GA id = no third-party
        // script on the public site, banner toggle off = no cookie notice.
        h.append(section("Analytics",
                "<p class=\"text-secondary\">Drop a Google Analytics tag onto the public landing, "
                + "rules, and map pages. Leave the field blank and no analytics script is loaded; "
                + "set it and the standard gtag.js snippet is injected at request time. "
                + "Accepted formats: GA4 (<code>G-XXXXXXXXXX</code>), Universal Analytics "
                + "(<code>UA-XXXX-Y</code>) or Google Tag Manager (<code>GTM-XXXXX</code>). "
                + "The dashboard at <code>/dash/*</code> never loads analytics.</p>"));
        h.append(textField("landing_google_analytics_id", "Google Analytics Measurement ID",
                l == null ? "" : (l.googleAnalyticsId() == null ? "" : l.googleAnalyticsId()),
                "Example: G-AB12CD34EF. Blank disables analytics. Find this in your GA admin "
                + "under Data Streams. The ID is rendered verbatim into the gtag script URL, "
                + "so unrecognised formats are silently dropped to avoid injection."));
        h.append(checkbox("landing_cookie_banner", "Show cookie warning",
                l != null && l.cookieBannerEnabled(),
                "Show a small dismissible banner on the public site asking visitors to accept "
                + "or decline cookies. Recommended when you have analytics turned on and serve "
                + "visitors from the EU/UK - their ePrivacy/GDPR rules treat the GA cookies "
                + "(_ga, _gid) as requiring consent before they're written. When the banner is "
                + "on, GA boots with Consent Mode set to 'denied' and only writes cookies after "
                + "the visitor clicks Accept. The choice is remembered in localStorage so the "
                + "banner doesn't reappear on every page load. Leave off if you have no analytics "
                + "and no other cookies that need consent, or if you serve a non-EU audience and "
                + "don't want a banner."));

        // Public leaderboard page - surfaces the XP leaderboard at /leaderboard
        // with operator-controlled title/description and a configurable top N.
        h.append(section("Public leaderboard page",
                "<p class=\"text-secondary\">Publishes the XP leaderboard at <code>/leaderboard</code> on this site "
                + "and adds a link to the landing nav. Requires the Leveling system to be enabled in the "
                + "<a href=\"/dash/levels\">Levels and XP</a> tab. Top members are pulled from the level_users table.</p>"));
        h.append(checkbox("landing_leaderboard_enabled", "Publish /leaderboard page",
                l != null && l.leaderboardEnabled(),
                "When on, /leaderboard becomes a public route on this site and the link appears in the landing nav."));
        h.append(textField("landing_leaderboard_label", "Nav link label",
                l == null || l.leaderboardLabel() == null || l.leaderboardLabel().isBlank() ? "Leaderboard" : l.leaderboardLabel(),
                "Text shown in the landing nav for this link. Defaults to 'Leaderboard' if blank."));
        h.append(textField("landing_leaderboard_title", "Page title",
                l == null || l.leaderboardTitle() == null || l.leaderboardTitle().isBlank() ? "Leaderboard" : l.leaderboardTitle(),
                "Shown as the big heading at the top of the /leaderboard page."));
        h.append(textareaField("landing_leaderboard_description", "Page description",
                l == null ? "" : l.leaderboardDescription(), 2,
                "Short paragraph rendered under the heading. Plain text."));
        h.append(textField("landing_leaderboard_top_n", "How many members to show",
                l == null ? "25" : String.valueOf(l.leaderboardTopNOrDefault()),
                "Top N members by XP. Defaults to 25. Clamped to 200."));

        // Live map embed - lets the operator surface their Dynmap / Pl3xMap / etc.
        // as a /map page in the public landing site without writing any HTML.
        h.append(section("Live map (Dynmap, Pl3xMap, BlueMap, squaremap)",
                "<p class=\"text-secondary\">If you already run a web map plugin alongside your server, "
                + "Warden can expose it at <code>/map</code> on this site and add a Map link to the landing nav. "
                + "The map keeps running on its own port; Warden just embeds it in an &lt;iframe&gt;.</p>"));
        h.append(checkbox("landing_map_enabled", "Publish /map page", l != null && l.mapEnabled(),
                "When on, /map becomes a public route on this site and the Map link appears in the landing nav. "
                + "Needs the URL below to be set."));
        h.append(selectFieldRaw("landing_map_provider", "Which map plugin are you running?", List.of(
                        new RadioOpt("",          "(not sure / not running one)",
                                "Pick this if you don't have a web map installed yet. Warden won't show the Map link."),
                        new RadioOpt("dynmap",    "Dynmap",
                                "Default web port 8123. Use the public URL players can reach, e.g. http://play.example.com:8123/."),
                        new RadioOpt("pl3xmap",   "Pl3xMap",
                                "Default web port 8080. Pl3xMap serves a static tile viewer; any URL that loads its index page works."),
                        new RadioOpt("bluemap",   "BlueMap",
                                "Default web port 8100. BlueMap renders a 3D map; embedding in an iframe is supported."),
                        new RadioOpt("squaremap", "squaremap",
                                "Default web port 8080. squaremap is the maintained fork of the original Minimap; same iframe-friendly viewer."),
                        new RadioOpt("custom",    "Other / custom URL",
                                "Anything else - dynmap subdomain, a hosted Mapper instance, etc.")
                ), l == null || l.mapProvider() == null ? "" : l.mapProvider(),
                "Informational - we use this to set sensible defaults and to label the map nicely."));
        h.append(textField("landing_map_url", "Map URL",
                l == null ? "" : l.mapUrl(),
                "Full URL to the map's web UI, including http/https and the port if non-standard. "
                + "Example for Dynmap: http://play.example.com:8123/ - or whatever a player would type in their browser to view the map directly."));
        h.append(textField("landing_map_label", "Nav link label",
                l == null || l.mapLabel() == null || l.mapLabel().isBlank() ? "Live Map" : l.mapLabel(),
                "Text shown in the landing nav for this link. Defaults to 'Live Map' if blank."));

        // Features grid - the three-up card section under the hero. Empty list
        // hides the section (and its in-page nav anchor) entirely.
        h.append(section("Feature cards",
                "<p class=\"text-secondary\">The cards under the hero on the landing page. "
                + "Each card is a short pitch with an icon. Leave the list empty to hide the "
                + "Features section (and its nav link) entirely.</p>"));
        h.append(landingFeaturesEditor(l == null ? java.util.List.of() : l.features()));

        // FAQ - the accordion at the bottom of the landing page.
        h.append(section("FAQ",
                "<p class=\"text-secondary\">The questions visitors most often ask before joining. "
                + "Rendered as a click-to-expand accordion. Leave the list empty to hide the "
                + "FAQ section entirely.</p>"));
        h.append(landingFaqsEditor(l == null ? java.util.List.of() : l.faqs()));
        h.append("</div>");

        h.append("<div class=\"save-row mt-4 pt-3 border-top\">")
                .append("<button type=submit class=\"btn btn-success\">Save settings</button></div>");
        h.append("</form>");

        // --- Modules tab (outside the settings form: writes to config.yml,
        // not the settings DB, and takes effect on next plugin start) ---
        if (canEditConfig) {
            h.append(modulesPanel(plugin, services.config.modules()));
        }

        // --- Questions tab (outside the settings form) ---
        h.append("<div class=cfg-tab-panel data-tab=questions>");
        h.append(section("Generate with AI",
                "<p class=\"text-secondary\">Describe what you want to learn about new applicants and the AI will draft "
                + "a batch of questions for you. Review and edit them before saving.</p>"));
        h.append("<div class=\"qai-card border rounded p-3 mb-4 bg-body-tertiary\">");
        h.append("<div class=\"mb-2\"><label for=\"qai-brief\" class=\"form-label fw-semibold mb-1\">Brief</label>");
        h.append("<textarea id=\"qai-brief\" class=\"form-control\" rows=\"3\" "
                + "placeholder=\"e.g. Explain what you're looking for in a potential player. We want mature builders who can "
                + "commit to a few hours a week, won't grief, and care about community events.\"></textarea></div>");
        h.append("<div class=\"d-flex flex-wrap align-items-end gap-2 mb-2\">");
        h.append("<div><label for=\"qai-count\" class=\"form-label small text-secondary mb-1\">How many</label>"
                + "<input id=\"qai-count\" type=\"number\" min=\"1\" max=\"15\" value=\"5\" class=\"form-control form-control-sm\" style=\"width:6rem\"></div>");
        h.append("<button type=\"button\" id=\"qai-gen\" class=\"btn btn-primary btn-sm\">"
                + "<i class=\"bi bi-stars me-1\"></i>Generate questions</button>");
        h.append("<span id=\"qai-status\" class=\"q-status ms-2\" role=status aria-live=polite></span>");
        h.append("</div>");
        h.append("<div id=\"qai-preview\" class=\"qai-preview\"></div>");
        h.append("<template id=\"qai-row-tpl\">"
                + "<div class=\"qai-row border rounded p-2 mb-2 bg-body\">"
                + "<div class=\"d-flex gap-2 mb-1\">"
                + "<input class=\"qai-prompt form-control form-control-sm\" placeholder=\"Question prompt\">"
                + "<select class=\"qai-kind form-select form-select-sm\" style=\"width:auto\">"
                + "<option value=\"short_text\">Short text</option>"
                + "<option value=\"long_text\">Long text</option>"
                + "<option value=\"single_choice\">Single choice</option>"
                + "<option value=\"multi_choice\">Multi choice</option>"
                + "</select>"
                + "<div class=\"form-check form-switch d-flex align-items-center mx-1\">"
                + "<input class=\"qai-required form-check-input\" type=\"checkbox\" role=\"switch\">"
                + "<label class=\"form-check-label small ms-1 text-nowrap\">required</label>"
                + "</div>"
                + "<button type=\"button\" class=\"qai-del btn btn-sm btn-outline-danger\" title=\"Remove\">&times;</button>"
                + "</div>"
                + "<textarea class=\"qai-choices form-control form-control-sm d-none\" rows=\"3\" "
                + "placeholder=\"One choice per line\"></textarea>"
                + "</div>"
                + "</template>");
        h.append("<div id=\"qai-actions\" class=\"d-none mt-2 d-flex gap-2\">");
        h.append("<button type=\"button\" id=\"qai-save\" class=\"btn btn-success btn-sm\">"
                + "<i class=\"bi bi-check-lg me-1\"></i>Save all questions</button>");
        h.append("<button type=\"button\" id=\"qai-clear\" class=\"btn btn-outline-secondary btn-sm\">Discard</button>");
        h.append("</div>");
        h.append("</div>");
        h.append("<style>")
                .append(".qai-card .qai-preview:empty{display:none}")
                .append(".qai-row .qai-prompt{flex:1 1 auto;min-width:0}")
                .append(".qai-row .qai-kind{flex:0 0 auto}")
                .append("</style>");

        h.append(section("Questions",
                "<p class=\"text-secondary\">Drag the <span class=q-handle-inline>&#8801;</span> handle to reorder. "
                + "Order saves the moment you drop.</p>"));
        h.append("<div id=q-reorder-status class=\"q-status\" role=status aria-live=polite></div>");
        h.append("<div class=\"table-responsive\"><table id=q-table class=\"table table-hover table-sm align-middle q-table\">");
        h.append("<thead><tr><th class=q-handle-col aria-label=\"reorder\"></th>")
                .append("<th>Prompt</th><th>Kind</th><th>Required</th><th>Active</th><th></th></tr></thead>")
                .append("<tbody id=q-tbody>");
        for (Question q : questions) {
            h.append("<tr draggable=true data-id=\"").append(q.id()).append("\" class=q-row>");
            h.append("<td class=q-handle-cell aria-label=\"drag to reorder\">")
                    .append("<span class=q-handle title=\"Drag to reorder\">&#8801;</span></td>");
            h.append("<td>").append(escape(q.prompt())).append("</td>");
            h.append("<td>").append(escape(q.kind().label())).append("</td>");
            h.append("<td class=\"text-center\">")
                    .append("<input type=checkbox class=\"form-check-input q-toggle\" role=switch")
                    .append(" data-id=\"").append(q.id()).append("\" data-field=\"required\"")
                    .append(q.required() ? " checked" : "").append("></td>");
            h.append("<td class=\"text-center\">")
                    .append("<input type=checkbox class=\"form-check-input q-toggle\" role=switch")
                    .append(" data-id=\"").append(q.id()).append("\" data-field=\"active\"")
                    .append(q.active() ? " checked" : "").append("></td>");
            h.append("<td class=\"text-end text-nowrap\">")
                    .append("<a class=\"btn btn-sm btn-outline-primary me-1\" href=\"/dash/config/questions/").append(q.id()).append("/edit\">edit</a>")
                    .append("<form method=post action=\"/dash/config/questions/").append(q.id())
                    .append("/delete\" class=\"d-inline m-0\" data-confirm=\"Delete this question?\" data-confirm-kind=\"danger\">")
                    .append("<button class=\"btn btn-sm btn-outline-danger\">delete</button></form>")
                    .append("</td>");
            h.append("</tr>");
        }
        h.append("</tbody></table></div>");
        h.append("<p><a class=\"btn btn-primary btn-sm\" href=\"/dash/config/questions/new\">+ add question</a></p>");
        h.append("</div>");

        h.append("</div>"); // .cfg-tab-panels
        h.append("</div>"); // .cfg-tabs

        // Sync the active tab into the hidden form field so save() can redirect back.
        h.append("<script>(function(){")
                .append("var f=document.getElementById('active_tab_field');")
                .append("if(!f)return;")
                .append("function sync(){var r=document.querySelector('input[name=\"__cfg_tab\"]:checked');")
                .append("if(r&&r.id&&r.id.indexOf('cfg-')===0)f.value=r.id.substring(4);}")
                .append("sync();")
                .append("document.querySelectorAll('input[name=\"__cfg_tab\"]').forEach(function(i){i.addEventListener('change',sync);});")
                .append("})();</script>");

        // Markdown / placeholder toolbar: inserts text at the textarea caret or wraps the selection.
        h.append("<script>")
                .append("window.tplInsert=function(btn,action,payload){")
                .append("var tb=btn.closest('.tpl-toolbar');if(!tb)return;")
                .append("var ta=tb.parentElement&&tb.parentElement.querySelector('textarea');if(!ta)return;")
                .append("var s=ta.selectionStart,e=ta.selectionEnd;")
                .append("var sel=ta.value.substring(s,e);")
                .append("var before=ta.value.substring(0,s),after=ta.value.substring(e);")
                .append("var ins,ns,ne;")
                .append("if(action==='wrap'){")
                .append("var i=payload.indexOf('|||');")
                .append("var L=i<0?payload:payload.slice(0,i);")
                .append("var R=i<0?payload:payload.slice(i+3);")
                .append("ins=L+sel+R;ns=s+L.length;ne=ns+sel.length;")
                .append("}else if(action==='prefix'){")
                .append("var lines=(sel.length?sel:'').split('\\n');")
                .append("ins=lines.map(function(l){return payload+l;}).join('\\n');")
                .append("ns=ne=s+ins.length;")
                .append("}else if(action==='link'){")
                .append("var t=sel||'text';ins='['+t+'](url)';")
                .append("ns=s+1+t.length+2;ne=ns+3;")
                .append("}else{ins=payload;ns=ne=s+ins.length;}")
                .append("ta.value=before+ins+after;ta.focus();ta.setSelectionRange(ns,ne);")
                .append("ta.dispatchEvent(new Event('input',{bubbles:true}));")
                .append("};</script>");

        // Drag-and-drop reordering for the questions table.
        h.append("<style>")
                .append(".q-table tbody tr.q-row{cursor:default}")
                .append(".q-handle-col{width:2rem;padding:.25rem .5rem!important}")
                .append(".q-handle-cell{width:2rem;text-align:center;padding:.25rem .5rem!important;cursor:grab;user-select:none;color:var(--bs-secondary-color,#6c757d);font-size:1.25rem;line-height:1}")
                .append(".q-handle-cell:active{cursor:grabbing}")
                .append(".q-handle{display:inline-block;padding:.1rem .25rem;border-radius:3px}")
                .append(".q-handle-cell:hover .q-handle{background:rgba(127,127,127,.12)}")
                .append(".q-handle-inline{display:inline-block;padding:0 .2rem;font-size:1.05em;vertical-align:-.05em;opacity:.7}")
                .append(".q-row.dragging{opacity:.35}")
                .append(".q-row.drop-above td{box-shadow:inset 0 2px 0 0 var(--bs-primary,#0d6efd)}")
                .append(".q-row.drop-below td{box-shadow:inset 0 -2px 0 0 var(--bs-primary,#0d6efd)}")
                .append(".q-status{min-height:1.5rem;font-size:.875rem;margin-bottom:.5rem;color:var(--bs-secondary-color,#6c757d)}")
                .append(".q-status.ok{color:var(--bs-success,#198754)}")
                .append(".q-status.err{color:var(--bs-danger,#dc3545)}")
                .append("</style>");

        h.append("<script>(function(){")
                .append("var tbody=document.getElementById('q-tbody');if(!tbody)return;")
                .append("var status=document.getElementById('q-reorder-status');")
                .append("var dragging=null;")
                // Drag is initiated from anywhere on the row, but the handle cursor signals where.
                .append("function rows(){return Array.prototype.slice.call(tbody.querySelectorAll('tr.q-row'));}")
                .append("function clearMarkers(){rows().forEach(function(r){r.classList.remove('drop-above','drop-below');});}")
                .append("function setStatus(text,kind){if(!status)return;status.textContent=text||'';status.className='q-status '+(kind||'');}")
                .append("function currentOrder(){return rows().map(function(r){return r.getAttribute('data-id');});}")
                .append("var lastSavedOrder=currentOrder().join(',');")
                .append("function save(){")
                .append("var ids=currentOrder();var joined=ids.join(',');")
                .append("if(joined===lastSavedOrder){return;}")
                .append("setStatus('Saving order...');")
                .append("var body=new URLSearchParams();body.set('ids',joined);")
                .append("fetch('/dash/config/questions/reorder',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString(),credentials:'same-origin'})")
                .append(".then(function(res){if(!res.ok)throw new Error('HTTP '+res.status);lastSavedOrder=joined;setStatus('Saved.','ok');setTimeout(function(){if(status&&status.textContent==='Saved.')setStatus('');},1500);})")
                .append(".catch(function(e){setStatus('Could not save order: '+e.message+'. Refresh to retry.','err');});")
                .append("}")
                .append("tbody.addEventListener('dragstart',function(e){")
                .append("var tr=e.target.closest('tr.q-row');if(!tr){e.preventDefault();return;}")
                .append("dragging=tr;tr.classList.add('dragging');")
                .append("if(e.dataTransfer){e.dataTransfer.effectAllowed='move';try{e.dataTransfer.setData('text/plain',tr.getAttribute('data-id'));}catch(_){}}")
                .append("});")
                .append("tbody.addEventListener('dragend',function(){if(dragging)dragging.classList.remove('dragging');dragging=null;clearMarkers();});")
                .append("tbody.addEventListener('dragover',function(e){")
                .append("if(!dragging)return;")
                .append("var tr=e.target.closest('tr.q-row');if(!tr||tr===dragging)return;")
                .append("e.preventDefault();if(e.dataTransfer)e.dataTransfer.dropEffect='move';")
                .append("var rect=tr.getBoundingClientRect();var before=(e.clientY-rect.top)<(rect.height/2);")
                .append("clearMarkers();tr.classList.add(before?'drop-above':'drop-below');")
                .append("});")
                .append("tbody.addEventListener('dragleave',function(e){")
                .append("var tr=e.target.closest('tr.q-row');if(tr)tr.classList.remove('drop-above','drop-below');")
                .append("});")
                .append("tbody.addEventListener('drop',function(e){")
                .append("if(!dragging)return;")
                .append("var tr=e.target.closest('tr.q-row');if(!tr||tr===dragging){clearMarkers();return;}")
                .append("e.preventDefault();")
                .append("var rect=tr.getBoundingClientRect();var before=(e.clientY-rect.top)<(rect.height/2);")
                .append("if(before){tr.parentNode.insertBefore(dragging,tr);}else{tr.parentNode.insertBefore(dragging,tr.nextSibling);}")
                .append("clearMarkers();save();")
                .append("});")
                .append("})();</script>");

        // Required / Active checkboxes - delegated so any row (even ones added
        // by future JS) participates. Posts the new value and reverts the
        // checkbox if the server rejects the change.
        h.append("<script>(function(){")
                .append("var tbody=document.getElementById('q-tbody');if(!tbody)return;")
                .append("var status=document.getElementById('q-reorder-status');")
                .append("function setStatus(text,kind){if(!status)return;status.textContent=text||'';status.className='q-status '+(kind||'');}")
                .append("tbody.addEventListener('change',function(e){")
                .append("var cb=e.target;if(!cb||!cb.classList.contains('q-toggle'))return;")
                .append("var id=cb.getAttribute('data-id');var field=cb.getAttribute('data-field');")
                .append("if(!id||!field)return;")
                .append("var was=!cb.checked;")
                .append("cb.disabled=true;setStatus('Saving '+field+'...');")
                .append("var body=new URLSearchParams();body.set('field',field);body.set('value',cb.checked?'1':'0');")
                .append("fetch('/dash/config/questions/'+encodeURIComponent(id)+'/toggle',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString()})")
                .append(".then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);setStatus('Saved.','ok');setTimeout(function(){if(status&&status.textContent==='Saved.')setStatus('');},1500);})")
                .append(".catch(function(e){cb.checked=was;setStatus('Could not save: '+e.message,'err');})")
                .append(".finally(function(){cb.disabled=false;});")
                .append("});")
                .append("})();</script>");

        // AI question-generation panel: brief -> preview -> bulk save.
        h.append("<script>(function(){")
                .append("var briefEl=document.getElementById('qai-brief');")
                .append("var countEl=document.getElementById('qai-count');")
                .append("var genBtn=document.getElementById('qai-gen');")
                .append("var saveBtn=document.getElementById('qai-save');")
                .append("var clearBtn=document.getElementById('qai-clear');")
                .append("var actions=document.getElementById('qai-actions');")
                .append("var preview=document.getElementById('qai-preview');")
                .append("var tpl=document.getElementById('qai-row-tpl');")
                .append("var status=document.getElementById('qai-status');")
                .append("if(!briefEl||!genBtn||!preview||!tpl)return;")
                .append("function setStatus(t,k){if(!status)return;status.textContent=t||'';status.className='q-status ms-2 '+(k||'');}")
                .append("function syncChoicesVisibility(row){")
                .append("var k=row.querySelector('.qai-kind').value;")
                .append("var ta=row.querySelector('.qai-choices');")
                .append("var needs=(k==='single_choice'||k==='multi_choice');")
                .append("ta.classList.toggle('d-none',!needs);")
                .append("}")
                .append("function buildRow(q){")
                .append("var frag=tpl.content.cloneNode(true);")
                .append("var row=frag.querySelector('.qai-row');")
                .append("row.querySelector('.qai-prompt').value=q.prompt||'';")
                .append("var sel=row.querySelector('.qai-kind');")
                .append("sel.value=(q.kind==='long_text'||q.kind==='single_choice'||q.kind==='multi_choice')?q.kind:'short_text';")
                .append("row.querySelector('.qai-required').checked=q.required!==false;")
                .append("row.querySelector('.qai-choices').value=Array.isArray(q.choices)?q.choices.join('\\n'):'';")
                .append("syncChoicesVisibility(row);")
                .append("sel.addEventListener('change',function(){syncChoicesVisibility(row);});")
                .append("row.querySelector('.qai-del').addEventListener('click',function(){row.remove();if(!preview.querySelector('.qai-row'))actions.classList.add('d-none');});")
                .append("return row;")
                .append("}")
                .append("genBtn.addEventListener('click',function(){")
                .append("var brief=(briefEl.value||'').trim();")
                .append("if(!brief){setStatus('Write a brief first.','err');briefEl.focus();return;}")
                .append("var count=parseInt(countEl.value||'5',10);if(!(count>=1&&count<=15))count=5;")
                .append("preview.innerHTML='';actions.classList.add('d-none');")
                .append("genBtn.disabled=true;var orig=genBtn.innerHTML;")
                .append("genBtn.innerHTML='<span class=\"spinner-border spinner-border-sm me-1\" role=status aria-hidden=true></span>Drafting...';")
                .append("setStatus('Asking the AI...','');")
                .append("var body=new URLSearchParams();body.set('brief',brief);body.set('count',String(count));")
                .append("fetch('/dash/config/questions/ai-generate',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString()})")
                .append(".then(function(r){return r.json().then(function(j){return{status:r.status,json:j};});})")
                .append(".then(function(p){")
                .append("if(!p.json||!p.json.ok){setStatus((p.json&&p.json.message)||('HTTP '+p.status),'err');return;}")
                .append("var qs=p.json.questions||[];")
                .append("if(!qs.length){setStatus('AI returned no questions. Try a more specific brief.','err');return;}")
                .append("qs.forEach(function(q){preview.appendChild(buildRow(q));});")
                .append("actions.classList.remove('d-none');")
                .append("setStatus('Drafted '+qs.length+' question'+(qs.length===1?'':'s')+'. Edit and click Save.','ok');")
                .append("})")
                .append(".catch(function(e){setStatus('Network error: '+(e.message||e),'err');})")
                .append(".finally(function(){genBtn.disabled=false;genBtn.innerHTML=orig;});")
                .append("});")
                .append("if(clearBtn)clearBtn.addEventListener('click',function(){preview.innerHTML='';actions.classList.add('d-none');setStatus('','');});")
                .append("if(saveBtn)saveBtn.addEventListener('click',function(){")
                .append("var rows=Array.prototype.slice.call(preview.querySelectorAll('.qai-row'));")
                .append("if(!rows.length){setStatus('Nothing to save.','err');return;}")
                .append("var payload=[];")
                .append("for(var i=0;i<rows.length;i++){")
                .append("var r=rows[i];")
                .append("var prompt=(r.querySelector('.qai-prompt').value||'').trim();")
                .append("if(!prompt)continue;")
                .append("var kind=r.querySelector('.qai-kind').value;")
                .append("var required=r.querySelector('.qai-required').checked;")
                .append("var choices=[];")
                .append("if(kind==='single_choice'||kind==='multi_choice'){")
                .append("choices=(r.querySelector('.qai-choices').value||'').split(/\\r?\\n/).map(function(s){return s.trim();}).filter(function(s){return s.length>0;});")
                .append("if(choices.length<2){setStatus('Question '+(i+1)+' needs at least 2 choices, or pick a text kind.','err');return;}")
                .append("}")
                .append("payload.push({prompt:prompt,kind:kind,choices:choices,required:required});")
                .append("}")
                .append("if(!payload.length){setStatus('Nothing to save - every question is blank.','err');return;}")
                .append("saveBtn.disabled=true;var orig=saveBtn.innerHTML;")
                .append("saveBtn.innerHTML='<span class=\"spinner-border spinner-border-sm me-1\" role=status aria-hidden=true></span>Saving...';")
                .append("setStatus('Saving '+payload.length+' question'+(payload.length===1?'':'s')+'...','');")
                .append("var body=new URLSearchParams();body.set('payload',JSON.stringify(payload));")
                .append("fetch('/dash/config/questions/ai-save',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString()})")
                .append(".then(function(r){return r.json().then(function(j){return{status:r.status,json:j};});})")
                .append(".then(function(p){")
                .append("if(!p.json||!p.json.ok){setStatus((p.json&&p.json.message)||('HTTP '+p.status),'err');return;}")
                .append("setStatus('Saved '+p.json.added+' question'+(p.json.added===1?'':'s')+'. Reloading...','ok');")
                .append("setTimeout(function(){window.location.href='/dash/config?tab=questions';},600);")
                .append("})")
                .append(".catch(function(e){setStatus('Network error: '+(e.message||e),'err');})")
                .append(".finally(function(){saveBtn.disabled=false;saveBtn.innerHTML=orig;});")
                .append("});")
                .append("})();</script>");

        // Test-connection button on the AI tab. Posts the current form values
        // (or falls back to the saved api key if the password field is empty,
        // since we never render the saved key into the page).
        h.append("<script>(function(){")
                .append("var btn=document.getElementById('cfg-llm-test');")
                .append("var out=document.getElementById('cfg-llm-test-result');")
                .append("if(!btn||!out)return;")
                .append("btn.addEventListener('click',function(){")
                .append("var k=document.getElementById('f_llm_api_key');")
                .append("var b=document.getElementById('f_llm_base_url');")
                .append("var m=document.getElementById('f_llm_model');")
                .append("var body=new URLSearchParams();")
                .append("body.set('api_key',k?k.value:'');")
                .append("body.set('base_url',b?b.value:'');")
                .append("body.set('model',m?m.value:'');")
                .append("btn.disabled=true;var orig=btn.innerHTML;")
                .append("btn.innerHTML='<span class=\"spinner-border spinner-border-sm me-1\" role=status aria-hidden=true></span>Asking...';")
                .append("out.className='cfg-llm-test-result loading';")
                .append("out.textContent='Calling the gateway...';")
                .append("fetch('/dash/config/llm/test',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString(),credentials:'same-origin'})")
                .append(".then(function(r){return r.json().then(function(j){return {status:r.status,json:j};});})")
                .append(".then(function(p){")
                .append("if(p.json&&p.json.ok){")
                .append("out.className='cfg-llm-test-result ok';")
                .append("out.innerHTML='<div class=\"cfg-llm-meta\"><i class=\"bi bi-check-circle\"></i> '")
                .append("+'OK ('+p.json.elapsed_ms+'ms · '+escapeHtml(p.json.model||'')+' @ '+escapeHtml(p.json.base_url||'')+')</div>'")
                .append("+'<blockquote class=\"cfg-llm-joke\">'+escapeHtml(p.json.joke||'(empty response)')+'</blockquote>';")
                .append("}else{")
                .append("out.className='cfg-llm-test-result err';")
                .append("out.innerHTML='<div class=\"cfg-llm-meta\"><i class=\"bi bi-x-circle\"></i> Failed</div>'")
                .append("+'<pre class=\"cfg-llm-err\">'+escapeHtml((p.json&&p.json.message)||('HTTP '+p.status))+'</pre>';")
                .append("}")
                .append("})")
                .append(".catch(function(e){")
                .append("out.className='cfg-llm-test-result err';")
                .append("out.innerHTML='<div class=\"cfg-llm-meta\"><i class=\"bi bi-x-circle\"></i> Network error</div>'")
                .append("+'<pre class=\"cfg-llm-err\">'+escapeHtml(e.message||String(e))+'</pre>';")
                .append("})")
                .append(".finally(function(){btn.disabled=false;btn.innerHTML=orig;});")
                .append("});")
                .append("function escapeHtml(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&#39;');}")
                .append("})();</script>");

        // Styles for the test-result block and AI tab badges.
        h.append("<style>")
                .append(".cfg-llm-test-result{margin-top:.75rem;padding:.75rem 1rem;border-radius:.5rem;font-size:.92rem;display:none}")
                .append(".cfg-llm-test-result.loading,.cfg-llm-test-result.ok,.cfg-llm-test-result.err{display:block}")
                .append(".cfg-llm-test-result.loading{background:rgba(127,127,127,.08);color:var(--bs-secondary-color)}")
                .append(".cfg-llm-test-result.ok{background:rgba(25,135,84,.08);border:1px solid rgba(25,135,84,.25);color:var(--bs-success-text-emphasis,#0a6c44)}")
                .append(".cfg-llm-test-result.err{background:rgba(220,53,69,.08);border:1px solid rgba(220,53,69,.25);color:var(--bs-danger-text-emphasis,#a02834)}")
                .append(".cfg-llm-meta{font-weight:600;margin-bottom:.35rem}")
                .append(".cfg-llm-meta .bi{margin-right:.25rem}")
                .append(".cfg-llm-joke{margin:0;padding:.5rem .75rem;border-left:3px solid rgba(25,135,84,.5);background:var(--bs-body-bg);color:var(--bs-body-color);border-radius:.25rem;font-style:italic;white-space:pre-wrap}")
                .append(".cfg-llm-err{margin:0;padding:.5rem .75rem;background:var(--bs-body-bg);color:var(--bs-body-color);border-radius:.25rem;white-space:pre-wrap;word-break:break-word;font-size:.85rem}")
                .append("</style>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    /** Tab definitions in display order. The first one is the default. */
    private record TabDef(String id, String label) {}
    private static final List<TabDef> TABS = List.of(
            new TabDef("roles", "Roles & channels"),
            new TabDef("delivery", "Delivery"),
            new TabDef("entry", "Entry"),
            new TabDef("geoip", "GeoIP"),
            new TabDef("gating", "Gating"),
            new TabDef("triage", "Triage"),
            new TabDef("ai", "AI"),
            new TabDef("approve", "Approve"),
            new TabDef("deny", "Deny"),
            new TabDef("rules", "Rules"),
            new TabDef("landing", "Landing"),
            new TabDef("modules", "Modules"),
            new TabDef("questions", "Questions")
    );

    /**
     * Toggleable feature modules surfaced on the Modules tab. Order = render
     * order on the page. The {@code key} matches a {@link io.warden.config.WardenConfig.Modules}
     * field; the {@code ymlKey} is the {@code config.yml} key written on save.
     */
    private record ModuleDef(String key, String ymlKey, String label, String description) {}

    private static final List<ModuleDef> MODULES = List.of(
            new ModuleDef("moderation", "moderation", "Moderation",
                    "Server moderation toolkit. Adds automod (banned words, spam filter, link/invite blocking), "
                    + "raid protection (mass-join lockdown), and warn / kick / ban / timeout commands. "
                    + "Edits policies on /dash/moderation. Mod actions land in the audit log."),
            new ModuleDef("violations", "violations", "Violations (Grim anticheat)",
                    "Read-only viewer for the Grim anticheat plugin's violation database. Surfaces flagged "
                    + "players and packet-level evidence on /dash/violations. Takes no action on its own. "
                    + "Inert when Grim isn't installed - the page shows an install prompt."),
            new ModuleDef("levels", "levels", "Levels and XP",
                    "Discord chat XP, levels, and rank-up announcements. Optionally awards XP for Minecraft "
                    + "chat too (via DiscordSRV link). /dash/levels manages the XP curve, multipliers, and "
                    + "role rewards. Adds /rank and /leaderboard Discord commands."),
            new ModuleDef("reaction_roles", "reaction_roles", "Reaction roles",
                    "Self-assignable Discord roles via reactions or buttons. /dash/reaction-roles builds the "
                    + "panel and posts it to a channel; members click to grant or remove the linked role "
                    + "themselves. Useful for pronouns, ping opt-in, region tags."),
            new ModuleDef("engagement", "engagement", "Polls and giveaways",
                    "Discord polls (single or multi-choice with deadlines) and giveaways (random-winner "
                    + "draws with role/level requirements). /dash/engagement creates and tracks them. "
                    + "Background scheduler closes polls and draws winners on time."),
            new ModuleDef("tickets", "tickets", "Tickets",
                    "Private support tickets - members open a private thread from a Discord panel, staff "
                    + "reply via Discord or /dash/tickets. Tracks categories, assignees, transcripts, and "
                    + "attachments. Closed tickets get a shareable transcript link in the closing DM."),
            new ModuleDef("feedback", "feedback", "Feedback",
                    "Suggestion box - members post ideas in a configured Discord channel, the bot adds "
                    + "upvote/downvote reactions, staff triage on /dash/feedback (accept / reject / "
                    + "planned / shipped). Independent of Tickets - public-by-design, no private threads."),
            new ModuleDef("alerts", "alerts", "Alerts",
                    "Event-driven Discord notifications: when a Minecraft or Discord event fires (player "
                    + "death, world load, member ban, etc.) the bot posts a configurable embed to a "
                    + "channel. /dash/alerts manages rules with SpEL placeholders and per-event filters."),
            new ModuleDef("autoresponders", "autoresponders", "Autoresponders",
                    "Pattern-matched chat replies in Discord. Define a keyword or regex; the bot answers "
                    + "matching messages with the canned response you set on /dash/autoresponders. Useful "
                    + "for FAQ-style answers (\"how do I link my account?\", \"where's the IP?\")."),
            new ModuleDef("events_timezones", "events_timezones", "Events and timezones",
                    "Scheduled events with RSVPs rendered in each member's local timezone. Members pick "
                    + "their timezone at /tz; staff schedule events on /dash/scheduler. Disabling this "
                    + "removes both the scheduler and the /tz timezone picker.")
    );

    /**
     * Render the Modules tab panel. Lives outside the main settings form
     * because module toggles are persisted to {@code config.yml} (not the
     * settings DB row) and require a plugin restart to actually take effect -
     * mixing the two save semantics in one button would mislead operators
     * about when the change goes live.
     *
     * Checkbox state reflects the SAVED value in config.yml (the operator's
     * intent), not the currently-running state. Where the two diverge, the
     * "pending apply" banner spells it out and offers a restart button.
     */
    private static String modulesPanel(io.warden.WardenPlugin plugin,
                                       io.warden.config.WardenConfig.Modules loaded) {
        var cfg = plugin.getConfig();
        // Pending = modules whose saved state differs from the in-memory state
        // we booted with. Saving in this tab updates the former; only a plugin
        // restart updates the latter. Map values are "savedValue" (what will be
        // active after restart) for clarity in the banner.
        java.util.Map<String, Boolean> pending = new java.util.LinkedHashMap<>();
        for (ModuleDef md : MODULES) {
            boolean saved = cfg.getBoolean("modules." + md.ymlKey(), true);
            boolean active = moduleEnabled(loaded, md.key());
            if (saved != active) pending.put(md.ymlKey(), saved);
        }

        StringBuilder h = new StringBuilder(8192);
        h.append("<div class=cfg-tab-panel data-tab=modules>");
        h.append(section("Feature modules",
                "<p class=\"text-secondary\">Each toggle gates a complete subsystem - "
                + "Discord listeners, dashboard page, REST API routes, sidebar entry, and "
                + "background jobs - for that module. Turning one off is equivalent to "
                + "the feature not being installed: the code never runs, and nothing "
                + "from it appears in the sidebar.</p>"
                + "<p class=\"text-secondary small mb-0\">Saved here, applied by a "
                + "plugin restart - <code>/warden reload</code> alone won't re-bind "
                + "listeners or HTTP routes. Use the <strong>Apply changes</strong> "
                + "button after saving to do it in one click.</p>"));

        // Pending-apply banner. Renders only when config.yml diverges from the
        // running state. Includes a one-click restart that does the full
        // disable+enable cycle so module changes actually go live.
        h.append("<div id=modules-pending-banner class=\"modules-pending-banner");
        if (pending.isEmpty()) h.append(" d-none");
        h.append("\" data-pending=\"").append(pending.size()).append("\">");
        h.append("<div class=\"alert alert-warning d-flex flex-wrap align-items-center justify-content-between gap-3 mb-3\" role=alert>")
                .append("<div class=\"flex-grow-1\">")
                .append("<div class=\"d-flex align-items-center gap-2 fw-semibold mb-1\">")
                .append("<i class=\"bi bi-exclamation-triangle-fill text-warning\"></i>")
                .append("<span id=modules-pending-headline>");
        if (pending.isEmpty()) {
            h.append("Pending apply");
        } else if (pending.size() == 1) {
            h.append("1 module change is waiting for a plugin restart");
        } else {
            h.append(pending.size()).append(" module changes are waiting for a plugin restart");
        }
        h.append("</span></div>")
                .append("<div class=\"small text-secondary\" id=modules-pending-detail>");
        if (!pending.isEmpty()) {
            h.append("Saved values differ from what's running: <code>")
                    .append(escape(String.join(", ", pending.keySet())))
                    .append("</code>. Apply now to disable/enable Warden so the new wiring takes effect.");
        }
        h.append("</div></div>")
                .append("<button type=button id=modules-restart-btn class=\"btn btn-warning\">")
                .append("<i class=\"bi bi-arrow-clockwise me-1\"></i>Apply changes (restart plugin)")
                .append("</button>")
                .append("</div></div>");

        h.append("<form method=post action=\"/dash/config/modules\" id=modules-form>");
        h.append("<input type=hidden name=active_tab value=\"modules\">");
        h.append("<div class=\"modules-grid\">");
        for (ModuleDef md : MODULES) {
            boolean saved = cfg.getBoolean("modules." + md.ymlKey(), true);
            boolean active = moduleEnabled(loaded, md.key());
            boolean differs = saved != active;
            String inputId = "mod_" + md.ymlKey();
            h.append("<div class=\"module-card border rounded p-3 mb-3 bg-body-tertiary")
                    .append(differs ? " module-card-pending" : "")
                    .append("\">")
                    .append("<div class=\"form-check form-switch d-flex align-items-start gap-2\">")
                    .append("<input type=hidden name=\"module_").append(md.ymlKey()).append("\" value=\"off\">")
                    .append("<input class=\"form-check-input mt-1\" type=checkbox role=switch")
                    .append(" id=\"").append(inputId).append("\"")
                    .append(" name=\"module_").append(md.ymlKey()).append("\" value=\"on\"")
                    .append(" data-active=\"").append(active ? "1" : "0").append("\"")
                    .append(saved ? " checked" : "")
                    .append(">")
                    .append("<label class=\"form-check-label flex-grow-1\" for=\"").append(inputId).append("\">")
                    .append("<div class=\"d-flex align-items-center gap-2\">")
                    .append("<span class=\"fw-semibold\">").append(escape(md.label())).append("</span>");
            if (differs) {
                String pendingLabel = saved ? "will turn ON after restart" : "will turn OFF after restart";
                h.append("<span class=\"badge text-bg-warning-subtle text-warning-emphasis fw-normal\">")
                        .append("<i class=\"bi bi-hourglass-split me-1\"></i>")
                        .append(escape(pendingLabel))
                        .append("</span>");
            }
            h.append("</div>")
                    .append("<div class=\"text-secondary small mt-1\">").append(escape(md.description())).append("</div>")
                    .append("</label>")
                    .append("</div></div>");
        }
        h.append("</div>");
        h.append("<div class=\"save-row mt-3 pt-3 border-top d-flex align-items-center gap-3 flex-wrap\">")
                .append("<button type=submit class=\"btn btn-success\" id=modules-save-btn>Save modules</button>")
                .append("<span class=\"text-secondary small\">Save writes to config.yml. Click <strong>Apply changes</strong> above to restart the plugin and make the changes live.</span>")
                .append("</div>");
        h.append("</form>");

        // Restart-progress overlay. Hidden by default; the JS swaps in title +
        // body text as it cycles through requesting / waiting / back-online.
        h.append("<div id=modules-restart-overlay class=\"modules-restart-overlay d-none\" role=dialog aria-modal=true>")
                .append("<div class=\"mro-card\">")
                .append("<div class=\"mro-spinner spinner-border text-primary mb-3\" role=status>")
                .append("<span class=\"visually-hidden\">Restarting...</span></div>")
                .append("<h5 id=mro-title class=\"mb-2\">Restarting Warden...</h5>")
                .append("<p id=mro-body class=\"text-secondary mb-0\">Web and Discord are briefly offline while listeners and routes are re-bound.</p>")
                .append("</div></div>");

        h.append("<style>")
                .append(".module-card{transition:background-color .12s,border-color .12s}")
                .append(".module-card:has(input[type=checkbox]:checked){background:rgba(25,135,84,.06);border-color:rgba(25,135,84,.30) !important}")
                .append(".module-card.module-card-pending{border-color:rgba(255,193,7,.55) !important;box-shadow:inset 3px 0 0 var(--bs-warning)}")
                .append(".module-card .form-check-label{cursor:pointer}")
                .append(".modules-pending-banner.d-none{display:none}")
                .append(".modules-restart-overlay{position:fixed;inset:0;background:rgba(0,0,0,.6);")
                .append("display:flex;align-items:center;justify-content:center;z-index:2000;backdrop-filter:blur(2px)}")
                .append(".modules-restart-overlay.d-none{display:none}")
                .append(".modules-restart-overlay .mro-card{background:var(--bs-body-bg);color:var(--bs-body-color);")
                .append("padding:2rem 2.5rem;border-radius:.6rem;text-align:center;max-width:26rem;")
                .append("box-shadow:0 20px 60px rgba(0,0,0,.4)}")
                // Self-contained spinner so the modal works even if Bootstrap's
                // spinner-border @keyframes are missing or paused for any reason
                // (some pages, prefers-reduced-motion at the OS level, etc.).
                .append("@keyframes mroSpin{to{transform:rotate(360deg)}}")
                .append(".modules-restart-overlay .mro-spinner{display:inline-block;width:2.5rem;height:2.5rem;")
                .append("border:.28em solid currentColor;border-right-color:transparent;border-radius:50%;")
                .append("animation:mroSpin .75s linear infinite;color:var(--bs-primary,#0d6efd);margin-bottom:1rem}")
                .append("@keyframes wardenBtnPulse{0%{box-shadow:0 0 0 0 rgba(25,135,84,.45)}70%{box-shadow:0 0 0 8px rgba(25,135,84,0)}100%{box-shadow:0 0 0 0 rgba(25,135,84,0)}}")
                .append(".btn-pulse{animation:wardenBtnPulse 1.6s ease-out infinite}")
                .append("</style>");

        h.append("<script>").append(modulesPanelScript(pending.size())).append("</script>");
        h.append("</div>");
        return h.toString();
    }

    /**
     * Client-side glue for the Modules tab:
     *  - Tracks form dirtiness (any toggle change) and adds a beforeunload
     *    guard whenever the saved state differs from what's running OR the
     *    form has unsaved edits, so an operator can't navigate away thinking
     *    the change went live.
     *  - Restart button does the apply: POSTs /dash/config/restart-plugin,
     *    shows a modal overlay, then polls /health until Javalin is back.
     *    Once /health responds OK, full-page reload with a success flash.
     */
    private static String modulesPanelScript(int initialPending) {
        return "(function(){"
                + "var pendingCount=" + initialPending + ";"
                + "var formDirty=false;"
                + "var restarting=false;"
                + "var form=document.getElementById('modules-form');"
                + "var restartBtn=document.getElementById('modules-restart-btn');"
                + "var banner=document.getElementById('modules-pending-banner');"
                + "var saveBtn=document.getElementById('modules-save-btn');"
                + "if(!form||!restartBtn||!banner)return;"
                // Mark dirty on any toggle change. Also surface a soft hint on
                // the Save button so the operator notices unsaved state.
                + "form.querySelectorAll('input[type=checkbox]').forEach(function(cb){"
                + "cb.addEventListener('change',function(){"
                + "formDirty=true;"
                + "if(saveBtn){saveBtn.classList.add('btn-pulse');}"
                + "});});"
                // The submit triggers a server-side redirect; once we're saving,
                // drop the dirty flag so the impending navigation doesn't trip
                // beforeunload. The pending banner state arrives fresh from the
                // server on the reload.
                + "form.addEventListener('submit',function(){formDirty=false;});"
                // beforeunload: only fire when the user is genuinely abandoning
                // unapplied state. Skipped while a restart is in flight so the
                // /health-driven reload isn't blocked.
                + "window.addEventListener('beforeunload',function(e){"
                + "if(restarting)return;"
                + "if(pendingCount>0||formDirty){"
                + "e.preventDefault();e.returnValue='';return '';"
                + "}});"
                // Restart click: confirm, drop the guard, kick the cycle.
                + "restartBtn.addEventListener('click',function(){"
                + "if(restarting)return;"
                + "var doIt=function(){restartNow();};"
                + "if(window.WardenConfirm){"
                + "window.WardenConfirm({"
                + "title:'Restart Warden now?',"
                + "message:'Discord disconnects and the dashboard goes briefly offline while listeners and routes are re-bound. Usually 3-5 seconds.',"
                + "okLabel:'Restart now'"
                + "}).then(function(ok){if(ok)doIt();});"
                + "}else if(window.confirm('Restart Warden now? Discord and the dashboard will briefly go offline.')){doIt();}"
                + "});"
                + "function restartNow(){"
                + "restarting=true;"
                + "showOverlay('Restarting Warden...','Capturing current state.');"
                // Capture the current boot_id from /health, then fire the restart
                // and poll for the value to change. This is robust to fast
                // in-process restarts where the /health downtime window is too
                // short to catch with a "must see down first" probe.
                + "fetch('/health',{cache:'no-store'}).then(function(r){return r.ok?r.json():null;})"
                + ".catch(function(){return null;})"
                + ".then(function(h){"
                + "var prevBootId=(h&&typeof h.boot_id!=='undefined')?h.boot_id:null;"
                + "showOverlay('Restarting Warden...','Sending the restart request.');"
                + "fetch('/dash/config/restart-plugin',{method:'POST',credentials:'same-origin'})"
                + ".then(function(r){return r.json().then(function(j){return {ok:r.ok,json:j};});})"
                + ".catch(function(){return {ok:true,json:{ok:true}};})" // connection drop = restart already firing
                + ".then(function(p){"
                + "if(!p.json||p.json.ok===false){failOverlay((p.json&&p.json.message)||'Restart request failed.');restarting=false;return;}"
                + "showOverlay('Restarting Warden...','Web and Discord are offline while listeners are re-bound.');"
                + "setTimeout(function(){pollHealth(prevBootId);},1200);"
                + "});"
                + "});"
                + "}"
                // Boot-id health probe: the server bumps boot_id on every
                // bootstrap(), so we just need to see it differ from what we
                // captured before kicking the restart. If we couldn't read a
                // prev boot_id, fall back to "wait for /health to come back
                // OK at all" - still better than the old two-phase check that
                // races the sub-second teardown/bootstrap cycle.
                + "var pollAttempts=0;"
                + "function pollHealth(prevBootId){"
                + "pollAttempts++;"
                + "if(pollAttempts>60){failOverlay('Plugin did not come back after ~60 seconds. Check the server console.');return;}"
                + "fetch('/health',{cache:'no-store'}).then(function(r){"
                + "if(!r||!r.ok){"
                + "showOverlay('Restarting Warden...','Plugin is down. Waiting for it to come back.');"
                + "setTimeout(function(){pollHealth(prevBootId);},800);"
                + "return;"
                + "}"
                + "return r.json().then(function(j){"
                + "var curBootId=(j&&typeof j.boot_id!=='undefined')?j.boot_id:null;"
                + "var bumped=(prevBootId!==null&&curBootId!==null&&curBootId!==prevBootId);"
                + "var fallback=(prevBootId===null&&pollAttempts>=2);"
                + "if(bumped||fallback){"
                + "showOverlay('Back online','Reconnecting...');"
                + "setTimeout(reloadPage,400);"
                + "}else{"
                + "showOverlay('Restarting Warden...','Waiting for the new instance to come up.');"
                + "setTimeout(function(){pollHealth(prevBootId);},800);"
                + "}"
                + "});"
                + "}).catch(function(){"
                + "showOverlay('Restarting Warden...','Plugin is down. Waiting for it to come back.');"
                + "setTimeout(function(){pollHealth(prevBootId);},800);"
                + "});"
                + "}"
                + "function reloadPage(){"
                // Full document refresh against the new Javalin instance. Using
                // a flash-carrying URL via location.replace so the success toast
                // fires on the fresh page and the transient restart URL doesn't
                // pollute the browser back-stack.
                + "var url='/dash/config?tab=modules&flash='+encodeURIComponent('Plugin restarted - modules now match the saved values.')+'&flash_kind=success';"
                + "try{window.location.replace(url);}catch(_){window.location.href=url;}"
                + "}"
                + "function showOverlay(title,body){"
                + "var ov=document.getElementById('modules-restart-overlay');"
                + "var t=document.getElementById('mro-title');"
                + "var b=document.getElementById('mro-body');"
                + "if(t)t.textContent=title;if(b)b.textContent=body;"
                + "if(ov){ov.classList.remove('d-none');}"
                + "}"
                + "function failOverlay(msg){"
                + "var t=document.getElementById('mro-title');"
                + "var b=document.getElementById('mro-body');"
                + "var sp=document.querySelector('#modules-restart-overlay .mro-spinner');"
                + "if(sp)sp.classList.add('d-none');"
                + "if(t)t.textContent='Restart failed';"
                + "if(b)b.textContent=msg;"
                + "}"
                + "})();";
    }

    private static boolean moduleEnabled(io.warden.config.WardenConfig.Modules m, String key) {
        return switch (key) {
            case "moderation"        -> m.moderation();
            case "violations"        -> m.violations();
            case "levels"            -> m.levels();
            case "reaction_roles"    -> m.reactionRoles();
            case "engagement"        -> m.engagement();
            case "tickets"           -> m.tickets();
            case "feedback"          -> m.feedback();
            case "alerts"            -> m.alerts();
            case "autoresponders"    -> m.autoresponders();
            case "events_timezones"  -> m.eventsTimezones();
            default -> true;
        };
    }

    /**
     * POST /dash/config/modules - writes the module toggles into the live
     * Bukkit FileConfiguration and persists to config.yml. The new values
     * become visible to {@link io.warden.config.WardenConfig#load} on the
     * next plugin start; the running JDA listeners and HTTP routes were
     * registered at boot from the previous values and are not re-wired here.
     */
    public void saveModules(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null || !sess.canEditConfig()) {
            ctx.status(401).html("<h1>401</h1><p>Modules require config-admin access.</p>");
            return;
        }
        var cfg = plugin.getConfig();
        java.util.Map<String, Boolean> diff = new java.util.LinkedHashMap<>();
        for (ModuleDef md : MODULES) {
            boolean on = bool(ctx, "module_" + md.ymlKey());
            boolean was = cfg.getBoolean("modules." + md.ymlKey(), true);
            if (on != was) diff.put(md.ymlKey(), on);
            cfg.set("modules." + md.ymlKey(), on);
        }
        plugin.saveConfig();
        services.audit.write("web-cfgadmin", "modules_updated", AuditActor.modDiscordId(ctx),
                AuditActor.payload(ctx, java.util.Map.of("changed", diff)));
        String msg = diff.isEmpty()
                ? "No module changes."
                : "Saved. Restart the server (or fully reload Warden) to apply: "
                        + String.join(", ", diff.keySet());
        ctx.redirect(Layout.flashRedirect("/dash/config?tab=modules",
                diff.isEmpty() ? "info" : "success", msg));
    }

    /**
     * POST /dash/config/restart-plugin - cycles every Warden subsystem in
     * place. This is the only way to actually apply module toggles:
     * {@code /warden reload} only refreshes the in-memory config, it does not
     * re-wire JDA listeners or HTTP routes.
     *
     * Why we don't go through pm.disablePlugin / pm.enablePlugin: on modern
     * Paper, disable closes the plugin classloader's jar, after which
     * anything that still loads classes from it (JDA's WebSocket onShutdown,
     * Javalin's classpath static-files handler) throws "zip file closed" and
     * the web service fails to come back up. {@link io.warden.WardenPlugin#restartSubsystems()}
     * keeps the plugin instance + classloader alive and just cycles the
     * services.
     *
     * The cycle is delayed by 1 second so the 202 response flushes cleanly
     * before Javalin shuts down. The client then polls {@code /health} until
     * the web server comes back, which is the signal that the new module
     * wiring is live.
     */
    public void restartPlugin(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null || !sess.canEditConfig()) {
            ctx.status(401).json(java.util.Map.of(
                    "ok", false,
                    "error", "forbidden",
                    "message", "Restart requires the Config admin role."));
            return;
        }
        services.audit.write("web-cfgadmin", "plugin_restart_requested",
                AuditActor.modDiscordId(ctx),
                AuditActor.payload(ctx, java.util.Map.of()));
        ctx.status(202).json(java.util.Map.of(
                "ok", true,
                "message", "Restart scheduled. Web + Discord will go offline for a few seconds."));
        // Run on the main thread - many teardown hooks (alerts SERVER_STOP,
        // scheduler task cancellation) need the server tick. 20 ticks (~1s)
        // is enough for the response above to flush and for the browser to
        // start polling /health.
        final io.warden.WardenPlugin self = plugin;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                self.restartSubsystems();
            } catch (Exception e) {
                self.getLogger().severe("In-process restart failed: " + e.getMessage());
            }
        }, 20L);
    }

    public void save(Context ctx) throws Exception {
        Settings prev = services.settingsDao.get();
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        boolean canEditConfig = sess != null && sess.canEditConfig();
        boolean canEditLanding = sess != null && sess.canEditLanding();
        boolean isOwner = sess != null && sess.owner();
        if (!canEditLanding) {
            ctx.status(401).html("<h1>401</h1><p>Configuration is restricted.</p>");
            return;
        }
        // Web-manager-only users can save the Landing tab and nothing else.
        // We rebuild the landing config from the form, and use prev for everything else.
        if (!canEditConfig) {
            io.warden.onboarding.model.LandingConfig landingOnly = new io.warden.onboarding.model.LandingConfig(
                    normaliseLandingMode(str(ctx, "landing_mode")),
                    str(ctx, "landing_redirect_url"),
                    str(ctx, "landing_server_name"),
                    str(ctx, "landing_server_address"),
                    str(ctx, "landing_tagline"),
                    str(ctx, "landing_join_url"),
                    bool(ctx, "landing_map_enabled"),
                    normaliseMapProvider(str(ctx, "landing_map_provider")),
                    str(ctx, "landing_map_url"),
                    str(ctx, "landing_map_label"),
                    str(ctx, "landing_brand_image_url"),
                    str(ctx, "landing_hero_image_url"),
                    normaliseHex(str(ctx, "landing_accent_color")),
                    str(ctx, "landing_papi_players_online"),
                    str(ctx, "landing_papi_discord_members"),
                    str(ctx, "landing_stat_players_label"),
                    str(ctx, "landing_stat_members_label"),
                    normaliseGaId(str(ctx, "landing_google_analytics_id")),
                    bool(ctx, "landing_cookie_banner"),
                    bool(ctx, "landing_leaderboard_enabled"),
                    str(ctx, "landing_leaderboard_title"),
                    str(ctx, "landing_leaderboard_description"),
                    parseIntOr(str(ctx, "landing_leaderboard_top_n"), 25),
                    str(ctx, "landing_leaderboard_label"),
                    str(ctx, "landing_promo_video_url"),
                    collectFeatures(ctx),
                    collectFaqs(ctx)
            );
            Settings landingOnlySaved = new Settings(
                    prev.rulesMarkdown(), prev.gatedRoleId(), prev.fullRoleId(), prev.modRoleId(),
                    prev.configAdminRoleId(), prev.webManagerRoleId(),
                    prev.welcomeChannelId(), prev.modReviewChannelId(), prev.llmSystemPrompt(),
                    prev.llmAutoApproveThreshold(), prev.llmAutoDenyThreshold(), prev.llmAutoDenyEnabled(),
                    prev.llmApiKey(), prev.llmBaseUrl(), prev.llmModel(),
                    prev.geoipEnabled(), prev.geoipLicenseKey(),
                    prev.flow(), landingOnly);
            services.settingsDao.save(landingOnlySaved);
            services.audit.write("web-webmgr", "landing_updated", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, java.util.Map.of("scope", "landing-only")));
            ctx.redirect("/dash/config?saved=ok&tab=landing");
            return;
        }
        FlowConfig f = new FlowConfig(
                bool(ctx, "flow_delivery_via_dm"),
                bool(ctx, "flow_delivery_via_channel"),
                str(ctx, "flow_delivery_channel_id"),
                str(ctx, "flow_delivery_message_template"),
                bool(ctx, "flow_entry_via_discord_button"),
                bool(ctx, "flow_entry_via_web_code"),
                bool(ctx, "flow_entry_via_web_oauth"),
                bool(ctx, "flow_gating_enabled"),
                TriageMode.fromWire(str(ctx, "flow_triage_mode")),
                bool(ctx, "flow_approve_dm_enabled"),
                str(ctx, "flow_approve_dm_template"),
                bool(ctx, "flow_approve_channel_announce"),
                str(ctx, "flow_approve_channel_template"),
                ctx.formParams("flow_approve_extra_roles").stream()
                        .filter(v -> v != null && !v.isBlank()).toList(),
                bool(ctx, "flow_deny_dm_enabled"),
                str(ctx, "flow_deny_dm_template"),
                DenyAction.fromWire(str(ctx, "flow_deny_action"))
        );
        // Empty API key in the form means "leave the stored key alone". This stops
        // a sloppy save (e.g. fixing a typo on the rules tab) from blanking out a
        // working secret just because the password field is rendered empty.
        String submittedKey = str(ctx, "llm_api_key");
        String keepKey = submittedKey.isBlank() ? prev.llmApiKey() : submittedKey;
        io.warden.onboarding.model.LandingConfig landing = new io.warden.onboarding.model.LandingConfig(
                normaliseLandingMode(str(ctx, "landing_mode")),
                str(ctx, "landing_redirect_url"),
                str(ctx, "landing_server_name"),
                str(ctx, "landing_server_address"),
                str(ctx, "landing_tagline"),
                str(ctx, "landing_join_url"),
                bool(ctx, "landing_map_enabled"),
                normaliseMapProvider(str(ctx, "landing_map_provider")),
                str(ctx, "landing_map_url"),
                str(ctx, "landing_map_label"),
                str(ctx, "landing_brand_image_url"),
                str(ctx, "landing_hero_image_url"),
                normaliseHex(str(ctx, "landing_accent_color")),
                str(ctx, "landing_papi_players_online"),
                str(ctx, "landing_papi_discord_members"),
                str(ctx, "landing_stat_players_label"),
                str(ctx, "landing_stat_members_label"),
                normaliseGaId(str(ctx, "landing_google_analytics_id")),
                bool(ctx, "landing_cookie_banner"),
                bool(ctx, "landing_leaderboard_enabled"),
                str(ctx, "landing_leaderboard_title"),
                str(ctx, "landing_leaderboard_description"),
                parseIntOr(str(ctx, "landing_leaderboard_top_n"), 25),
                str(ctx, "landing_leaderboard_label"),
                str(ctx, "landing_promo_video_url"),
                collectFeatures(ctx),
                collectFaqs(ctx)
        );
        // Owner-only fields: non-owners can't change config_admin/web_manager role IDs even
        // if they fiddle with form data, because the inputs are disabled in the UI and we
        // pin them to prev on the server too.
        String nextConfigAdminRoleId = isOwner ? str(ctx, "config_admin_role_id") : prev.configAdminRoleId();
        String nextWebManagerRoleId  = isOwner ? str(ctx, "web_manager_role_id")  : prev.webManagerRoleId();
        // Empty geoip key in the form means "leave the stored key alone", same
        // rationale as the LLM key above.
        String submittedGeoKey = str(ctx, "geoip_license_key");
        String keepGeoKey = submittedGeoKey.isBlank() ? prev.geoipLicenseKey() : submittedGeoKey;
        Settings next = new Settings(
                str(ctx, "rules_markdown"),
                str(ctx, "gated_role_id"),
                str(ctx, "full_role_id"),
                str(ctx, "mod_role_id"),
                nextConfigAdminRoleId,
                nextWebManagerRoleId,
                str(ctx, "welcome_channel_id"),
                str(ctx, "mod_review_channel_id"),
                str(ctx, "llm_system_prompt"),
                doubleOr(ctx, "llm_auto_approve_threshold", prev.llmAutoApproveThreshold()),
                doubleOr(ctx, "llm_auto_deny_threshold", prev.llmAutoDenyThreshold()),
                bool(ctx, "llm_auto_deny_enabled"),
                keepKey,
                str(ctx, "llm_base_url"),
                str(ctx, "llm_model"),
                bool(ctx, "geoip_enabled"),
                keepGeoKey,
                f,
                landing
        );
        services.settingsDao.save(next);
        List<String> changed = diffSettings(prev, next);
        services.audit.write("web-mod", "settings_updated", AuditActor.modDiscordId(ctx),
                AuditActor.payload(ctx, java.util.Map.of("changed", changed)));
        String tab = str(ctx, "active_tab");
        boolean validTab = false;
        for (var t : TABS) if (t.id().equals(tab)) { validTab = true; break; }
        ctx.redirect("/dash/config?saved=ok" + (validTab ? "&tab=" + tab : ""));
    }

    /**
     * AJAX endpoint behind the "Test connection" button on the AI tab.
     * Accepts api_key/base_url/model in the form body (so the operator can test
     * what's in the form before saving). An empty api_key field falls back to
     * the currently-saved key (same semantics as save()). Asks the gateway to
     * reply with a short funny joke and returns the response text.
     */
    public void testLlm(Context ctx) throws Exception {
        String submittedKey = str(ctx, "api_key");
        String key = submittedKey.isBlank() ? services.settingsDao.get().llmApiKey() : submittedKey;
        String base = str(ctx, "base_url");
        if (base.isBlank()) base = "https://app.manifest.build/v1";
        String model = str(ctx, "model");
        if (model.isBlank()) model = "auto";

        if (key == null || key.isBlank()) {
            ctx.status(400).json(java.util.Map.of(
                    "ok", false,
                    "error", "api_key_missing",
                    "message", "Set an API key (or save one) before testing."));
            return;
        }

        long t0 = System.currentTimeMillis();
        try {
            io.warden.llm.ManifestClient.Endpoint endpoint =
                    new io.warden.llm.ManifestClient.Endpoint(key, base, model);
            // No retries on the test path - operators want a fast yes/no.
            String joke = services.manifest.requestText(
                    endpoint,
                    "You are a helpful assistant. Reply with one short, family-friendly joke. "
                    + "No preamble, no explanation, just the joke.",
                    "Tell me a funny joke.",
                    0, 0L, 0L);
            long ms = System.currentTimeMillis() - t0;
            services.audit.write("web-mod", "llm_test_ok", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, java.util.Map.of(
                            "base_url", endpoint.baseUrl(),
                            "model", endpoint.model(),
                            "elapsed_ms", ms)));
            ctx.json(java.util.Map.of(
                    "ok", true,
                    "joke", joke,
                    "elapsed_ms", ms,
                    "base_url", endpoint.baseUrl(),
                    "model", endpoint.model()));
        } catch (io.warden.llm.ManifestClient.ManifestException e) {
            long ms = System.currentTimeMillis() - t0;
            services.audit.write("web-mod", "llm_test_failed", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, java.util.Map.of(
                            "base_url", base,
                            "model", model,
                            "status", e.statusCode(),
                            "error", String.valueOf(e.getMessage()),
                            "elapsed_ms", ms)));
            ctx.status(e.statusCode() >= 400 && e.statusCode() < 600 ? e.statusCode() : 502)
                    .json(java.util.Map.of(
                            "ok", false,
                            "error", "request_failed",
                            "status", e.statusCode(),
                            "message", String.valueOf(e.getMessage()),
                            "elapsed_ms", ms));
        }
    }

    /**
     * Collapsible panel listing every {{...}} placeholder that the template
     * renderers substitute, grouped by which template file accepts them.
     *
     * Kept alongside the live form so operators editing landing.html /
     * rules.html / map.html under plugins/Warden/www/ can copy a placeholder
     * straight into their template without leaving the page.
     *
     * Single source of truth: this list mirrors WelcomeHandlers#renderLanding /
     * rulesPage / mapPage. When you add a new substitution there, add the row
     * here too.
     */
    private static String templateFieldsReference() {
        StringBuilder s = new StringBuilder(4096);
        s.append("<details class=\"tplref mb-3\">");
        s.append("<summary><i class=\"bi bi-braces\"></i> Template fields reference"
                + " <span class=\"text-secondary small\">- click to expand</span></summary>");
        s.append("<div class=\"tplref-body\">");
        s.append("<p class=\"text-secondary small mb-3\">Each row is a placeholder the template renderer "
                + "swaps for a real value at request time. <strong>Text</strong> placeholders are HTML-escaped, "
                + "safe inside attributes (<code>&lt;meta content=\"{{server_name}}\"&gt;</code>). "
                + "<strong>HTML</strong> placeholders are pre-rendered markup - drop them where a block is valid. "
                + "Unrecognised <code>{{...}}</code> tokens render literally, so typos show up immediately.</p>");

        s.append("<h3 class=\"h6 mt-3\">landing.html</h3>");
        s.append(tplRefTable(new String[][] {
            {"{{server_name}}",        "text", "Server name from config."},
            {"{{tagline}}",            "text", "Raw tagline text."},
            {"{{server_address}}",     "text", "MC connect address."},
            {"{{players_online}}",     "text", "Bukkit's online count, or the PAPI override."},
            {"{{players_max}}",        "text", "Bukkit's getMaxPlayers()."},
            {"{{discord_members}}",    "text", "Guild member count, or the PAPI override."},
            {"{{players_label}}",      "text", "Caption under the players counter."},
            {"{{members_label}}",      "text", "Caption under the Discord counter."},
            {"{{join_url}}",           "text", "Raw URL of the Join button target."},
            {"{{signin_url}}",         "text", "/auth/discord/start?next=/dash/stats"},
            {"{{dash_url}}",           "text", "/dash"},
            {"{{accent_color}}",       "text", "Hex string for --accent."},
            {"{{brand_image_url}}",    "text", "Resolved: explicit setting → guild icon → bundled warden icon."},
            {"{{hero_image_url}}",     "text", "Raw hero image URL (empty when unset)."},
            {"{{tagline_block}}",      "HTML", "&lt;p class=\"tagline\"&gt;...&lt;/p&gt; or empty."},
            {"{{address_block}}",      "HTML", "&lt;p class=\"address\"&gt;...&lt;/p&gt; or empty."},
            {"{{join_button}}",        "HTML", "&lt;a class=\"btn primary\"&gt;...&lt;/a&gt; or empty."},
            {"{{signin_button}}",      "HTML", "Sign-in CTA, empty when OAuth isn't configured."},
            {"{{signin_footer_link}}", "HTML", "Footer Sign-in link + separator dot."},
            {"{{features_block}}",     "HTML", "Whole &lt;section id=features&gt; or empty when list is empty."},
            {"{{promo_block}}",        "HTML", "Whole &lt;section id=promo&gt; with a YouTube embed, or empty when the promo URL is unset."},
            {"{{faq_block}}",          "HTML", "Whole &lt;section id=faq&gt; or empty when list is empty."},
            {"{{hero_image_block}}",   "HTML", "Positioned &lt;img&gt; with scrim, or an autoplay/muted/looped YouTube &lt;iframe&gt; when the URL is a YouTube link. Renders inside .hero-bg. Empty when no URL."},
            {"{{nav_links}}",          "HTML", "Shared nav: Home / Rules / Map / Features / FAQ."},
        }));

        s.append("<h3 class=\"h6 mt-4\">rules.html</h3>");
        s.append(tplRefTable(new String[][] {
            {"{{server_name}}",        "text", "Server name."},
            {"{{brand_image_url}}",    "text", "Same resolution as landing.html."},
            {"{{nav_links}}",          "HTML", "Shared nav."},
            {"{{rules_content}}",      "HTML", "Rules markdown rendered to HTML."},
        }));

        s.append("<h3 class=\"h6 mt-4\">map.html</h3>");
        s.append(tplRefTable(new String[][] {
            {"{{server_name}}",        "text", "Server name."},
            {"{{brand_image_url}}",    "text", "Same resolution as landing.html."},
            {"{{nav_links}}",          "HTML", "Shared nav."},
            {"{{map_label}}",          "text", "Map link label (e.g. 'Live Map')."},
            {"{{map_provider_label}}", "text", "Human label for the provider (Dynmap, BlueMap, ...)."},
            {"{{map_url}}",            "text", "URL the iframe embeds."},
        }));

        s.append("<p class=\"text-secondary small mt-3 mb-0\">Files under <code>plugins/Warden/www/</code> "
                + "are served at <code>/www/*</code>, so a custom template can reference its own CSS, JS, "
                + "or images alongside the bundled <code>landing.css</code>.</p>");

        s.append("</div></details>");
        s.append(TPLREF_STYLES);
        return s.toString();
    }

    private static String tplRefTable(String[][] rows) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"table-responsive\"><table class=\"table table-sm tplref-table mb-0\">");
        s.append("<thead><tr><th>Placeholder</th><th class=\"tplref-kind\">Kind</th><th>Meaning</th></tr></thead><tbody>");
        for (String[] r : rows) {
            s.append("<tr>")
                    .append("<td><code>").append(r[0]).append("</code></td>")
                    .append("<td><span class=\"tplref-badge tplref-").append(r[1].toLowerCase(java.util.Locale.ROOT))
                    .append("\">").append(r[1]).append("</span></td>")
                    .append("<td>").append(r[2]).append("</td>")
                    .append("</tr>");
        }
        s.append("</tbody></table></div>");
        return s.toString();
    }

    private static final String TPLREF_STYLES =
            "<style>"
            + ".tplref{border:1px solid var(--bs-border-color);border-radius:.5rem;"
            + "background:var(--bs-tertiary-bg);overflow:hidden}"
            + ".tplref > summary{cursor:pointer;padding:.7rem 1rem;font-weight:600;"
            + "list-style:none;display:flex;align-items:center;gap:.5rem}"
            + ".tplref > summary::-webkit-details-marker{display:none}"
            + ".tplref > summary::before{content:'\\f285';font-family:'bootstrap-icons';"
            + "font-weight:normal;font-size:.85em;transition:transform .15s;display:inline-block}"
            + ".tplref[open] > summary::before{transform:rotate(90deg)}"
            + ".tplref > summary > .bi{display:none}"  // we use ::before chevron, hide the leading icon
            + ".tplref-body{padding:0 1rem 1rem;border-top:1px solid var(--bs-border-color);"
            + "background:var(--bs-body-bg)}"
            + ".tplref-body h3{margin-top:1rem;color:var(--bs-emphasis-color)}"
            + ".tplref-table code{font-size:.85em;background:var(--bs-tertiary-bg);"
            + "padding:.1em .35em;border-radius:.25rem;white-space:nowrap}"
            + ".tplref-table th{font-weight:600;font-size:.85em;color:var(--bs-secondary-color)}"
            + ".tplref-table td{font-size:.9em;vertical-align:middle}"
            + ".tplref-kind{width:5rem}"
            + ".tplref-badge{display:inline-block;font-size:.72em;font-weight:600;"
            + "padding:.15em .55em;border-radius:99px;letter-spacing:.02em;text-transform:uppercase}"
            + ".tplref-badge.tplref-text{background:rgba(13,110,253,.15);color:var(--bs-primary)}"
            + ".tplref-badge.tplref-html{background:rgba(214,51,132,.15);color:#d63384}"
            + "[data-bs-theme=dark] .tplref-badge.tplref-html{color:#f06ba3}"
            + "</style>";

    /* ---------- AI polish + plugin-driven features ---------- */

    /**
     * AJAX: polish a single landing-text field. Body params: kind, text.
     * kind ∈ {feature_title, feature_body, faq_question, faq_answer}.
     * Returns {ok:true, polished:"..."} or {ok:false, message:"..."}.
     *
     * Gated by {@code canEditLanding} so web managers can use it too. The route
     * sits outside /dash/config/* on purpose - that subtree is config-admin-only.
     */
    public void polish(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null || !sess.canEditLanding()) {
            ctx.status(401).json(java.util.Map.of("ok", false, "message", "not allowed"));
            return;
        }
        String kind = str(ctx, "kind");
        String text = str(ctx, "text");
        if (text.isBlank()) {
            ctx.status(400).json(java.util.Map.of("ok", false, "message", "empty text"));
            return;
        }
        // Hard-limit input so we don't ship a textarea full of pasted lore at the LLM.
        if (text.length() > 4000) text = text.substring(0, 4000);

        Settings s = services.settingsDao.get();
        if (!s.llmConfigured()) {
            ctx.status(400).json(java.util.Map.of("ok", false,
                    "message", "AI gateway isn't configured. Ask a Config admin to set the API key on the AI tab."));
            return;
        }
        var endpoint = new io.warden.llm.ManifestClient.Endpoint(s.llmApiKey(), s.llmBaseUrl(), s.llmModel());
        String sys = systemPromptForPolish(kind);
        try {
            String polished = services.manifest.requestText(endpoint, sys, text, 0, 0L, 0L).trim();
            // Some models wrap responses in quotes / code fences; strip them.
            polished = stripWrap(polished);
            services.audit.write("web", "landing_polish", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, java.util.Map.of("kind", kind, "in", text.length(), "out", polished.length())));
            ctx.json(java.util.Map.of("ok", true, "polished", polished));
        } catch (io.warden.llm.ManifestClient.ManifestException e) {
            ctx.status(502).json(java.util.Map.of("ok", false,
                    "message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * AJAX: ask the LLM to draft N feature cards based on the Bukkit plugin list.
     * Body param: count (default 6, clamped 1..12). Returns
     * {ok:true, features:[{icon,title,body},...]} on success.
     */
    public void featuresFromPlugins(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null || !sess.canEditLanding()) {
            ctx.status(401).json(java.util.Map.of("ok", false, "message", "not allowed"));
            return;
        }
        int count = (int) doubleOr(ctx, "count", 6);
        count = Math.max(1, Math.min(12, count));

        Settings s = services.settingsDao.get();
        if (!s.llmConfigured()) {
            ctx.status(400).json(java.util.Map.of("ok", false,
                    "message", "AI gateway isn't configured. Ask a Config admin to set the API key on the AI tab."));
            return;
        }

        StringBuilder pluginList = new StringBuilder();
        try {
            var pm = org.bukkit.Bukkit.getServer().getPluginManager();
            for (var p : pm.getPlugins()) {
                String name = p.getName();
                String desc = "";
                try {
                    var meta = p.getPluginMeta();
                    if (meta != null && meta.getDescription() != null) desc = meta.getDescription();
                } catch (Throwable ignored) {
                    // Older API on some forks; skip rather than emit a deprecation noise.
                }
                pluginList.append("- ").append(name);
                if (!desc.isBlank()) pluginList.append(": ").append(desc.replace("\n", " ").trim());
                pluginList.append("\n");
            }
        } catch (Throwable t) {
            ctx.status(500).json(java.util.Map.of("ok", false, "message", "couldn't enumerate plugins"));
            return;
        }
        if (pluginList.length() == 0) {
            ctx.json(java.util.Map.of("ok", true, "features", java.util.List.of()));
            return;
        }

        String icons = String.join(", ", LandingFeature.ICONS);
        String sysPrompt = "You write copy for a Minecraft server's public landing page. Given a list of "
                + "installed Paper/Spigot plugins, draft " + count + " short feature cards that highlight the "
                + "player-facing experience the server offers. Output ONLY a JSON array of objects with "
                + "exactly the keys icon, title, body. Choose icon from this whitelist: " + icons + ". "
                + "Each title is 2-4 words; each body is one short sentence (10-22 words). "
                + "Focus on what players experience, not the plugin names. Don't mention plugin names directly.";
        String userPrompt = "Installed plugins:\n" + pluginList.toString().trim();
        var endpoint = new io.warden.llm.ManifestClient.Endpoint(s.llmApiKey(), s.llmBaseUrl(), s.llmModel());
        try {
            String reply = services.manifest.requestText(endpoint, sysPrompt, userPrompt, 0, 0L, 0L);
            java.util.List<java.util.Map<String, String>> parsed = parseFeatureJson(reply);
            if (parsed.isEmpty()) {
                ctx.status(502).json(java.util.Map.of("ok", false,
                        "message", "AI reply did not contain a JSON array of features. Try again."));
                return;
            }
            services.audit.write("web", "landing_features_generated", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, java.util.Map.of("count", parsed.size())));
            ctx.json(java.util.Map.of("ok", true, "features", parsed));
        } catch (io.warden.llm.ManifestClient.ManifestException e) {
            ctx.status(502).json(java.util.Map.of("ok", false, "message", String.valueOf(e.getMessage())));
        }
    }

    private static String systemPromptForPolish(String kind) {
        String base = "You polish short marketing copy for a Minecraft server's public landing page. "
                + "Reply with ONLY the rewritten text - no quotes, no preamble, no markdown. "
                + "Keep the same meaning and roughly the same length; fix grammar and tighten phrasing. "
                + "Match a warm, plainspoken tone; no exclamation marks unless the input had them.";
        return switch (kind == null ? "" : kind) {
            case "feature_title" -> base + " Output a feature-card title of 2 to 4 words in title case.";
            case "feature_body"  -> base + " Output one sentence of 10 to 22 words describing the feature.";
            case "faq_question"  -> base + " Output a single question ending with a question mark.";
            case "faq_answer"    -> base + " Output one or two short sentences answering the user's question.";
            default              -> base;
        };
    }

    /** Strip leading/trailing quotes, backticks, or "Answer:" / "Title:" prefixes the LLM sometimes adds. */
    private static String stripWrap(String s) {
        if (s == null) return "";
        String t = s.trim();
        // Strip ``` fences if present
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        // Strip leading "Title:" / "Question:" / "Answer:" etc.
        if (t.matches("(?i)^(title|question|answer|polished|result)\\s*[:\\-]\\s*.+")) {
            int idx = Math.max(t.indexOf(':'), t.indexOf('-'));
            if (idx >= 0 && idx + 1 < t.length()) t = t.substring(idx + 1).trim();
        }
        // Strip wrapping quotes
        if (t.length() >= 2) {
            char a = t.charAt(0), b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    /** Pulls a JSON array of {icon,title,body} out of an LLM reply, tolerating prose around it. */
    private static java.util.List<java.util.Map<String, String>> parseFeatureJson(String reply) {
        java.util.List<java.util.Map<String, String>> out = new ArrayList<>();
        if (reply == null) return out;
        int start = reply.indexOf('[');
        int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) return out;
        String slice = reply.substring(start, end + 1);
        try {
            com.fasterxml.jackson.databind.JsonNode arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(slice);
            if (!arr.isArray()) return out;
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                String icon  = n.path("icon").asText("shield");
                String title = n.path("title").asText("").trim();
                String body  = n.path("body").asText("").trim();
                if (title.isEmpty() && body.isEmpty()) continue;
                if (!LandingFeature.ICONS.contains(icon)) icon = "shield";
                out.add(java.util.Map.of("icon", icon, "title", title, "body", body));
            }
        } catch (Exception ignored) {
            // Fall through with whatever we collected; empty list signals failure.
        }
        return out;
    }

    /* ---------- questions AI-generation ---------- */

    /**
     * AJAX: draft a batch of onboarding questions from a free-text brief.
     * Body params:
     *   - brief (required): operator's description of what they want to learn
     *     about applicants (e.g. "Explain what you're looking for in a potential player").
     *   - count (optional, default 5, clamped 1..15): how many questions to draft.
     * Returns {ok:true, questions:[{prompt, kind, choices, required}, ...]}.
     * Preview only - the client must POST the edited list back to /ai-save to persist.
     */
    public void generateQuestions(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null || !sess.canEditConfig()) {
            ctx.status(401).json(java.util.Map.of("ok", false, "message", "not allowed"));
            return;
        }
        String brief = str(ctx, "brief").trim();
        if (brief.isBlank()) {
            ctx.status(400).json(java.util.Map.of("ok", false, "message", "brief is required"));
            return;
        }
        if (brief.length() > 4000) brief = brief.substring(0, 4000);

        int count = (int) doubleOr(ctx, "count", 5);
        count = Math.max(1, Math.min(15, count));

        Settings s = services.settingsDao.get();
        if (!s.llmConfigured()) {
            ctx.status(400).json(java.util.Map.of("ok", false,
                    "message", "AI gateway isn't configured. Set the API key on the AI tab first."));
            return;
        }

        String kindList = "short_text, long_text, single_choice, multi_choice";
        String sysPrompt = "You design onboarding questions for a Minecraft community's Discord vetting flow. "
                + "The operator gives you a brief describing the kind of player they want to admit; you draft "
                + count + " questions that surface the information they care about. Output ONLY a JSON array of objects "
                + "with exactly the keys: prompt (string, the question shown to the applicant), "
                + "kind (one of " + kindList + "), "
                + "choices (array of strings; use [] for short_text and long_text, 2 to 6 entries for single_choice and multi_choice), "
                + "required (boolean). "
                + "Pick the kind that fits the question: factual one-liners use short_text, open-ended explanations use long_text, "
                + "and discrete options use single_choice or multi_choice. Keep prompts concise, neutral, and answerable in one go. "
                + "Do not include numbering, preamble, code fences, or commentary - just the JSON array.";
        String userPrompt = "Operator brief:\n" + brief;
        var endpoint = new io.warden.llm.ManifestClient.Endpoint(s.llmApiKey(), s.llmBaseUrl(), s.llmModel());
        try {
            String reply = services.manifest.requestText(endpoint, sysPrompt, userPrompt, 0, 0L, 0L);
            java.util.List<java.util.Map<String, Object>> parsed = parseQuestionJson(reply);
            if (parsed.isEmpty()) {
                ctx.status(502).json(java.util.Map.of("ok", false,
                        "message", "AI reply did not contain a JSON array of questions. Try again."));
                return;
            }
            services.audit.write("web", "questions_generated", AuditActor.modDiscordId(ctx),
                    AuditActor.payload(ctx, java.util.Map.of(
                            "count", parsed.size(),
                            "brief_len", brief.length())));
            ctx.json(java.util.Map.of("ok", true, "questions", parsed));
        } catch (io.warden.llm.ManifestClient.ManifestException e) {
            ctx.status(502).json(java.util.Map.of("ok", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Bulk-create generated questions. Body param: payload = JSON array of
     * {prompt, kind, choices, required} objects (the edited preview list).
     * Each new question is appended after the existing ones, active=true by default.
     * Returns {ok:true, added:N} so the UI can reload.
     */
    public void saveGeneratedQuestions(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null || !sess.canEditConfig()) {
            ctx.status(401).json(java.util.Map.of("ok", false, "message", "not allowed"));
            return;
        }
        String payload = str(ctx, "payload");
        if (payload.isBlank()) {
            ctx.status(400).json(java.util.Map.of("ok", false, "message", "payload is required"));
            return;
        }
        java.util.List<java.util.Map<String, Object>> items = parseQuestionJson(payload);
        if (items.isEmpty()) {
            ctx.status(400).json(java.util.Map.of("ok", false, "message", "no valid questions in payload"));
            return;
        }
        int nextOrder = 0;
        for (Question existing : services.questionDao.listAll()) {
            if (existing.order() >= nextOrder) nextOrder = existing.order() + 1;
        }
        int added = 0;
        for (java.util.Map<String, Object> q : items) {
            String prompt = String.valueOf(q.getOrDefault("prompt", "")).trim();
            if (prompt.isEmpty()) continue;
            QuestionKind kind;
            try { kind = QuestionKind.fromWire(String.valueOf(q.getOrDefault("kind", "short_text"))); }
            catch (IllegalArgumentException e) { kind = QuestionKind.SHORT_TEXT; }
            @SuppressWarnings("unchecked")
            java.util.List<String> choices = (java.util.List<String>) q.getOrDefault("choices", java.util.List.<String>of());
            if (choices == null) choices = java.util.List.of();
            boolean required = Boolean.TRUE.equals(q.get("required"));
            services.questionDao.create(nextOrder, prompt, kind, choices, required, true);
            nextOrder++;
            added++;
        }
        services.audit.write("web-mod", "questions_generated_saved", AuditActor.modDiscordId(ctx),
                AuditActor.payload(ctx, java.util.Map.of("added", added)));
        ctx.json(java.util.Map.of("ok", true, "added", added));
    }

    /** Pulls a JSON array of {prompt,kind,choices,required} out of an LLM reply, tolerating prose around it. */
    private static java.util.List<java.util.Map<String, Object>> parseQuestionJson(String reply) {
        java.util.List<java.util.Map<String, Object>> out = new ArrayList<>();
        if (reply == null) return out;
        int start = reply.indexOf('[');
        int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) return out;
        String slice = reply.substring(start, end + 1);
        try {
            com.fasterxml.jackson.databind.JsonNode arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(slice);
            if (!arr.isArray()) return out;
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                String prompt = n.path("prompt").asText("").trim();
                if (prompt.isEmpty()) continue;
                String kindWire = n.path("kind").asText("short_text").trim().toLowerCase(java.util.Locale.ROOT);
                // Snap to a known kind; default to short_text.
                try { QuestionKind.fromWire(kindWire); }
                catch (IllegalArgumentException e) { kindWire = "short_text"; }
                java.util.List<String> choices = new ArrayList<>();
                com.fasterxml.jackson.databind.JsonNode ch = n.get("choices");
                if (ch != null && ch.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode c : ch) {
                        String v = c.asText("").trim();
                        if (!v.isEmpty()) choices.add(v);
                    }
                }
                // Text kinds never carry choices; choice kinds must have at least 2.
                if ("short_text".equals(kindWire) || "long_text".equals(kindWire)) {
                    choices = java.util.List.of();
                } else if (choices.size() < 2) {
                    // Salvage by demoting to short_text rather than dropping the question.
                    kindWire = "short_text";
                    choices = java.util.List.of();
                }
                boolean required = n.path("required").asBoolean(true);
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("prompt", prompt);
                row.put("kind", kindWire);
                row.put("choices", choices);
                row.put("required", required);
                out.add(row);
            }
        } catch (Exception ignored) {
            // Fall through with whatever we collected; empty list signals failure.
        }
        return out;
    }

    /* ---------- questions CRUD ---------- */

    public void newQuestion(Context ctx) {
        StringBuilder h = new StringBuilder();
        h.append(Layout.head("New question · Warden", "config", ctx));
        h.append("<h1 class=\"h3 mb-3\">New question</h1>");
        h.append(questionForm(null));
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void editQuestion(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        Question q = services.questionDao.findById(id).orElse(null);
        if (q == null) { ctx.status(404).html("<h1>404</h1>"); return; }
        StringBuilder h = new StringBuilder();
        h.append(Layout.head("Edit question · Warden", "config", ctx));
        h.append("<h1 class=\"h3 mb-3\">Edit question " + id + "</h1>");
        h.append(questionForm(q));
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void saveQuestion(Context ctx) throws Exception {
        String idStr = ctx.pathParamMap().get("id");
        String prompt = str(ctx, "prompt");
        QuestionKind kind = QuestionKind.fromWire(str(ctx, "kind"));
        // Choices arrive as one form value per input row (the reorderable widget).
        // We still split each value on newlines so a pasted blob ends up as
        // separate options rather than a single multi-line label.
        List<String> choices = new ArrayList<>();
        List<String> rawValues = ctx.formParams("choices");
        if (rawValues != null) {
            for (String v : rawValues) {
                if (v == null) continue;
                for (String line : v.split("\\R")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) choices.add(trimmed);
                }
            }
        }
        boolean required = bool(ctx, "required");
        boolean active = bool(ctx, "active");
        if (idStr == null || idStr.equals("new")) {
            // Append at the end; the user reorders via drag-and-drop afterwards.
            int nextOrder = 0;
            for (Question existing : services.questionDao.listAll()) {
                if (existing.order() >= nextOrder) nextOrder = existing.order() + 1;
            }
            services.questionDao.create(nextOrder, prompt, kind, choices, required, active);
        } else {
            // Preserve the existing order on edit; reordering happens separately.
            long id = Long.parseLong(idStr);
            int order = services.questionDao.findById(id).map(Question::order).orElse(999);
            services.questionDao.update(id, order, prompt, kind, choices, required, active);
        }
        services.audit.write("web-mod", "question_saved", AuditActor.modDiscordId(ctx),
                AuditActor.payload(ctx, java.util.Map.of(
                        "id", idStr == null ? "new" : idStr,
                        "prompt", prompt)));
        ctx.redirect("/dash/config?tab=questions");
    }

    public void deleteQuestion(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        services.questionDao.delete(id);
        services.audit.write("web-mod", "question_deleted", AuditActor.modDiscordId(ctx),
                AuditActor.payload(ctx, java.util.Map.of("id", id)));
        ctx.redirect("/dash/config?tab=questions");
    }

    public void moveQuestion(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String dir = ctx.queryParam("dir");
        List<Question> all = services.questionDao.listAll();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id() == id) { idx = i; break; }
        if (idx < 0) { ctx.redirect("/dash/config?tab=questions"); return; }
        int swap = "up".equals(dir) ? idx - 1 : idx + 1;
        if (swap < 0 || swap >= all.size()) { ctx.redirect("/dash/config?tab=questions"); return; }
        Question a = all.get(idx);
        Question b = all.get(swap);
        services.questionDao.update(a.id(), b.order(), a.prompt(), a.kind(), a.choices(), a.required(), a.active());
        services.questionDao.update(b.id(), a.order(), b.prompt(), b.kind(), b.choices(), b.required(), b.active());
        ctx.redirect("/dash/config?tab=questions");
    }

    /**
     * Bulk-reorder via drag-and-drop. Accepts {@code ids} as a comma-separated
     * list of question ids in the new order. Idempotent and atomic. Returns 204
     * on success so the browser can keep its locally-reordered DOM without a
     * full page refresh.
     */
    /**
     * Flip a boolean column on a question (required or active). Used by the
     * inline checkboxes on the questions table so mods don't have to open the
     * edit form just to toggle a flag.
     */
    public void toggleQuestion(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String field = str(ctx, "field");
        boolean value = "1".equals(str(ctx, "value")) || "true".equalsIgnoreCase(str(ctx, "value"));
        Question q = services.questionDao.findById(id).orElse(null);
        if (q == null) { ctx.status(404).result("not found"); return; }

        boolean newRequired = q.required();
        boolean newActive   = q.active();
        switch (field) {
            case "required" -> newRequired = value;
            case "active"   -> newActive   = value;
            default -> { ctx.status(400).result("unknown field"); return; }
        }
        services.questionDao.update(id, q.order(), q.prompt(), q.kind(), q.choices(), newRequired, newActive);
        services.audit.write("web-mod", "question_toggle", AuditActor.modDiscordId(ctx),
                AuditActor.payload(ctx, java.util.Map.of("id", id, "field", field, "value", value)));
        ctx.status(204);
    }

    public void reorderQuestions(Context ctx) throws Exception {
        String raw = ctx.formParam("ids");
        if (raw == null || raw.isBlank()) { ctx.status(400).result("ids required"); return; }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String s : raw.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            try { ids.add(Long.parseLong(s)); }
            catch (NumberFormatException e) { ctx.status(400).result("bad id: " + s); return; }
        }
        services.questionDao.reorder(ids);
        services.audit.write("web-mod", "questions_reordered", AuditActor.modDiscordId(ctx),
                AuditActor.payload(ctx, java.util.Map.of("count", ids.size(), "ids", ids)));
        ctx.status(204);
    }

    private static String questionForm(Question q) {
        StringBuilder h = new StringBuilder();
        boolean isNew = (q == null);
        String action = isNew ? "/dash/config/questions/new" : ("/dash/config/questions/" + q.id());
        String currentKind = q == null ? "short_text" : q.kind().wire();
        h.append("<form method=post action=\"").append(action).append("\" class=\"qform\">");
        h.append(textField("prompt", "Question prompt", q == null ? "" : q.prompt(),
                "Exactly what the new member will see as the question label. Keep it short, "
                + "clear, and answerable in one go."));
        h.append(selectFieldRaw("kind", "Kind", List.of(
                new RadioOpt("short_text", "Short text - single-line modal",
                        "One-line text answer in a Discord modal. Good for short factual answers."),
                new RadioOpt("long_text", "Long text - multi-line modal",
                        "Multi-line text answer in a Discord modal. Good for an introduction or longer explanation."),
                new RadioOpt("single_choice", "Single choice - dropdown",
                        "User picks exactly one option from the Choices list below."),
                new RadioOpt("multi_choice", "Multi choice - multi-select dropdown",
                        "User can pick any number of options from the Choices list below.")
        ), currentKind,
                "How the answer is collected. Text kinds show an input box; choice kinds "
                + "show a dropdown built from the Choices list below."));
        h.append(choicesField(q == null ? java.util.List.<String>of() : q.choices()));
        h.append(checkbox("required", "Required - block submission if left blank",
                q == null || q.required(),
                "When on, the member can't finish the form without answering. "
                + "Turn it off for optional questions."));
        h.append(checkbox("active", "Active - show this question in the flow",
                q == null || q.active(),
                "Turn off to hide this question without deleting it. "
                + "Useful for seasonal or temporarily-paused questions."));
        h.append("<div class=\"mt-4 pt-3 border-top\">")
                .append("<button type=submit class=\"btn btn-success\">Save</button> ")
                .append("<a class=\"btn btn-link\" href=\"/dash/config\">cancel</a></div>");
        h.append("</form>");
        h.append(QFORM_ASSETS);
        h.append("<script>(function(){var sel=document.getElementById('f_kind');if(sel){function sync(){var k=sel.value;var box=document.getElementById('choices-block');if(!box)return;var needs=(k==='single_choice'||k==='multi_choice');box.classList.toggle('d-none',!needs);}sync();sel.addEventListener('change',sync);}})();</script>");
        return h.toString();
    }

    /**
     * Reorderable list of single-line inputs, one per choice. Each row carries a
     * drag handle and a remove button; an "+ add choice" button appends new rows.
     * The block is hidden when the Kind dropdown isn't a choice kind. All inputs
     * share the {@code choices} name so the form posts a list of values that
     * {@link #saveQuestion(Context)} can collect via {@code ctx.formParams("choices")}.
     */
    private static String choicesField(java.util.List<String> values) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"mb-3\" id=\"choices-block\">");
        s.append("<label class=\"form-label\">Choices");
        s.append(Layout.infoIcon(
                "Used only by single-choice and multi-choice questions. Drag the handle to reorder, "
                + "click the X to remove a row, and click + add choice to add another."));
        s.append(" <em class=\"text-secondary fst-normal small d-block\">"
                + "Drag the handle to reorder. Click + add choice to add a new option.</em>");
        s.append("</label>");

        s.append("<div class=\"choices-list\" id=\"choices-list\">");
        java.util.List<String> rows = (values == null || values.isEmpty())
                ? java.util.List.of("", "")  // start with two empty rows when there are none
                : values;
        for (String v : rows) {
            s.append(choiceRow(v));
        }
        s.append("</div>");

        s.append("<button type=\"button\" class=\"btn btn-sm btn-outline-primary mt-1\" id=\"choices-add\">")
                .append("<i class=\"bi bi-plus-lg me-1\"></i>add choice</button>");

        // Hidden template row used by the JS to clone fresh rows.
        s.append("<template id=\"choice-row-template\">").append(choiceRow("")).append("</template>");

        s.append("</div>");
        return s.toString();
    }

    private static String choiceRow(String value) {
        return "<div class=\"choice-row\" draggable=\"true\">"
                + "<span class=\"choice-handle\" title=\"Drag to reorder\" aria-label=\"drag to reorder\">&#8801;</span>"
                + "<input type=\"text\" name=\"choices\" class=\"form-control choice-input\" value=\""
                + escape(value == null ? "" : value) + "\" placeholder=\"Option text\">"
                + "<button type=\"button\" class=\"btn btn-sm btn-outline-danger choice-remove\""
                + " title=\"Remove this option\" aria-label=\"Remove\">"
                + "<i class=\"bi bi-x-lg\"></i></button>"
                + "</div>";
    }

    /** CSS + JS for the choices list widget; appended once after the form. */
    private static final String QFORM_ASSETS =
            "<style>"
            + ".choices-list{display:flex;flex-direction:column;gap:.4rem;margin-bottom:.5rem}"
            + ".choice-row{display:flex;align-items:center;gap:.4rem;"
            + "background:var(--bs-tertiary-bg);border:1px solid var(--bs-border-color);"
            + "border-radius:.4rem;padding:.3rem .45rem}"
            + ".choice-row.dragging{opacity:.4}"
            + ".choice-row.drop-above{box-shadow:inset 0 2px 0 0 var(--bs-primary,#0d6efd)}"
            + ".choice-row.drop-below{box-shadow:inset 0 -2px 0 0 var(--bs-primary,#0d6efd)}"
            + ".choice-handle{cursor:grab;user-select:none;color:var(--bs-secondary-color);"
            + "font-size:1.25rem;line-height:1;padding:.1rem .35rem;border-radius:.25rem;flex:0 0 auto}"
            + ".choice-row:hover .choice-handle{background:rgba(127,127,127,.12)}"
            + ".choice-handle:active{cursor:grabbing}"
            + ".choice-row .choice-input{flex:1 1 auto}"
            + ".choice-row .choice-remove{flex:0 0 auto;line-height:1;padding:.25rem .55rem}"
            + "</style>"
            + "<script>(function(){"
            + "var list=document.getElementById('choices-list');"
            + "var addBtn=document.getElementById('choices-add');"
            + "var tpl=document.getElementById('choice-row-template');"
            + "if(!list||!addBtn||!tpl)return;"
            + "function wireRow(row){"
            + "var rm=row.querySelector('.choice-remove');"
            + "if(rm)rm.addEventListener('click',function(){row.remove();});"
            + "row.addEventListener('dragstart',function(e){"
            + "dragging=row;row.classList.add('dragging');"
            + "if(e.dataTransfer){e.dataTransfer.effectAllowed='move';try{e.dataTransfer.setData('text/plain','x');}catch(_){}}"
            + "});"
            + "row.addEventListener('dragend',function(){if(dragging)dragging.classList.remove('dragging');dragging=null;clearMarkers();});"
            + "}"
            + "var dragging=null;"
            + "function clearMarkers(){list.querySelectorAll('.choice-row').forEach(function(r){r.classList.remove('drop-above','drop-below');});}"
            + "list.addEventListener('dragover',function(e){"
            + "if(!dragging)return;"
            + "var row=e.target.closest('.choice-row');if(!row||row===dragging)return;"
            + "e.preventDefault();if(e.dataTransfer)e.dataTransfer.dropEffect='move';"
            + "var rect=row.getBoundingClientRect();var before=(e.clientY-rect.top)<(rect.height/2);"
            + "clearMarkers();row.classList.add(before?'drop-above':'drop-below');"
            + "});"
            + "list.addEventListener('dragleave',function(e){"
            + "var row=e.target.closest('.choice-row');if(row)row.classList.remove('drop-above','drop-below');"
            + "});"
            + "list.addEventListener('drop',function(e){"
            + "if(!dragging)return;"
            + "var row=e.target.closest('.choice-row');if(!row||row===dragging){clearMarkers();return;}"
            + "e.preventDefault();"
            + "var rect=row.getBoundingClientRect();var before=(e.clientY-rect.top)<(rect.height/2);"
            + "if(before){row.parentNode.insertBefore(dragging,row);}else{row.parentNode.insertBefore(dragging,row.nextSibling);}"
            + "clearMarkers();"
            + "});"
            + "list.querySelectorAll('.choice-row').forEach(wireRow);"
            + "addBtn.addEventListener('click',function(){"
            + "var frag=tpl.content.cloneNode(true);"
            + "var row=frag.querySelector('.choice-row');"
            + "list.appendChild(frag);"
            + "if(row){wireRow(row);var inp=row.querySelector('input');if(inp)inp.focus();}"
            + "});"
            + "})();</script>";

    /* ---------- landing features editor ---------- */

    /**
     * Reorderable list of feature cards. Each row carries icon/title/body
     * inputs plus a drag handle and a remove button. All inputs share array
     * names ({@code landing_feature_icon}, {@code _title}, {@code _body}) so
     * the rendered position decides the resulting array index in save().
     */
    private static String landingFeaturesEditor(List<LandingFeature> values) {
        StringBuilder s = new StringBuilder();
        // Toolbar above the list: "Generate from plugins" + count picker.
        s.append("<div class=\"d-flex align-items-center gap-2 flex-wrap mb-2\">");
        s.append("<button type=\"button\" class=\"btn btn-sm btn-outline-primary\" id=\"feat-ai-generate\">")
                .append("<i class=\"bi bi-stars me-1\"></i>Generate from plugins</button>");
        s.append("<label class=\"form-label small mb-0 ms-1\" for=\"feat-ai-count\">Count</label>");
        s.append("<input type=\"number\" id=\"feat-ai-count\" class=\"form-control form-control-sm\""
                + " value=\"6\" min=\"1\" max=\"12\" style=\"width:5rem\">");
        s.append("<label class=\"form-check form-switch ms-2 mb-0 small\">")
                .append("<input class=\"form-check-input\" type=\"checkbox\" id=\"feat-ai-replace\">")
                .append("<span class=\"form-check-label\">Replace existing</span>")
                .append("</label>");
        s.append("<span class=\"text-secondary small\" id=\"feat-ai-status\" aria-live=\"polite\"></span>");
        s.append("</div>");

        s.append("<div class=\"mb-3 landing-list\" id=\"feat-list\">");
        if (values != null) {
            for (LandingFeature f : values) s.append(featureRow(f));
        }
        s.append("</div>");
        s.append("<button type=\"button\" class=\"btn btn-sm btn-outline-primary mt-1\" id=\"feat-add\">")
                .append("<i class=\"bi bi-plus-lg me-1\"></i>add feature</button>");
        s.append("<template id=\"feat-row-template\">").append(featureRow(null)).append("</template>");
        s.append(LANDING_LIST_ASSETS);
        s.append(featureListScript());
        s.append(LANDING_AI_SCRIPT);
        return s.toString();
    }

    private static String featureRow(LandingFeature f) {
        String icon  = f == null ? "shield" : (f.icon()  == null ? "" : f.icon());
        String title = f == null ? "" : (f.title() == null ? "" : f.title());
        String body  = f == null ? "" : (f.body()  == null ? "" : f.body());
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"landing-row feat-row\" draggable=\"true\">");
        s.append("<div class=\"landing-row-head\">");
        s.append("<span class=\"landing-handle\" title=\"Drag to reorder\" aria-label=\"drag to reorder\">&#8801;</span>");
        s.append("<select class=\"form-select form-select-sm icon-select\" name=\"landing_feature_icon\" aria-label=\"Icon\">");
        for (String tok : LandingFeature.ICONS) {
            s.append("<option value=\"").append(escape(tok)).append("\"")
                    .append(tok.equals(icon) ? " selected" : "").append(">")
                    .append(escape(tok)).append("</option>");
        }
        s.append("</select>");
        s.append("<input type=\"text\" name=\"landing_feature_title\" class=\"form-control form-control-sm\""
                + " value=\"").append(escape(title)).append("\" placeholder=\"Title\">");
        s.append(polishBtn("feature_title", "Polish this title"));
        s.append("<button type=\"button\" class=\"btn btn-sm btn-outline-danger landing-remove\""
                + " title=\"Remove this card\" aria-label=\"Remove\"><i class=\"bi bi-x-lg\"></i></button>");
        s.append("</div>");
        s.append("<div class=\"landing-body-row\">");
        s.append("<textarea name=\"landing_feature_body\" class=\"form-control form-control-sm landing-body\""
                + " rows=\"2\" placeholder=\"One- or two-sentence description\">")
                .append(escape(body)).append("</textarea>");
        s.append(polishBtn("feature_body", "Polish this description"));
        s.append("</div>");
        s.append("</div>");
        return s.toString();
    }

    /* ---------- landing FAQ editor ---------- */

    private static String landingFaqsEditor(List<LandingFaq> values) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"mb-3 landing-list\" id=\"faq-edit-list\">");
        if (values != null) {
            for (LandingFaq q : values) s.append(faqRow(q));
        }
        s.append("</div>");
        s.append("<button type=\"button\" class=\"btn btn-sm btn-outline-primary mt-1\" id=\"faq-edit-add\">")
                .append("<i class=\"bi bi-plus-lg me-1\"></i>add FAQ</button>");
        s.append("<template id=\"faq-row-template\">").append(faqRow(null)).append("</template>");
        s.append(faqListScript());
        return s.toString();
    }

    private static String faqRow(LandingFaq q) {
        String question = q == null ? "" : (q.question() == null ? "" : q.question());
        String answer   = q == null ? "" : (q.answer()   == null ? "" : q.answer());
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"landing-row faq-row\" draggable=\"true\">");
        s.append("<div class=\"landing-row-head\">");
        s.append("<span class=\"landing-handle\" title=\"Drag to reorder\" aria-label=\"drag to reorder\">&#8801;</span>");
        s.append("<input type=\"text\" name=\"landing_faq_question\" class=\"form-control form-control-sm\""
                + " value=\"").append(escape(question)).append("\" placeholder=\"Question\">");
        s.append(polishBtn("faq_question", "Polish this question"));
        s.append("<button type=\"button\" class=\"btn btn-sm btn-outline-danger landing-remove\""
                + " title=\"Remove this entry\" aria-label=\"Remove\"><i class=\"bi bi-x-lg\"></i></button>");
        s.append("</div>");
        s.append("<div class=\"landing-body-row\">");
        s.append("<textarea name=\"landing_faq_answer\" class=\"form-control form-control-sm landing-body\""
                + " rows=\"2\" placeholder=\"Answer\">")
                .append(escape(answer)).append("</textarea>");
        s.append(polishBtn("faq_answer", "Polish this answer"));
        s.append("</div>");
        s.append("</div>");
        return s.toString();
    }

    /**
     * Renders the "Polish with AI" button. Wires up to the global JS in
     * {@link #LANDING_AI_SCRIPT} which looks at data-polish-kind to know which
     * adjacent input/textarea to operate on and which system prompt to use.
     */
    private static String polishBtn(String kind, String tip) {
        return "<button type=\"button\" class=\"btn btn-sm btn-outline-secondary landing-polish\""
                + " data-polish-kind=\"" + escape(kind) + "\""
                + " title=\"" + escape(tip) + "\" aria-label=\"" + escape(tip) + "\">"
                + "<i class=\"bi bi-stars\"></i></button>";
    }

    /**
     * Wires the "Polish with AI" button on every landing field and the
     * "Generate from plugins" button at the top of the features editor.
     *
     * Replace strategy uses {@code document.execCommand('insertText')} on a
     * fully-selected field so the browser's native undo (CTRL+Z) restores the
     * original text. Falling back to {@code field.value = ...} loses undo but
     * still works.
     */
    private static final String LANDING_AI_SCRIPT =
            "<style>"
            + ".landing-body-row{display:flex;align-items:flex-start;gap:.4rem}"
            + ".landing-body-row textarea{flex:1 1 auto}"
            + ".landing-body-row .landing-polish{flex:0 0 auto;align-self:flex-start;line-height:1;padding:.25rem .5rem;margin-top:.05rem}"
            + ".landing-row-head .landing-polish{flex:0 0 auto;line-height:1;padding:.25rem .5rem}"
            + ".landing-polish.busy{pointer-events:none;opacity:.6}"
            + ".landing-polish.busy .bi-stars:before{content:'\\f130'}" // bi-arrow-clockwise
            + "</style>"
            + "<script>(function(){"
            // --- helpers ---
            + "function aiReplace(field,newValue){"
            + "if(!field)return;"
            + "field.focus();"
            + "if(field.tagName==='TEXTAREA'||field.type==='text'){"
            + "field.setSelectionRange(0,field.value.length);"
            + "var ok=false;try{ok=document.execCommand('insertText',false,newValue);}catch(e){ok=false;}"
            + "if(!ok){field.value=newValue;}"
            + "}else{field.value=newValue;}"
            + "field.setSelectionRange(newValue.length,newValue.length);"
            + "field.dispatchEvent(new Event('input',{bubbles:true}));"
            + "}"
            + "function fieldForButton(btn){"
            + "var kind=btn.getAttribute('data-polish-kind');"
            + "var row=btn.closest('.landing-row');"
            + "if(!row)return null;"
            + "if(kind==='feature_title'||kind==='faq_question')"
            + "return row.querySelector('input[name=\"landing_feature_title\"],input[name=\"landing_faq_question\"]');"
            + "if(kind==='feature_body'||kind==='faq_answer')"
            + "return row.querySelector('textarea[name=\"landing_feature_body\"],textarea[name=\"landing_faq_answer\"]');"
            + "return null;"
            + "}"
            + "function flash(btn,ok){var c=ok?'btn-outline-success':'btn-outline-danger';var orig=btn.className;"
            + "btn.className=btn.className.replace('btn-outline-secondary',c);"
            + "setTimeout(function(){btn.className=orig;},900);}"
            + "function setBusy(btn,busy){btn.classList.toggle('busy',!!busy);}"
            // --- polish button click ---
            + "document.addEventListener('click',function(e){"
            + "var btn=e.target.closest('.landing-polish');if(!btn)return;"
            + "e.preventDefault();"
            + "var field=fieldForButton(btn);if(!field)return;"
            + "var text=(field.value||'').trim();"
            + "if(!text){flash(btn,false);return;}"
            + "setBusy(btn,true);"
            + "var body=new URLSearchParams();"
            + "body.set('kind',btn.getAttribute('data-polish-kind')||'');"
            + "body.set('text',text);"
            + "fetch('/dash/landing-ai/polish',{method:'POST',credentials:'same-origin',"
            + "headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString()})"
            + ".then(function(r){return r.json().catch(function(){return{ok:false,message:'HTTP '+r.status};});})"
            + ".then(function(j){if(j&&j.ok&&j.polished){aiReplace(field,j.polished);flash(btn,true);}"
            + "else{flash(btn,false);console.warn('polish failed',j);}})"
            + ".catch(function(err){flash(btn,false);console.warn('polish error',err);})"
            + ".finally(function(){setBusy(btn,false);});"
            + "});"
            // --- generate from plugins ---
            + "var gen=document.getElementById('feat-ai-generate');"
            + "if(gen){gen.addEventListener('click',function(){"
            + "var status=document.getElementById('feat-ai-status');"
            + "var countEl=document.getElementById('feat-ai-count');"
            + "var replaceEl=document.getElementById('feat-ai-replace');"
            + "var list=document.getElementById('feat-list');"
            + "var tpl=document.getElementById('feat-row-template');"
            + "if(!list||!tpl)return;"
            + "var count=countEl?Math.max(1,Math.min(12,parseInt(countEl.value,10)||6)):6;"
            + "gen.disabled=true;if(status)status.textContent='Asking the AI - this can take 10-30 seconds.';"
            + "var body=new URLSearchParams();body.set('count',String(count));"
            + "fetch('/dash/landing-ai/features-from-plugins',{method:'POST',credentials:'same-origin',"
            + "headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString()})"
            + ".then(function(r){return r.json().catch(function(){return{ok:false,message:'HTTP '+r.status};});})"
            + ".then(function(j){"
            + "if(!j||!j.ok||!j.features){if(status)status.textContent='Failed: '+((j&&j.message)||'unknown error');return;}"
            + "if(replaceEl&&replaceEl.checked){"
            + "list.querySelectorAll('.feat-row').forEach(function(r){r.remove();});"
            + "}"
            + "j.features.forEach(function(feat){"
            + "var frag=tpl.content.cloneNode(true);"
            + "var row=frag.querySelector('.feat-row');"
            + "if(row){"
            + "var iconSel=row.querySelector('select[name=\"landing_feature_icon\"]');"
            + "var titleIn=row.querySelector('input[name=\"landing_feature_title\"]');"
            + "var bodyIn =row.querySelector('textarea[name=\"landing_feature_body\"]');"
            + "if(iconSel)iconSel.value=feat.icon||'shield';"
            + "if(titleIn)titleIn.value=feat.title||'';"
            + "if(bodyIn) bodyIn.value =feat.body||'';"
            + "list.appendChild(frag);"
            + "}"
            + "});"
            + "if(status)status.textContent='Added '+j.features.length+' feature card(s). Review before saving.';"
            + "})"
            + ".catch(function(err){if(status)status.textContent='Network error: '+(err.message||err);})"
            + ".finally(function(){gen.disabled=false;});"
            + "});}"
            + "})();</script>";

    /** Shared styling for the two reorderable landing-content editors. */
    private static final String LANDING_LIST_ASSETS =
            "<style>"
            + ".landing-list{display:flex;flex-direction:column;gap:.55rem;margin-bottom:.5rem}"
            + ".landing-row{display:flex;flex-direction:column;gap:.4rem;"
            + "background:var(--bs-tertiary-bg);border:1px solid var(--bs-border-color);"
            + "border-radius:.5rem;padding:.55rem .6rem}"
            + ".landing-row.dragging{opacity:.4}"
            + ".landing-row.drop-above{box-shadow:inset 0 2px 0 0 var(--bs-primary,#0d6efd)}"
            + ".landing-row.drop-below{box-shadow:inset 0 -2px 0 0 var(--bs-primary,#0d6efd)}"
            + ".landing-row-head{display:flex;align-items:center;gap:.4rem}"
            + ".landing-handle{cursor:grab;user-select:none;color:var(--bs-secondary-color);"
            + "font-size:1.25rem;line-height:1;padding:.1rem .35rem;border-radius:.25rem;flex:0 0 auto}"
            + ".landing-row:hover .landing-handle{background:rgba(127,127,127,.12)}"
            + ".landing-handle:active{cursor:grabbing}"
            + ".landing-row-head .icon-select{flex:0 0 auto;width:auto;min-width:8rem}"
            + ".landing-row-head input[type=text]{flex:1 1 auto}"
            + ".landing-row-head .landing-remove{flex:0 0 auto;line-height:1;padding:.25rem .55rem}"
            + ".landing-body{font-size:.92rem}"
            + "</style>";

    private static String featureListScript() {
        return landingListScript("feat-list", "feat-add", "feat-row-template", "feat-row");
    }

    private static String faqListScript() {
        return landingListScript("faq-edit-list", "faq-edit-add", "faq-row-template", "faq-row");
    }

    /**
     * Vanilla drag-and-drop + add/remove wiring shared by both list editors.
     * Each call emits an IIFE scoped by element id so the two editors don't
     * trample each other's state.
     */
    private static String landingListScript(String listId, String addId, String tplId, String rowClass) {
        // Fully delegated handlers - the parent list is the single source of truth
        // for click/drag events, so rows added later (by the AI generator or any
        // other JS) work without an explicit wire-up step.
        return "<script>(function(){"
                + "var list=document.getElementById(" + jsQuote(listId) + ");"
                + "var addBtn=document.getElementById(" + jsQuote(addId) + ");"
                + "var tpl=document.getElementById(" + jsQuote(tplId) + ");"
                + "if(!list||!addBtn||!tpl)return;"
                + "var rowSel='.' + " + jsQuote(rowClass) + ";"
                + "var dragging=null;"
                + "function clearMarkers(){list.querySelectorAll(rowSel).forEach(function(r){r.classList.remove('drop-above','drop-below');});}"
                // Delegated click - the remove button works on any row, anytime.
                + "list.addEventListener('click',function(e){"
                + "var rm=e.target.closest('.landing-remove');if(!rm)return;"
                + "var row=rm.closest(rowSel);if(!row)return;"
                + "e.preventDefault();row.remove();"
                + "});"
                // Drag handlers are also delegated. We block drag from inside form
                // controls so a click in an <input> doesn't fire a drag.
                + "list.addEventListener('dragstart',function(e){"
                + "var row=e.target.closest(rowSel);if(!row||!list.contains(row))return;"
                + "if(e.target&&(e.target.tagName==='INPUT'||e.target.tagName==='TEXTAREA'||e.target.tagName==='SELECT'||e.target.tagName==='BUTTON')){e.preventDefault();return;}"
                + "dragging=row;row.classList.add('dragging');"
                + "if(e.dataTransfer){e.dataTransfer.effectAllowed='move';try{e.dataTransfer.setData('text/plain','x');}catch(_){}}"
                + "});"
                + "list.addEventListener('dragend',function(){if(dragging)dragging.classList.remove('dragging');dragging=null;clearMarkers();});"
                + "list.addEventListener('dragover',function(e){"
                + "if(!dragging)return;"
                + "var row=e.target.closest(rowSel);if(!row||row===dragging)return;"
                + "e.preventDefault();if(e.dataTransfer)e.dataTransfer.dropEffect='move';"
                + "var rect=row.getBoundingClientRect();var before=(e.clientY-rect.top)<(rect.height/2);"
                + "clearMarkers();row.classList.add(before?'drop-above':'drop-below');"
                + "});"
                + "list.addEventListener('dragleave',function(e){"
                + "var row=e.target.closest(rowSel);if(row)row.classList.remove('drop-above','drop-below');"
                + "});"
                + "list.addEventListener('drop',function(e){"
                + "if(!dragging)return;"
                + "var row=e.target.closest(rowSel);if(!row||row===dragging){clearMarkers();return;}"
                + "e.preventDefault();"
                + "var rect=row.getBoundingClientRect();var before=(e.clientY-rect.top)<(rect.height/2);"
                + "if(before){row.parentNode.insertBefore(dragging,row);}else{row.parentNode.insertBefore(dragging,row.nextSibling);}"
                + "clearMarkers();"
                + "});"
                + "addBtn.addEventListener('click',function(){"
                + "var frag=tpl.content.cloneNode(true);"
                + "var row=frag.querySelector(rowSel);"
                + "list.appendChild(frag);"
                + "if(row){var first=row.querySelector('input,textarea,select');if(first)first.focus();}"
                + "});"
                + "})();</script>";
    }

    /* ---------- form helpers ---------- */

    private record RadioOpt(String value, String label, String tip) {
        RadioOpt(String value, String label) { this(value, label, null); }
    }

    private static String section(String title, String hintHtml) {
        return "<h2 class=\"h5 mt-4 mb-2\">" + escape(title) + "</h2>"
                + (hintHtml == null ? "" : hintHtml);
    }

    private static String checkbox(String name, String label, boolean checked) {
        return checkbox(name, label, checked, null);
    }

    private static String checkbox(String name, String label, boolean checked, String tip) {
        String id = "cb_" + name;
        return "<div class=\"form-check mb-2\">"
                + "<input type=hidden name=\"" + name + "\" value=\"off\">"
                + "<input class=\"form-check-input\" type=checkbox name=\"" + name
                + "\" value=\"on\" id=\"" + id + "\"" + (checked ? " checked" : "") + ">"
                + "<label class=\"form-check-label\" for=\"" + id + "\">"
                + escape(label) + Layout.infoIcon(tip) + "</label></div>";
    }

    private static String textField(String name, String label, String value) {
        return textField(name, label, value, null);
    }

    private static String textField(String name, String label, String value, String tip) {
        return "<div class=mb-3><label class=\"form-label\" for=\"f_" + name + "\">"
                + escape(label) + Layout.infoIcon(tip) + "</label>"
                + "<input class=\"form-control\" id=\"f_" + name + "\" type=text name=\"" + name
                + "\" value=\"" + escape(value == null ? "" : value) + "\"></div>";
    }

    private static String numberField(String name, String label, String value, String min, String max, String step) {
        return numberField(name, label, value, min, max, step, null);
    }

    private static String numberField(String name, String label, String value, String min, String max, String step, String tip) {
        return "<div class=mb-3><label class=\"form-label\" for=\"f_" + name + "\">"
                + escape(label) + Layout.infoIcon(tip) + "</label>"
                + "<input class=\"form-control\" id=\"f_" + name + "\" type=number name=\"" + name
                + "\" value=\"" + escape(value == null ? "" : value)
                + "\" min=\"" + min + "\" max=\"" + max + "\" step=\"" + step + "\"></div>";
    }

    private static String textareaField(String name, String label, String value, int rows, String hint) {
        return textareaField(name, label, value, rows, hint, null);
    }

    private static String textareaField(String name, String label, String value, int rows, String hint, String tip) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=mb-3><label class=\"form-label\" for=\"f_").append(name).append("\">")
                .append(escape(label)).append(Layout.infoIcon(tip));
        if (hint != null && !hint.isBlank()) {
            s.append(" <em class=\"text-secondary fst-normal small d-block\">").append(escape(hint)).append("</em>");
        }
        s.append("</label><textarea class=\"form-control tpl\" id=\"f_").append(name)
                .append("\" name=\"").append(name).append("\" rows=\"").append(rows).append("\">")
                .append(escape(value == null ? "" : value)).append("</textarea></div>");
        return s.toString();
    }

    /**
     * Like {@link #textareaField} but prefixes the textarea with a Word-style toolbar of
     * Discord-markdown formatting buttons (bold/italic/underline/strike/code/quote/heading/link)
     * and a row of placeholder-insert chips. Click a button to wrap the current selection
     * (or insert at the caret).
     */
    private static String templateField(String name, String label, String value, int rows,
                                        String hint, String tip, List<String> placeholders) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=mb-3><label class=\"form-label\" for=\"f_").append(name).append("\">")
                .append(escape(label)).append(Layout.infoIcon(tip));
        if (hint != null && !hint.isBlank()) {
            s.append(" <em class=\"text-secondary fst-normal small d-block\">").append(escape(hint)).append("</em>");
        }
        s.append("</label>");
        s.append(toolbarHtml(placeholders));
        s.append("<textarea class=\"form-control tpl\" id=\"f_").append(name)
                .append("\" name=\"").append(name).append("\" rows=\"").append(rows).append("\">")
                .append(escape(value == null ? "" : value)).append("</textarea></div>");
        return s.toString();
    }

    private static String toolbarHtml(List<String> placeholders) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=tpl-toolbar>");

        s.append("<div class=tpl-group>");
        s.append(tbBtn("bold",      "B", "wrap",   "**", "Bold"));
        s.append(tbBtn("italic",    "I", "wrap",   "*",  "Italic"));
        s.append(tbBtn("underline", "U", "wrap",   "__", "Underline"));
        s.append(tbBtn("strike",    "S", "wrap",   "~~", "Strikethrough"));
        s.append("</div><div class=tpl-sep></div>");

        s.append("<div class=tpl-group>");
        s.append(tbBtn("code", "&lt;/&gt;", "wrap", "`",                "Inline code"));
        s.append(tbBtn("code", "{ }",       "wrap", "```\n|||\n```",    "Code block"));
        s.append("</div><div class=tpl-sep></div>");

        s.append("<div class=tpl-group>");
        s.append(tbBtn("h",  "H1",      "prefix", "# ",   "Heading 1"));
        s.append(tbBtn("h",  "H2",      "prefix", "## ",  "Heading 2"));
        s.append(tbBtn("h",  "H3",      "prefix", "### ", "Heading 3"));
        s.append(tbBtn("q",  "&rdquo;", "prefix", "> ",   "Block quote"));
        s.append(tbBtn("li", "&bull;",  "prefix", "- ",   "Bulleted list"));
        s.append("</div><div class=tpl-sep></div>");

        s.append("<div class=tpl-group>");
        s.append(tbBtn("lnk",   "Link",    "link", "",   "Insert link (wraps selection or inserts [text](url))"));
        s.append(tbBtn("spoil", "Spoiler", "wrap", "||", "Spoiler (Discord ||hidden||)"));
        s.append("</div>");

        if (placeholders != null && !placeholders.isEmpty()) {
            s.append("<div class=tpl-sep></div>");
            s.append("<div class=\"tpl-group tpl-placeholders\">");
            for (String p : placeholders) {
                s.append(tbBtn("chip", escape(p), "insert", p, "Insert " + p));
            }
            s.append("</div>");
        }

        s.append("</div>");
        return s.toString();
    }

    private static String tbBtn(String cls, String labelHtml, String action, String payload, String tip) {
        String onclick = "tplInsert(this," + jsQuote(action) + "," + jsQuote(payload) + ")";
        return "<button type=button class=\"tpl-btn " + cls + "\" title=\"" + escape(tip)
                + "\" onclick=\"" + escape(onclick) + "\">" + labelHtml + "</button>";
    }

    private static String jsQuote(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    private static String selectField(String name, String label, List<GuildLookup.Option> opts,
                                      String selected, boolean allowBlank) {
        return selectField(name, label, opts, selected, allowBlank, null);
    }

    /**
     * Like {@link #selectField} but the visible &lt;select&gt; is disabled when the
     * current user lacks permission to change it. The currently-saved value is
     * still posted via a hidden input so the form round-trip preserves it.
     */
    private static String restrictedSelect(String name, String label, List<GuildLookup.Option> opts,
                                           String selected, String tip, boolean editable) {
        if (editable) {
            return selectField(name, label, opts, selected, true, tip);
        }
        StringBuilder s = new StringBuilder();
        s.append("<div class=mb-3><label class=\"form-label\" for=\"f_").append(name).append("\">")
                .append(escape(label)).append(Layout.infoIcon(tip)).append("</label>");
        // Hidden input ensures the saved value is round-tripped on submit.
        s.append("<input type=hidden name=\"").append(name).append("\" value=\"")
                .append(escape(selected == null ? "" : selected)).append("\">");
        s.append("<select class=\"form-select\" id=\"f_").append(name)
                .append("\" disabled aria-disabled=true title=\"Server owner only\">");
        s.append("<option value=\"\"")
                .append((selected == null || selected.isBlank()) ? " selected" : "")
                .append(">(unset)</option>");
        for (var o : opts) {
            s.append("<option value=\"").append(escape(o.id())).append("\"")
                    .append(o.id().equals(selected) ? " selected" : "").append(">")
                    .append(escape(o.name())).append("</option>");
        }
        s.append("</select></div>");
        return s.toString();
    }

    private static String selectField(String name, String label, List<GuildLookup.Option> opts,
                                      String selected, boolean allowBlank, String tip) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=mb-3><label class=\"form-label\" for=\"f_").append(name).append("\">")
                .append(escape(label)).append(Layout.infoIcon(tip)).append("</label>");
        s.append("<select class=\"form-select\" id=\"f_").append(name)
                .append("\" name=\"").append(name).append("\">");
        if (allowBlank) s.append("<option value=\"\">(unset)</option>");
        for (var o : opts) {
            s.append("<option value=\"").append(escape(o.id())).append("\"")
                    .append(o.id().equals(selected) ? " selected" : "").append(">")
                    .append(escape(o.name())).append("</option>");
        }
        s.append("</select></div>");
        return s.toString();
    }

    /** Like selectField but takes plain RadioOpt entries (value+label, no GuildLookup.Option). */
    private static String selectFieldRaw(String name, String label, List<RadioOpt> opts, String selected) {
        return selectFieldRaw(name, label, opts, selected, null);
    }

    private static String selectFieldRaw(String name, String label, List<RadioOpt> opts,
                                         String selected, String tip) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=mb-3><label class=\"form-label\" for=\"f_").append(name).append("\">")
                .append(escape(label)).append(Layout.infoIcon(tip)).append("</label>");
        s.append("<select class=\"form-select\" id=\"f_").append(name)
                .append("\" name=\"").append(name).append("\">");
        for (var o : opts) {
            s.append("<option value=\"").append(escape(o.value())).append("\"")
                    .append(o.value().equals(selected) ? " selected" : "")
                    .append(o.tip() == null ? "" : " title=\"" + escape(o.tip()) + "\"")
                    .append(">")
                    .append(escape(o.label())).append("</option>");
        }
        s.append("</select></div>");
        return s.toString();
    }

    private static String multiCheckboxField(String name, String label,
                                              List<GuildLookup.Option> opts,
                                              List<String> selected, String tip) {
        java.util.Set<String> sel = (selected == null)
                ? java.util.Set.of()
                : new java.util.LinkedHashSet<>(selected);
        java.util.Set<String> known = new java.util.HashSet<>();
        for (var o : opts) known.add(o.id());

        StringBuilder s = new StringBuilder();
        s.append("<div class=mb-3><label class=\"form-label\">")
                .append(escape(label)).append(Layout.infoIcon(tip)).append("</label>");
        s.append("<div class=multicheck>");

        for (String sid : sel) {
            if (sid != null && !sid.isBlank() && !known.contains(sid)) {
                String id = "mc_" + name + "_" + Integer.toHexString(sid.hashCode());
                s.append("<div class=form-check>")
                        .append("<input class=form-check-input type=checkbox name=\"").append(name)
                        .append("\" value=\"").append(escape(sid)).append("\" id=\"").append(id).append("\" checked>")
                        .append("<label class=form-check-label for=\"").append(id).append("\">")
                        .append("(currently set; not visible to bot: ")
                        .append(escape(sid)).append(")</label></div>");
            }
        }
        for (var o : opts) {
            boolean checked = sel.contains(o.id());
            String id = "mc_" + name + "_" + Integer.toHexString(o.id().hashCode());
            s.append("<div class=form-check>")
                    .append("<input class=form-check-input type=checkbox name=\"").append(name)
                    .append("\" value=\"").append(escape(o.id())).append("\" id=\"").append(id).append("\"")
                    .append(checked ? " checked" : "").append(">")
                    .append("<label class=form-check-label for=\"").append(id).append("\">")
                    .append(escape(o.name())).append("</label></div>");
        }
        if (opts.isEmpty() && sel.isEmpty()) {
            s.append("<p class=\"text-secondary small mb-0\">No roles available. Connect Discord (set the bot token and guild id in <code>plugins/Warden/config.yml</code>) to populate this list.</p>");
        }
        s.append("</div></div>");
        return s.toString();
    }

    private static String radioGroup(String name, List<RadioOpt> opts, String selected) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"mb-3 border rounded p-3 bg-body-tertiary\">");
        for (var o : opts) {
            String id = "r_" + name + "_" + Integer.toHexString(o.value().hashCode());
            s.append("<div class=\"form-check\">")
                    .append("<input class=\"form-check-input\" type=radio name=\"").append(name)
                    .append("\" value=\"").append(escape(o.value())).append("\" id=\"").append(id).append("\"")
                    .append(o.value().equals(selected) ? " checked" : "").append(">")
                    .append("<label class=\"form-check-label\" for=\"").append(id).append("\">")
                    .append(escape(o.label())).append(Layout.infoIcon(o.tip())).append("</label>")
                    .append("</div>");
        }
        s.append("</div>");
        return s.toString();
    }

    private static boolean bool(Context ctx, String key) {
        // Each checkbox is preceded by a hidden field with value "off". If checked, the
        // browser also submits "on" (second value wins in Javalin).
        var vals = ctx.formParams(key);
        if (vals == null || vals.isEmpty()) return false;
        String last = vals.get(vals.size() - 1);
        return "on".equalsIgnoreCase(last) || "true".equalsIgnoreCase(last) || "1".equals(last);
    }

    private static String str(Context ctx, String key) {
        String v = ctx.formParam(key);
        return v == null ? "" : v;
    }

    private static double doubleOr(Context ctx, String key, double fallback) {
        try { return Double.parseDouble(ctx.formParam(key)); }
        catch (Exception e) { return fallback; }
    }

    private static int parseIntOr(String raw, int fallback) {
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static String escape(String s) {
        return Layout.escape(s);
    }

    @SuppressWarnings("unused")
    private static final String JSON_HINT = Json.writeStringList(java.util.List.of());

    /** Best-effort diff of two Settings rows; returns the names of the fields that changed. */
    private static java.util.List<String> diffSettings(Settings prev, Settings next) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (prev == null || next == null) return out;
        if (!java.util.Objects.equals(prev.rulesMarkdown(),       next.rulesMarkdown()))       out.add("rules_markdown");
        if (!java.util.Objects.equals(prev.gatedRoleId(),         next.gatedRoleId()))         out.add("gated_role_id");
        if (!java.util.Objects.equals(prev.fullRoleId(),          next.fullRoleId()))          out.add("full_role_id");
        if (!java.util.Objects.equals(prev.modRoleId(),           next.modRoleId()))           out.add("mod_role_id");
        if (!java.util.Objects.equals(prev.configAdminRoleId(),   next.configAdminRoleId()))   out.add("config_admin_role_id");
        if (!java.util.Objects.equals(prev.webManagerRoleId(),    next.webManagerRoleId()))    out.add("web_manager_role_id");
        if (!java.util.Objects.equals(prev.welcomeChannelId(),    next.welcomeChannelId()))    out.add("welcome_channel_id");
        if (!java.util.Objects.equals(prev.modReviewChannelId(),  next.modReviewChannelId()))  out.add("mod_review_channel_id");
        if (!java.util.Objects.equals(prev.llmSystemPrompt(),     next.llmSystemPrompt()))     out.add("llm_system_prompt");
        if (Double.compare(prev.llmAutoApproveThreshold(), next.llmAutoApproveThreshold()) != 0) out.add("llm_auto_approve_threshold");
        if (Double.compare(prev.llmAutoDenyThreshold(),    next.llmAutoDenyThreshold())    != 0) out.add("llm_auto_deny_threshold");
        if (prev.llmAutoDenyEnabled() != next.llmAutoDenyEnabled()) out.add("llm_auto_deny_enabled");
        if (!java.util.Objects.equals(prev.llmApiKey(),  next.llmApiKey()))  out.add("llm_api_key");
        if (!java.util.Objects.equals(prev.llmBaseUrl(), next.llmBaseUrl())) out.add("llm_base_url");
        if (!java.util.Objects.equals(prev.llmModel(),   next.llmModel()))   out.add("llm_model");

        FlowConfig pf = prev.flow();
        FlowConfig nf = next.flow();
        if (pf == null || nf == null) {
            if (pf != nf) out.add("flow");
            return out;
        }
        if (pf.deliveryViaDm()             != nf.deliveryViaDm())             out.add("flow.delivery_via_dm");
        if (pf.deliveryViaChannel()        != nf.deliveryViaChannel())        out.add("flow.delivery_via_channel");
        if (!java.util.Objects.equals(pf.deliveryChannelId(),       nf.deliveryChannelId()))       out.add("flow.delivery_channel_id");
        if (!java.util.Objects.equals(pf.deliveryMessageTemplate(), nf.deliveryMessageTemplate())) out.add("flow.delivery_message_template");
        if (pf.entryViaDiscordButton()     != nf.entryViaDiscordButton())     out.add("flow.entry_via_discord_button");
        if (pf.entryViaWebCode()           != nf.entryViaWebCode())           out.add("flow.entry_via_web_code");
        if (pf.entryViaWebOauth()          != nf.entryViaWebOauth())          out.add("flow.entry_via_web_oauth");
        if (pf.gatingEnabled()             != nf.gatingEnabled())             out.add("flow.gating_enabled");
        if (pf.triageMode()                != nf.triageMode())                out.add("flow.triage_mode");
        if (pf.approveDmEnabled()          != nf.approveDmEnabled())          out.add("flow.approve_dm_enabled");
        if (!java.util.Objects.equals(pf.approveDmTemplate(),       nf.approveDmTemplate()))       out.add("flow.approve_dm_template");
        if (pf.approveChannelAnnounce()    != nf.approveChannelAnnounce())    out.add("flow.approve_channel_announce");
        if (!java.util.Objects.equals(pf.approveChannelTemplate(),  nf.approveChannelTemplate()))  out.add("flow.approve_channel_template");
        if (!java.util.Objects.equals(pf.approveExtraRoles(),       nf.approveExtraRoles()))       out.add("flow.approve_extra_roles");
        if (pf.denyDmEnabled()             != nf.denyDmEnabled())             out.add("flow.deny_dm_enabled");
        if (!java.util.Objects.equals(pf.denyDmTemplate(),          nf.denyDmTemplate()))          out.add("flow.deny_dm_template");
        if (pf.denyAction()                != nf.denyAction())                out.add("flow.deny_action");

        io.warden.onboarding.model.LandingConfig pl = prev.landing();
        io.warden.onboarding.model.LandingConfig nl = next.landing();
        if (pl == null || nl == null) {
            if (pl != nl) out.add("landing");
            return out;
        }
        if (!java.util.Objects.equals(pl.mode(),          nl.mode()))          out.add("landing.mode");
        if (!java.util.Objects.equals(pl.redirectUrl(),   nl.redirectUrl()))   out.add("landing.redirect_url");
        if (!java.util.Objects.equals(pl.serverName(),    nl.serverName()))    out.add("landing.server_name");
        if (!java.util.Objects.equals(pl.serverAddress(), nl.serverAddress())) out.add("landing.server_address");
        if (!java.util.Objects.equals(pl.tagline(),       nl.tagline()))       out.add("landing.tagline");
        if (!java.util.Objects.equals(pl.joinUrl(),       nl.joinUrl()))       out.add("landing.join_url");
        if (pl.mapEnabled()                != nl.mapEnabled())                out.add("landing.map_enabled");
        if (!java.util.Objects.equals(pl.mapProvider(),   nl.mapProvider()))   out.add("landing.map_provider");
        if (!java.util.Objects.equals(pl.mapUrl(),        nl.mapUrl()))        out.add("landing.map_url");
        if (!java.util.Objects.equals(pl.mapLabel(),      nl.mapLabel()))      out.add("landing.map_label");
        if (!java.util.Objects.equals(pl.brandImageUrl(),     nl.brandImageUrl()))     out.add("landing.brand_image_url");
        if (!java.util.Objects.equals(pl.heroImageUrl(),      nl.heroImageUrl()))      out.add("landing.hero_image_url");
        if (!java.util.Objects.equals(pl.accentColor(),       nl.accentColor()))       out.add("landing.accent_color");
        if (!java.util.Objects.equals(pl.papiPlayersOnline(), nl.papiPlayersOnline())) out.add("landing.papi_players_online");
        if (!java.util.Objects.equals(pl.papiDiscordMembers(),nl.papiDiscordMembers()))out.add("landing.papi_discord_members");
        if (!java.util.Objects.equals(pl.statPlayersLabel(),  nl.statPlayersLabel()))  out.add("landing.stat_players_label");
        if (!java.util.Objects.equals(pl.statMembersLabel(),  nl.statMembersLabel()))  out.add("landing.stat_members_label");
        if (!java.util.Objects.equals(pl.googleAnalyticsId(), nl.googleAnalyticsId())) out.add("landing.google_analytics_id");
        if (pl.cookieBannerEnabled()           != nl.cookieBannerEnabled())            out.add("landing.cookie_banner");
        if (pl.leaderboardEnabled()            != nl.leaderboardEnabled())             out.add("landing.leaderboard_enabled");
        if (!java.util.Objects.equals(pl.leaderboardTitle(),       nl.leaderboardTitle()))       out.add("landing.leaderboard_title");
        if (!java.util.Objects.equals(pl.leaderboardDescription(), nl.leaderboardDescription())) out.add("landing.leaderboard_description");
        if (pl.leaderboardTopN()               != nl.leaderboardTopN())                out.add("landing.leaderboard_top_n");
        if (!java.util.Objects.equals(pl.leaderboardLabel(),       nl.leaderboardLabel()))       out.add("landing.leaderboard_label");
        if (!java.util.Objects.equals(pl.promoVideoUrl(),          nl.promoVideoUrl()))          out.add("landing.promo_video_url");
        if (!java.util.Objects.equals(pl.features(),          nl.features()))          out.add("landing.features");
        if (!java.util.Objects.equals(pl.faqs(),          nl.faqs()))          out.add("landing.faqs");
        return out;
    }

    /** Accept only the three valid mode tokens; anything else falls back to "enabled". */
    private static String normaliseLandingMode(String s) {
        if (s == null) return "enabled";
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return ("disabled".equals(t) || "redirect".equals(t)) ? t : "enabled";
    }

    /**
     * Reassemble the feature list from three parallel form arrays. The browser
     * submits one value per row in DOM order for each name, so zipping them
     * by index preserves whatever reordering the operator did before saving.
     * Rows where both title and body are blank are dropped silently so the
     * landing renderer doesn't paint hollow cards.
     */
    private static List<LandingFeature> collectFeatures(Context ctx) {
        List<String> icons  = ctx.formParams("landing_feature_icon");
        List<String> titles = ctx.formParams("landing_feature_title");
        List<String> bodies = ctx.formParams("landing_feature_body");
        int n = Math.max(Math.max(safeSize(icons), safeSize(titles)), safeSize(bodies));
        List<LandingFeature> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String icon  = at(icons, i);
            String title = at(titles, i).trim();
            String body  = at(bodies, i).trim();
            if (title.isEmpty() && body.isEmpty()) continue;
            out.add(new LandingFeature(normaliseFeatureIcon(icon), title, body));
        }
        return out;
    }

    private static List<LandingFaq> collectFaqs(Context ctx) {
        List<String> qs = ctx.formParams("landing_faq_question");
        List<String> as = ctx.formParams("landing_faq_answer");
        int n = Math.max(safeSize(qs), safeSize(as));
        List<LandingFaq> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String q = at(qs, i).trim();
            String a = at(as, i).trim();
            if (q.isEmpty() && a.isEmpty()) continue;
            out.add(new LandingFaq(q, a));
        }
        return out;
    }

    /** Restrict to the palette WelcomeHandlers knows how to render; fall back to "shield". */
    private static String normaliseFeatureIcon(String s) {
        if (s == null) return "shield";
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return LandingFeature.ICONS.contains(t) ? t : "shield";
    }

    private static int safeSize(List<String> l) { return l == null ? 0 : l.size(); }

    private static String at(List<String> l, int i) {
        if (l == null || i < 0 || i >= l.size()) return "";
        String v = l.get(i);
        return v == null ? "" : v;
    }

    /** Force a #RRGGBB form, falling back to the prior default. */
    private static String normaliseHex(String s) {
        if (s == null) return "#39beff";
        String t = s.trim();
        if (t.isEmpty()) return "#39beff";
        if (t.matches("^#[0-9a-fA-F]{6}$")) return t.toLowerCase(java.util.Locale.ROOT);
        if (t.matches("^[0-9a-fA-F]{6}$"))  return "#" + t.toLowerCase(java.util.Locale.ROOT);
        if (t.matches("^#[0-9a-fA-F]{3}$")) return t.toLowerCase(java.util.Locale.ROOT);
        return "#39beff";
    }

    /**
     * Accept GA4 (G-XXXXXXXXXX), Universal Analytics (UA-XXXX-Y) or Google Tag
     * Manager (GTM-XXXXX) identifiers. Anything else is silently coerced to ""
     * so a typo never injects an arbitrary string into the gtag script URL.
     */
    private static String normaliseGaId(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        return t.matches("^(G-|UA-|GTM-)[A-Za-z0-9\\-]{4,32}$") ? t : "";
    }

    /** Whitelist of known map plugin tokens. Anything else is treated as "custom". */
    private static String normaliseMapProvider(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (t) {
            case "dynmap", "pl3xmap", "bluemap", "squaremap", "custom" -> t;
            case "" -> "";
            default -> "custom";
        };
    }
}
