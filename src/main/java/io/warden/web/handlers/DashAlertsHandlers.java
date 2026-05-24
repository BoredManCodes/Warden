package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.alerts.Alert;
import io.warden.alerts.AlertContext;
import io.warden.alerts.AlertEmbedField;
import io.warden.alerts.AlertEvent;
import io.warden.alerts.AlertManager;
import io.warden.alerts.AlertService;
import io.warden.alerts.DsrvAlertImporter;
import io.warden.alerts.EventClassResolver;
import io.warden.discord.DiscordSrvBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /dash/alerts UI: list, new, edit, save, delete, toggle, test fire, and the
 * DSRV alerts.yml importer.
 *
 * Every mutation calls {@link AlertManager#reload()} so dynamic Bukkit
 * subscriptions track the current alerts table without a server restart.
 */
public final class DashAlertsHandlers {

    private final AlertService service;
    private final AlertManager manager;
    private final GuildLookup lookup;
    private final DiscordSrvBridge discordSrv;

    public DashAlertsHandlers(AlertService service, AlertManager manager, GuildLookup lookup,
                              DiscordSrvBridge discordSrv) {
        this.service = service;
        this.manager = manager;
        this.lookup = lookup;
        this.discordSrv = discordSrv;
    }

    /* =========================== List page =========================== */

    public void list(Context ctx) throws Exception {
        List<Alert> alerts = service.dao().listAll();
        String flash = ctx.queryParam("msg");
        String err = ctx.queryParam("err");
        String warn = ctx.queryParam("warn");

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Alerts · Warden", "alerts", ctx));
        h.append(ACTIONS_CSS);
        h.append("<div class=\"d-flex justify-content-between align-items-center mb-3\">");
        h.append("<div><h1 class=\"h3 mb-1\">Alerts</h1>")
                .append("<p class=\"text-secondary mb-0\">Hook a Minecraft or Discord event to a Discord message and/or a list of commands. ")
                .append("Built-in events drop into the picker; for everything else, paste a Bukkit event class or import a DSRV <code>alerts.yml</code>.</p></div>");
        h.append("<div class=\"d-flex gap-2\">")
                .append("<a href=\"/dash/alerts/import\" class=\"btn btn-outline-secondary\"><i class=\"bi bi-download me-1\"></i>Import from DSRV</a>")
                .append("<a href=\"/dash/alerts/new\" class=\"btn btn-primary\"><i class=\"bi bi-plus-lg me-1\"></i>New alert</a>")
                .append("</div>");
        h.append("</div>");

        if (!lookup.discordConnected()) {
            h.append("<div class=\"alert alert-warning\">Discord isn't connected; channel dropdowns will be empty. ")
                    .append("Set the bot token and guild id in <code>plugins/Warden/config.yml</code> and restart.</div>");
        }
        if (flash != null && !flash.isBlank()) {
            h.append("<div class=\"alert alert-success\">").append(Layout.escape(flash)).append("</div>");
        }
        if (err != null && !err.isBlank()) {
            h.append("<div class=\"alert alert-danger\">").append(Layout.escape(err)).append("</div>");
        }
        if (warn != null && !warn.isBlank()) {
            h.append("<div class=\"alert alert-warning\"><strong>Import warnings:</strong><ul class=\"mb-0 mt-1\">");
            for (String line : warn.split("\\r?\\n")) {
                if (line == null || line.isBlank()) continue;
                h.append("<li>").append(Layout.escape(line)).append("</li>");
            }
            h.append("</ul></div>");
        }

        h.append("<div class=\"card\"><div class=\"card-body p-0\">");
        h.append("<table class=\"table table-hover align-middle mb-0\">");
        h.append("<thead><tr>")
                .append("<th style=\"width:1%\"></th>")
                .append("<th>Name</th>")
                .append("<th>Trigger</th>")
                .append("<th>Channel</th>")
                .append("<th>Actions</th>")
                .append("<th style=\"width:1%\"></th>")
                .append("</tr></thead><tbody>");
        if (alerts.isEmpty()) {
            h.append("<tr><td colspan=6 class=\"text-secondary text-center py-4\">")
                    .append("No alerts yet. Click <strong>New alert</strong> or <strong>Import from DSRV</strong> to add your first one.</td></tr>");
        }
        var channelOpts = lookup.textChannels();
        for (Alert a : alerts) {
            String trigger = triggerLabel(a);
            String chLabel = a.channelId() == null || a.channelId().isBlank()
                    ? "<span class=text-secondary>(none)</span>"
                    : channelLabel(channelOpts, a.channelId());
            h.append("<tr>")
                    .append("<td class=\"text-center\">")
                    .append("<form method=post action=\"/dash/alerts/").append(a.id()).append("/toggle\" class=m-0>")
                    .append("<button type=submit class=\"btn btn-sm ")
                    .append(a.enabled() ? "btn-success" : "btn-outline-secondary")
                    .append("\" title=\"").append(a.enabled() ? "Enabled - click to disable" : "Disabled - click to enable").append("\">")
                    .append("<i class=\"bi ").append(a.enabled() ? "bi-toggle-on" : "bi-toggle-off").append("\"></i>")
                    .append("</button></form></td>")
                    .append("<td><a href=\"/dash/alerts/").append(a.id()).append("/edit\" class=\"fw-semibold text-decoration-none\">")
                    .append(Layout.escape(a.name().isBlank() ? "(unnamed)" : a.name())).append("</a></td>")
                    .append("<td>").append(trigger).append("</td>")
                    .append("<td>").append(chLabel).append("</td>")
                    .append("<td>").append(summariseActions(a)).append("</td>")
                    .append("<td class=\"text-end\">")
                    .append("<div class=\"alert-actions\" role=group aria-label=\"Alert actions\">")
                    .append("<a class=\"aa-btn aa-edit\" href=\"/dash/alerts/").append(a.id())
                    .append("/edit\" data-bs-toggle=tooltip data-bs-title=\"Edit alert\">")
                    .append("<i class=\"bi bi-pencil\"></i><span class=aa-label>Edit</span></a>")
                    .append("<form method=post action=\"/dash/alerts/").append(a.id()).append("/test\">")
                    .append("<button type=submit class=\"aa-btn aa-test\" data-bs-toggle=tooltip data-bs-title=\"Fire this alert with sample variables\">")
                    .append("<i class=\"bi bi-lightning-charge\"></i><span class=aa-label>Test</span></button></form>")
                    .append("<form method=post action=\"/dash/alerts/").append(a.id()).append("/delete\" data-confirm=\"Delete this alert?\" data-confirm-kind=\"danger\">")
                    .append("<button type=submit class=\"aa-btn aa-delete\" data-bs-toggle=tooltip data-bs-title=\"Delete alert\">")
                    .append("<i class=\"bi bi-trash\"></i><span class=aa-label>Delete</span></button></form>")
                    .append("</div></td>")
                    .append("</tr>");
        }
        h.append("</tbody></table></div></div>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private static String triggerLabel(Alert a) {
        if (a.isCustomTrigger()) {
            String shortName = a.triggerClass();
            int dot = shortName.lastIndexOf('.');
            if (dot > 0) shortName = shortName.substring(dot + 1);
            return "<code class=small title=\"" + Layout.escape(a.triggerClass()) + "\">"
                    + Layout.escape(shortName) + "</code>"
                    + " <span class=\"badge text-bg-warning ms-1\" title=\"Custom DSRV-style trigger with SpEL templates\">custom</span>";
        }
        AlertEvent e = a.parsedEvent();
        String label = e == null ? a.event() : e.label();
        return "<code class=small>" + Layout.escape(label) + "</code>";
    }

    /* =========================== New / edit form =========================== */

    public void newForm(Context ctx) throws Exception {
        Alert fresh = new Alert(
                0, "", true, "player_join", "", "",
                false, "", "", "#5865F2", "", "", "",
                "", "",
                List.of(),
                "", "", "",
                "", "", false, false,
                0, 0);
        renderForm(ctx, fresh, true);
    }

    public void editForm(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        Optional<Alert> opt = service.dao().find(id);
        if (opt.isEmpty()) {
            ctx.redirect("/dash/alerts?err=" + url("Alert not found."));
            return;
        }
        renderForm(ctx, opt.get(), false);
    }

    private void renderForm(Context ctx, Alert a, boolean isNew) {
        var channelOpts = lookup.textChannels();
        boolean advancedOpen = a.isCustomTrigger()
                || a.expressionsEnabled()
                || (a.conditions() != null && !a.conditions().isBlank())
                || a.asyncDispatch();

        StringBuilder h = new StringBuilder(16384);
        h.append(Layout.head((isNew ? "New alert" : "Edit alert") + " · Warden", "alerts", ctx));

        h.append("<div class=\"d-flex justify-content-between align-items-center mb-3\">");
        h.append("<div><h1 class=\"h3 mb-1\">").append(isNew ? "New alert" : "Edit alert").append("</h1>")
                .append("<p class=\"text-secondary mb-0\">Pick an event, choose what should happen, save.</p></div>");
        h.append("<a href=\"/dash/alerts\" class=\"btn btn-outline-secondary\"><i class=\"bi bi-arrow-left me-1\"></i>Back to list</a>");
        h.append("</div>");

        String action = isNew ? "/dash/alerts/new" : "/dash/alerts/" + a.id();
        h.append("<form method=post action=\"").append(action).append("\" id=\"alert-form\" class=\"row g-3\">");

        h.append("<div class=col-md-6>");
        h.append("<label class=form-label for=f_name>Name")
                .append(Layout.infoIcon("Internal label shown on the list. Not used in the rendered Discord message."))
                .append("</label>")
                .append("<input id=f_name class=form-control name=name maxlength=120 required value=\"")
                .append(Layout.escape(a.name())).append("\">");
        h.append("</div>");

        h.append("<div class=col-md-3>");
        h.append("<label class=form-label for=f_event>Event")
                .append(Layout.infoIcon("Built-in trigger. Ignored when a custom Bukkit class is set in Advanced."))
                .append("</label>")
                .append(eventSelect(a.event()));
        h.append("</div>");

        h.append("<div class=col-md-3 d-flex align-items-end>")
                .append("<div class=form-check form-switch fs-5\">")
                .append("<input class=form-check-input type=checkbox id=f_enabled name=enabled value=1 ")
                .append(a.enabled() ? "checked" : "").append(">")
                .append("<label class=form-check-label for=f_enabled>Enabled</label>")
                .append("</div></div>");

        h.append("<div class=col-12>");
        h.append("<div class=\"alert alert-info py-2 mb-0\" id=\"vars-hint\">")
                .append("<strong>Available variables:</strong> <span id=\"vars-list\"></span> ")
                .append("<span class=\"text-secondary small ms-2\">"
                        + "Use <code>{name}</code> in any text below. With <strong>Expressions enabled</strong> in Advanced, "
                        + "<code>{event.player.name}</code>, <code>{server.onlinePlayers.size()}</code> etc. work too. "
                        + "PlaceholderAPI placeholders like <code>%player_health%</code> always resolve when PAPI is installed.</span>")
                .append("</div>");
        h.append("</div>");

        // Discord message section
        h.append("<div class=col-12><hr><h2 class=\"h5 mt-2\">Discord message</h2>");
        h.append("<p class=\"text-secondary small mb-3\">Optional. Pick a channel to enable posting. ")
                .append("You can send plain content, an embed, or both.</p></div>");

        h.append("<div class=col-md-6>")
                .append("<label class=form-label for=f_channel>Channel")
                .append(Layout.infoIcon("Discord text channel to post in. Leave blank if this alert only runs commands."))
                .append("</label>")
                .append(GuildLookup.selectInline("channel_id", channelOpts, a.channelId(), "form-select", "(no Discord post)")
                        .replace("<select ", "<select id=f_channel "))
                .append("</div>");

        h.append("<div class=col-12>")
                .append("<label class=form-label for=f_content d-flex align-items-center justify-content-between\">")
                .append("<span>Message content")
                .append(Layout.infoIcon("Plain text posted alongside the embed. Up to 2000 characters after variable resolution."))
                .append("</span>")
                .append("<button type=\"button\" class=\"btn btn-sm btn-outline-secondary alert-ai-polish\" data-alert-ai=\"content\" data-target=\"f_content\" title=\"Polish or draft this message with AI\">")
                .append("<i class=\"bi bi-stars\"></i></button>")
                .append("</label>")
                .append("<textarea id=f_content name=message_content class=\"form-control tpl\" rows=2>")
                .append(Layout.escape(a.messageContent())).append("</textarea>")
                .append("</div>");

        // Embed sub-block
        h.append("<div class=col-12>");
        h.append("<div class=\"form-check form-switch\">")
                .append("<input class=form-check-input type=checkbox id=f_embed_enabled name=embed_enabled value=1 ")
                .append(a.embedEnabled() ? "checked" : "").append(">")
                .append("<label class=form-check-label for=f_embed_enabled><strong>Send a Discord embed</strong></label>")
                .append("</div>");
        h.append("</div>");

        h.append("<div class=col-md-9>")
                .append("<label class=form-label for=f_etitle d-flex align-items-center justify-content-between\">")
                .append("<span>Title</span>")
                .append("<button type=\"button\" class=\"btn btn-sm btn-outline-secondary alert-ai-polish\" data-alert-ai=\"embed_title\" data-target=\"f_etitle\" title=\"Polish or draft this title with AI\">")
                .append("<i class=\"bi bi-stars\"></i></button>")
                .append("</label>")
                .append("<input id=f_etitle class=form-control name=embed_title maxlength=256 value=\"")
                .append(Layout.escape(a.embedTitle())).append("\">")
                .append("</div>");
        h.append("<div class=col-md-3>")
                .append("<label class=form-label for=f_ecolor>Color")
                .append(Layout.infoIcon("Side bar colour for the embed."))
                .append("</label>")
                .append("<input id=f_ecolor type=color class=\"form-control form-control-color w-100\" name=embed_color_hex value=\"")
                .append(Layout.escape(a.embedColorHex())).append("\">")
                .append("</div>");
        h.append("<div class=col-12>")
                .append("<label class=form-label for=f_edesc d-flex align-items-center justify-content-between\">")
                .append("<span>Description</span>")
                .append("<button type=\"button\" class=\"btn btn-sm btn-outline-secondary alert-ai-polish\" data-alert-ai=\"embed_description\" data-target=\"f_edesc\" title=\"Polish or draft this description with AI\">")
                .append("<i class=\"bi bi-stars\"></i></button>")
                .append("</label>")
                .append("<textarea id=f_edesc name=embed_description class=\"form-control tpl\" rows=3>")
                .append(Layout.escape(a.embedDescription())).append("</textarea>")
                .append("</div>");
        h.append("<div class=col-md-6>")
                .append("<label class=form-label for=f_ethumb>Thumbnail URL")
                .append(Layout.infoIcon("Square image in the top-right corner of the embed. Must be http(s)://"))
                .append("</label>")
                .append("<input id=f_ethumb class=form-control name=embed_thumbnail placeholder=\"https://example.com/icon.png\" value=\"")
                .append(Layout.escape(a.embedThumbnail())).append("\">")
                .append("</div>");
        h.append("<div class=col-md-6>")
                .append("<label class=form-label for=f_eimage>Image URL")
                .append(Layout.infoIcon("Large image below the embed body. Must be http(s)://"))
                .append("</label>")
                .append("<input id=f_eimage class=form-control name=embed_image placeholder=\"https://example.com/banner.png\" value=\"")
                .append(Layout.escape(a.embedImage())).append("\">")
                .append("</div>");
        h.append("<div class=col-12>")
                .append("<label class=form-label for=f_efoot>Footer</label>")
                .append("<input id=f_efoot class=form-control name=embed_footer value=\"")
                .append(Layout.escape(a.embedFooter())).append("\">")
                .append("</div>");

        h.append("<div class=col-md-6>")
                .append("<label class=form-label for=f_eauthor>Author name")
                .append(Layout.infoIcon("Small bold text rendered above the title. Discord renders Author with an optional icon to its left."))
                .append("</label>")
                .append("<input id=f_eauthor class=form-control name=embed_author_name maxlength=256 value=\"")
                .append(Layout.escape(a.embedAuthorName())).append("\">")
                .append("</div>");
        h.append("<div class=col-md-6>")
                .append("<label class=form-label for=f_eauthoricon>Author icon URL")
                .append(Layout.infoIcon("Small round image shown next to the Author name. Must be http(s)://. Ignored if Author name is blank."))
                .append("</label>")
                .append("<input id=f_eauthoricon class=form-control name=embed_author_icon_url placeholder=\"https://example.com/avatar.png\" value=\"")
                .append(Layout.escape(a.embedAuthorIconUrl())).append("\">")
                .append("</div>");

        // Embed fields (dynamic list)
        h.append("<div class=col-12>");
        h.append("<label class=\"form-label\">Embed fields")
                .append(Layout.infoIcon("Up to 25 name/value boxes shown inside the embed. Inline fields sit side-by-side."))
                .append("</label>");
        h.append("<div id=fields-list>");
        if (a.embedFields().isEmpty()) {
            h.append(fieldRow("", "", false));
        } else {
            for (AlertEmbedField f : a.embedFields()) {
                h.append(fieldRow(f.name(), f.value(), f.inline()));
            }
        }
        h.append("</div>");
        h.append("<button type=button class=\"btn btn-sm btn-outline-primary\" id=add-field>")
                .append("<i class=\"bi bi-plus-lg me-1\"></i>Add field</button>");
        h.append("</div>");

        // Commands section
        h.append("<div class=col-12><hr><h2 class=\"h5 mt-2\">Commands</h2>");
        h.append("<p class=\"text-secondary small mb-3\">One command per line. ")
                .append("Leading slashes are stripped. Same <code>{variables}</code> and PAPI placeholders apply.</p></div>");

        h.append("<div class=col-md-6>")
                .append("<label class=form-label for=f_console>Console commands")
                .append(Layout.infoIcon("Run on the server thread as CONSOLE. Use for /tellraw, /give, /broadcast, etc."))
                .append("</label>")
                .append("<textarea id=f_console name=console_commands class=\"form-control tpl\" rows=4 placeholder=\"say {player} joined!&#10;broadcast Welcome {player_display}!\">")
                .append(Layout.escape(a.consoleCommands())).append("</textarea>")
                .append("</div>");

        h.append("<div class=col-md-6>")
                .append("<label class=form-label for=f_asplayer>As-player commands")
                .append(Layout.infoIcon("Run as the related player with their permissions. Skipped if the event has no online player."))
                .append("</label>")
                .append("<textarea id=f_asplayer name=asplayer_commands class=\"form-control tpl\" rows=4 placeholder=\"me waves hello&#10;tell @r hi from {player}\">")
                .append(Layout.escape(a.asPlayerCommands())).append("</textarea>")
                .append("</div>");

        h.append("<div class=col-md-6>")
                .append("<label class=form-label for=f_papi>PlaceholderAPI player UUID (optional)")
                .append(Layout.infoIcon("If set, PAPI placeholders resolve against this player when the event has no player attached (e.g. server_start)."))
                .append("</label>")
                .append("<input id=f_papi class=form-control name=papi_player_uuid placeholder=\"00000000-0000-0000-0000-000000000000\" value=\"")
                .append(Layout.escape(a.papiPlayerUuid())).append("\">")
                .append("</div>");

        // ============== Advanced (DSRV-style) ==============
        h.append("<div class=col-12><hr>");
        h.append("<details ").append(advancedOpen ? "open" : "")
                .append(" class=\"mb-2\"><summary class=\"h5 mt-2 mb-2\" style=\"cursor:pointer\">")
                .append("<i class=\"bi bi-tools me-1\"></i>Advanced (custom Bukkit event, SpEL conditions)</summary>");
        h.append("<div class=\"row g-3 pt-2\">");

        h.append("<div class=col-12>")
                .append("<label class=form-label for=f_trigger>Custom Bukkit event class")
                .append(Layout.infoIcon("Fully-qualified class name of a Bukkit event (e.g. org.bukkit.event.player.PlayerInteractEvent). When set, this overrides the built-in Event picker above and dispatch happens via dynamic Bukkit listener registration. Short names work for classes in the standard packages."))
                .append("</label>")
                .append("<input id=f_trigger class=form-control name=trigger_class placeholder=\"org.bukkit.event.player.PlayerInteractEvent\" value=\"")
                .append(Layout.escape(a.triggerClass())).append("\">");
        if (!a.triggerClass().isBlank()) {
            Class<?> resolved;
            try { resolved = EventClassResolver.resolve(a.triggerClass()); }
            catch (Throwable t) { resolved = null; }
            if (resolved == null) {
                h.append("<div class=\"form-text text-warning\"><i class=\"bi bi-exclamation-triangle me-1\"></i>")
                        .append("Class not currently loadable. The alert will be saved but won't fire until the class is available.</div>");
            } else {
                h.append("<div class=\"form-text text-success\"><i class=\"bi bi-check-circle me-1\"></i>")
                        .append("Resolves to <code>").append(Layout.escape(resolved.getName())).append("</code>.</div>");
            }
        }
        h.append("</div>");

        h.append("<div class=col-md-8>")
                .append("<label class=form-label for=f_conditions>Conditions (SpEL, one per line; all must be true)")
                .append(Layout.infoIcon("Each line is a Spring Expression Language expression evaluated against {event, server, user, player}. Lines starting with # are comments. Empty = no filter."))
                .append("</label>")
                .append("<textarea id=f_conditions name=conditions class=\"form-control tpl\" rows=3 placeholder=\"event.player.world.name == 'world'&#10;event.player.hasPermission('staff')\">")
                .append(Layout.escape(a.conditions())).append("</textarea>")
                .append("</div>");

        h.append("<div class=col-md-4 d-flex flex-column gap-2 justify-content-end\">");
        h.append("<div class=form-check form-switch>")
                .append("<input class=form-check-input type=checkbox id=f_expr name=expressions_enabled value=1 ")
                .append(a.expressionsEnabled() ? "checked" : "").append(">")
                .append("<label class=form-check-label for=f_expr>Evaluate <code>{...}</code> as SpEL")
                .append(Layout.infoIcon("On: every {expr} is parsed as Spring Expression Language (DSRV-compatible). Off: only literal {var} substitution from the built-in variable map."))
                .append("</label></div>");
        h.append("<div class=form-check form-switch>")
                .append("<input class=form-check-input type=checkbox id=f_async name=async_dispatch value=1 ")
                .append(a.asyncDispatch() ? "checked" : "").append(">")
                .append("<label class=form-check-label for=f_async>Async dispatch (informational)")
                .append(Layout.infoIcon("Mirrors DSRV's Async flag. Currently informational only - Discord posts already queue async via JDA, and console commands always run on the main thread to stay safe."))
                .append("</label></div>");
        h.append("</div>");

        h.append("</div></details></div>");

        h.append("<div class=col-12 d-flex gap-2 mt-2>");
        h.append("<button class=\"btn btn-primary\" type=submit><i class=\"bi bi-save me-1\"></i>")
                .append(isNew ? "Create alert" : "Save changes").append("</button>");
        h.append("<a class=\"btn btn-outline-secondary\" href=\"/dash/alerts\">Cancel</a>");
        if (!isNew) {
            h.append("<form method=post action=\"/dash/alerts/").append(a.id()).append("/test\" class=\"m-0 ms-auto\">")
                    .append("<button type=submit class=\"btn btn-outline-secondary\"><i class=\"bi bi-lightning-charge me-1\"></i>Test fire</button></form>");
        }
        h.append("</div>");

        h.append("</form>");

        h.append(formScript());

        // AI polish for alert message + embed title + description. Hover over the
        // <i bi-stars> button next to each field to use it. With an empty field,
        // the AI prompts for a short brief; with text already there, it rewrites
        // it while preserving {var} and %placeholder% tokens.
        h.append("<script>(function(){")
                .append("document.addEventListener('click',function(e){")
                .append("var btn=e.target.closest('.alert-ai-polish');if(!btn)return;")
                .append("e.preventDefault();")
                .append("var kind=btn.getAttribute('data-alert-ai')||'content';")
                .append("var target=document.getElementById(btn.getAttribute('data-target'));")
                .append("if(!target)return;")
                .append("var text=(target.value||'').trim();")
                .append("var brief='';")
                .append("if(!text){brief=window.prompt('Describe what this field should say:','');if(!brief)return;}")
                .append("var orig=btn.innerHTML;btn.innerHTML='<i class=\"bi bi-arrow-clockwise\"></i>';btn.disabled=true;")
                .append("var b=new URLSearchParams();b.set('kind',kind);b.set('text',text);b.set('brief',brief);")
                .append("fetch('/dash/ai/alert-polish',{method:'POST',credentials:'same-origin',")
                .append("headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b.toString()})")
                .append(".then(function(r){return r.json().catch(function(){return{ok:false,message:'HTTP '+r.status};});})")
                .append(".then(function(j){")
                .append("if(!j||!j.ok){window.alert('AI failed: '+((j&&j.message)||'unknown'));return;}")
                .append("target.value=j.polished||'';")
                .append("target.dispatchEvent(new Event('input',{bubbles:true}));")
                .append("})")
                .append(".catch(function(err){window.alert('Network error: '+(err.message||err));})")
                .append(".finally(function(){btn.innerHTML=orig;btn.disabled=false;});")
                .append("});")
                .append("})();</script>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private static String fieldRow(String name, String value, boolean inline) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"row g-2 mb-2 alert-field-row\">");
        s.append("<div class=col-md-4>")
                .append("<input class=form-control form-control-sm name=field_name placeholder=\"Field name\" maxlength=256 value=\"")
                .append(Layout.escape(name)).append("\"></div>");
        s.append("<div class=col-md-6>")
                .append("<input class=form-control form-control-sm name=field_value placeholder=\"Field value\" maxlength=1024 value=\"")
                .append(Layout.escape(value)).append("\"></div>");
        s.append("<div class=col-md-1 d-flex align-items-center>")
                .append("<div class=form-check><input class=form-check-input field-inline type=checkbox name=field_inline value=1 ")
                .append(inline ? "checked" : "").append("><label class=form-check-label small\">Inline</label></div>")
                .append("</div>");
        s.append("<div class=col-md-1 d-flex align-items-center>")
                .append("<button type=button class=\"btn btn-sm btn-outline-danger remove-field\" title=\"Remove\"><i class=\"bi bi-x-lg\"></i></button>")
                .append("</div>");
        s.append("</div>");
        return s.toString();
    }

    private static String eventSelect(String selected) {
        StringBuilder s = new StringBuilder();
        s.append("<select id=f_event class=form-select name=event required>");
        for (AlertEvent e : AlertEvent.values()) {
            s.append("<option value=\"").append(e.key()).append("\"")
                    .append(e.key().equalsIgnoreCase(selected) ? " selected" : "")
                    .append(" data-vars=\"").append(Layout.escape(String.join(",", e.variables()))).append("\"")
                    .append(" title=\"").append(Layout.escape(e.description())).append("\">")
                    .append(Layout.escape(e.label())).append("</option>");
        }
        s.append("</select>");
        return s.toString();
    }

    private static String formScript() {
        return "<script>(function(){"
                + "var sel=document.getElementById('f_event');"
                + "var hint=document.getElementById('vars-list');"
                + "function refresh(){"
                + "  if(!sel||!hint)return;"
                + "  var opt=sel.options[sel.selectedIndex];"
                + "  var raw=opt?opt.getAttribute('data-vars'):'';"
                + "  var list=raw?raw.split(',').filter(Boolean):[];"
                + "  if(list.length===0){hint.innerHTML='<em>This event carries no variables.</em>';return;}"
                + "  hint.innerHTML=list.map(function(v){return '<code>{'+v+'}</code>';}).join(', ');"
                + "}"
                + "if(sel){sel.addEventListener('change',refresh);refresh();}"
                + "var list=document.getElementById('fields-list');"
                + "var addBtn=document.getElementById('add-field');"
                + "if(addBtn&&list){"
                + "  addBtn.addEventListener('click',function(){"
                + "    var template=list.querySelector('.alert-field-row');"
                + "    var fresh;"
                + "    if(template){fresh=template.cloneNode(true);"
                + "      fresh.querySelectorAll('input').forEach(function(i){"
                + "        if(i.type==='checkbox'){i.checked=false;}else{i.value='';}"
                + "      });"
                + "    }else{"
                + "      var div=document.createElement('div');"
                + "      div.innerHTML="
                + "        '<div class=\"row g-2 mb-2 alert-field-row\">"
                + "         <div class=col-md-4><input class=\"form-control form-control-sm\" name=field_name placeholder=\"Field name\" maxlength=256></div>"
                + "         <div class=col-md-6><input class=\"form-control form-control-sm\" name=field_value placeholder=\"Field value\" maxlength=1024></div>"
                + "         <div class=col-md-1 d-flex align-items-center><div class=form-check><input class=\"form-check-input field-inline\" type=checkbox name=field_inline value=1><label class=\"form-check-label small\">Inline</label></div></div>"
                + "         <div class=col-md-1 d-flex align-items-center><button type=button class=\"btn btn-sm btn-outline-danger remove-field\" title=Remove><i class=\"bi bi-x-lg\"></i></button></div>"
                + "         </div>';"
                + "      fresh=div.firstElementChild;"
                + "    }"
                + "    list.appendChild(fresh);"
                + "  });"
                + "  list.addEventListener('click',function(e){"
                + "    var btn=e.target.closest('.remove-field');"
                + "    if(!btn)return;"
                + "    var row=btn.closest('.alert-field-row');"
                + "    if(row&&list.querySelectorAll('.alert-field-row').length>1){row.remove();}"
                + "    else if(row){row.querySelectorAll('input').forEach(function(i){"
                + "      if(i.type==='checkbox'){i.checked=false;}else{i.value='';}"
                + "    });}"
                + "  });"
                + "}"
                + "})();</script>";
    }

    /* =========================== Save / delete / toggle / test =========================== */

    public void save(Context ctx) throws Exception {
        long id = 0;
        String idParam = ctx.pathParam("id");
        if (idParam != null && !idParam.isBlank()) {
            try { id = Long.parseLong(idParam); }
            catch (NumberFormatException nfe) { ctx.redirect("/dash/alerts?err=" + url("Invalid id.")); return; }
        }

        Alert built = fromForm(ctx, id);
        if (built.event() == null || built.event().isBlank()
                || AlertEvent.fromKey(built.event()) == null) {
            ctx.redirect("/dash/alerts?err=" + url("Pick a valid event.")); return;
        }
        if (built.name().isBlank()) {
            ctx.redirect("/dash/alerts?err=" + url("Name is required."));
            return;
        }

        if (id == 0) {
            long newId = service.dao().create(built);
            manager.reload();
            ctx.redirect("/dash/alerts/" + newId + "/edit?msg=" + url("Alert created."));
        } else {
            service.dao().update(built);
            manager.reload();
            ctx.redirect("/dash/alerts/" + id + "/edit?msg=" + url("Alert saved."));
        }
    }

    public void delete(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        service.dao().delete(id);
        manager.reload();
        ctx.redirect("/dash/alerts?msg=" + url("Alert deleted."));
    }

    public void toggle(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        var opt = service.dao().find(id);
        if (opt.isEmpty()) {
            ctx.redirect("/dash/alerts?err=" + url("Alert not found.")); return;
        }
        service.dao().setEnabled(id, !opt.get().enabled());
        manager.reload();
        ctx.redirect("/dash/alerts");
    }

    public void test(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        var opt = service.dao().find(id);
        if (opt.isEmpty()) {
            ctx.redirect("/dash/alerts?err=" + url("Alert not found.")); return;
        }
        Alert a = opt.get();
        AlertEvent e = a.parsedEvent();
        AlertContext sample = new AlertContext();
        if (e != null) {
            for (String v : e.variables()) sample.set(v, sampleFor(v));
        }
        service.testFire(a, sample);
        String back = ctx.header("Referer");
        if (back == null || back.isBlank()) back = "/dash/alerts";
        String sep = back.contains("?") ? "&" : "?";
        ctx.redirect(back + sep + "msg=" + url("Test fired alert #" + a.id() + "."));
    }

    /* =========================== Importer =========================== */

    public void importForm(Context ctx) throws Exception {
        // Optional one-shot prefill from DiscordSRV's alerts.yml on disk.
        // ?source=dsrv reads the file server-side and seeds the textarea.
        String source = ctx.queryParam("source");
        String prefill = "";
        String loadNote = "";
        if ("dsrv".equalsIgnoreCase(source)) {
            var read = discordSrv == null ? java.util.Optional.<String>empty() : discordSrv.readAlertsFile();
            if (read.isPresent()) {
                prefill = read.get();
                var path = discordSrv == null ? java.util.Optional.<java.nio.file.Path>empty() : discordSrv.alertsFile();
                loadNote = "Loaded "
                        + (path.isPresent() ? path.get().toAbsolutePath().toString() : "plugins/DiscordSRV/alerts.yml")
                        + " - review and click <strong>Parse and import</strong>.";
            } else {
                loadNote = "Could not read DiscordSRV alerts.yml. Is DiscordSRV installed and does "
                        + "<code>plugins/DiscordSRV/alerts.yml</code> exist? You can still paste the YAML manually below.";
            }
        }

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Import DSRV alerts · Warden", "alerts", ctx));
        h.append("<div class=\"d-flex justify-content-between align-items-center mb-3\">");
        h.append("<div><h1 class=\"h3 mb-1\">Import from DiscordSRV</h1>")
                .append("<p class=\"text-secondary mb-0\">Pull <code>plugins/DiscordSRV/alerts.yml</code> directly, ")
                .append("or paste a single alert / list of alerts below. Imported alerts are created with ")
                .append("<strong>Expressions enabled</strong> so DSRV's <code>{event.player.name}</code> ")
                .append("templates and SpEL <code>Conditions</code> work as-is.</p></div>");
        h.append("<a href=\"/dash/alerts\" class=\"btn btn-outline-secondary\"><i class=\"bi bi-arrow-left me-1\"></i>Back to list</a>");
        h.append("</div>");

        String err = ctx.queryParam("err");
        String msg = ctx.queryParam("msg");
        if (err != null && !err.isBlank()) {
            h.append("<div class=\"alert alert-danger\"><pre class=\"mb-0 small\">")
                    .append(Layout.escape(err)).append("</pre></div>");
        }
        if (msg != null && !msg.isBlank()) {
            h.append("<div class=\"alert alert-success\">").append(Layout.escape(msg)).append("</div>");
        }

        // "Load from DiscordSRV" affordance, surfaced regardless of whether DSRV
        // is currently detected so the operator sees what's possible. We disable
        // the button (with a tooltip) when the file isn't reachable.
        boolean dsrvPresent = discordSrv != null && discordSrv.isPresent();
        java.util.Optional<java.nio.file.Path> dsrvPath =
                discordSrv == null ? java.util.Optional.empty() : discordSrv.alertsFile();
        boolean fileExists = dsrvPath.isPresent()
                && java.nio.file.Files.isRegularFile(dsrvPath.get());
        h.append("<div class=\"card mb-3\"><div class=\"card-body d-flex align-items-center gap-3 flex-wrap\">");
        h.append("<div class=\"flex-grow-1\">");
        h.append("<div class=\"fw-semibold\"><i class=\"bi bi-cloud-arrow-down me-1\"></i>Load from DiscordSRV on disk</div>");
        if (fileExists) {
            h.append("<div class=\"small text-secondary\">Reads <code>")
                    .append(Layout.escape(dsrvPath.get().toAbsolutePath().toString()))
                    .append("</code> into the textarea below. Nothing is saved until you click Parse and import.</div>");
        } else if (dsrvPresent) {
            h.append("<div class=\"small text-secondary\">DiscordSRV is installed but no <code>alerts.yml</code> file was found "
                    + "(DSRV creates it on first use of the alert command). You can paste your YAML manually below.</div>");
        } else {
            h.append("<div class=\"small text-secondary\">DiscordSRV doesn't appear to be installed on this server. "
                    + "You can still paste a copy of someone else's <code>alerts.yml</code> below.</div>");
        }
        h.append("</div>");
        if (fileExists) {
            h.append("<a class=\"btn btn-outline-primary\" href=\"/dash/alerts/import?source=dsrv\">")
                    .append("<i class=\"bi bi-file-earmark-arrow-down me-1\"></i>Load alerts.yml</a>");
        } else {
            h.append("<button class=\"btn btn-outline-secondary\" type=\"button\" disabled ")
                    .append("title=\"alerts.yml not found on disk\">")
                    .append("<i class=\"bi bi-file-earmark-x me-1\"></i>alerts.yml not found</button>");
        }
        h.append("</div></div>");

        if (!loadNote.isBlank()) {
            h.append("<div class=\"alert alert-info\">").append(loadNote).append("</div>");
        }

        h.append("<form method=post action=\"/dash/alerts/import\" class=\"card\"><div class=\"card-body\">");
        h.append("<div class=\"mb-3\"><label for=\"f_yaml\" class=\"form-label\">DSRV YAML</label>");
        h.append("<textarea id=\"f_yaml\" name=\"yaml\" class=\"form-control\" rows=\"22\" required ")
                .append("placeholder=\"Alerts:&#10;- Trigger: org.bukkit.event.player.PlayerJoinEvent&#10;  Conditions:&#10;  - event.player.hasPermission('staff')&#10;  Embed:&#10;    Color: '#00ff00'&#10;    Title:&#10;      Text: 'Staff joined'&#10;    Description: '{event.player.name} just joined.'\">")
                .append(Layout.escape(prefill))
                .append("</textarea>");
        h.append("<div class=\"form-text\">After import, edit each alert to confirm the Discord channel (DSRV uses ")
                .append("channel names; Warden resolves them via DiscordSRV's <code>Channels:</code> map when possible).</div>");
        h.append("</div>");
        h.append("<div class=\"d-flex gap-2\">");
        h.append("<button class=\"btn btn-primary\" type=\"submit\"><i class=\"bi bi-download me-1\"></i>Parse and import</button>");
        h.append("<a class=\"btn btn-outline-secondary\" href=\"/dash/alerts\">Cancel</a>");
        h.append("</div>");
        h.append("</div></form>");
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void importSave(Context ctx) throws Exception {
        String yaml = ctx.formParam("yaml");
        if (yaml == null || yaml.isBlank()) {
            ctx.redirect("/dash/alerts/import?err=" + url("Paste some YAML first.")); return;
        }
        DsrvAlertImporter.Result result;
        try {
            DsrvAlertImporter.ChannelResolver resolver = discordSrv == null
                    ? name -> java.util.Optional.empty()
                    : discordSrv::channelIdByDsrvName;
            result = DsrvAlertImporter.parse(yaml, resolver);
        } catch (Exception e) {
            ctx.redirect("/dash/alerts/import?err=" + url(e.getMessage()));
            return;
        }
        if (result.alerts().isEmpty()) {
            String warnings = String.join("\n", result.warnings());
            ctx.redirect("/dash/alerts/import?err=" + url("No alerts imported.\n" + warnings));
            return;
        }
        int saved = 0;
        for (Alert a : result.alerts()) {
            try {
                service.dao().create(a);
                saved++;
            } catch (Exception e) {
                // log + skip
            }
        }
        manager.reload();

        // Redirect to the dedicated post-import page so the next-steps banner
        // (clear DSRV alerts.yml + discord reload) is impossible to miss.
        StringBuilder redirect = new StringBuilder("/dash/alerts/import/done?count=").append(saved);
        if (!result.warnings().isEmpty()) {
            redirect.append("&warn=").append(url(String.join("\n", result.warnings())));
        }
        ctx.redirect(redirect.toString());
    }

    /**
     * Post-import landing page. Makes the duplicate-fire risk obvious and
     * offers a one-click "clear DSRV alerts.yml" action so the operator
     * doesn't have to ssh in and edit a YAML file by hand.
     */
    public void importDone(Context ctx) throws Exception {
        int count = 0;
        try { count = Integer.parseInt(ctx.queryParam("count")); } catch (Exception ignored) {}
        String warn = ctx.queryParam("warn");
        String msg = ctx.queryParam("msg");
        String err = ctx.queryParam("err");

        boolean dsrvPresent = discordSrv != null && discordSrv.isPresent();
        java.util.Optional<java.nio.file.Path> dsrvPath =
                discordSrv == null ? java.util.Optional.empty() : discordSrv.alertsFile();
        boolean fileExists = dsrvPath.isPresent()
                && java.nio.file.Files.isRegularFile(dsrvPath.get());
        boolean alreadyCleared = fileExists && looksAlreadyCleared(dsrvPath.get());

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Import complete · Warden", "alerts", ctx));
        h.append("<div class=\"d-flex justify-content-between align-items-center mb-3\">");
        h.append("<div><h1 class=\"h3 mb-1\"><i class=\"bi bi-check2-circle text-success me-2\"></i>Import complete</h1>")
                .append("<p class=\"text-secondary mb-0\">Imported <strong>").append(count)
                .append("</strong> alert(s) from DiscordSRV YAML.</p></div>");
        h.append("<div class=\"d-flex gap-2\">")
                .append("<a href=\"/dash/alerts\" class=\"btn btn-outline-secondary\"><i class=\"bi bi-list me-1\"></i>View alerts</a>")
                .append("<a href=\"/dash/alerts/import\" class=\"btn btn-outline-primary\"><i class=\"bi bi-arrow-clockwise me-1\"></i>Import more</a>")
                .append("</div>");
        h.append("</div>");

        if (msg != null && !msg.isBlank()) {
            h.append("<div class=\"alert alert-success\">").append(Layout.escape(msg)).append("</div>");
        }
        if (err != null && !err.isBlank()) {
            h.append("<div class=\"alert alert-danger\">").append(Layout.escape(err)).append("</div>");
        }
        if (warn != null && !warn.isBlank()) {
            h.append("<div class=\"alert alert-warning\"><strong>Import warnings:</strong>")
                    .append("<pre class=\"mb-0 small mt-2\">").append(Layout.escape(warn)).append("</pre></div>");
        }

        // The big duplicate-fire warning. This is the whole point of this page.
        h.append("<div class=\"card border-warning mb-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h5 mb-2 text-warning\"><i class=\"bi bi-exclamation-triangle-fill me-2\"></i>")
                .append("Your alerts will fire twice until you clear DiscordSRV's alerts.yml</h2>");
        h.append("<p class=\"mb-2\">DiscordSRV still has the original alert definitions loaded in memory and on disk. ")
                .append("Until you clear them, every event will trigger <em>both</em> the DSRV alert and the new Warden alert ")
                .append("- so Discord gets the same message twice.</p>");
        h.append("<p class=\"mb-3\">Pick whichever option fits your workflow:</p>");

        h.append("<div class=\"row g-3\">");

        // Option A: one-click clear (only when we can actually see the file)
        h.append("<div class=\"col-md-6\"><div class=\"card h-100\"><div class=\"card-body\">");
        h.append("<h3 class=\"h6 mb-2\"><span class=\"badge text-bg-primary me-1\">A</span>Recommended: clear from here</h3>");
        if (fileExists && !alreadyCleared) {
            h.append("<p class=\"small mb-3\">Warden will:</p>");
            h.append("<ol class=\"small mb-3\">")
                    .append("<li>Rename <code>")
                    .append(Layout.escape(dsrvPath.get().getFileName().toString()))
                    .append("</code> to <code>alerts.yml.warden-backup-&lt;timestamp&gt;</code> so your original is recoverable.</li>")
                    .append("<li>Write a minimal <code>Alerts: []</code> placeholder in its place so DSRV stops firing the old alerts after its next reload.</li>")
                    .append("</ol>");
            h.append("<form method=post action=\"/dash/alerts/import/clear-dsrv\" ")
                    .append("data-confirm=\"Back up DiscordSRV's alerts.yml and replace it with an empty Alerts: [] file?\" ")
                    .append("data-confirm-kind=\"danger\" data-confirm-ok=\"Back up and clear\">");
            h.append("<button type=submit class=\"btn btn-warning w-100\">")
                    .append("<i class=\"bi bi-eraser-fill me-1\"></i>Back up and clear alerts.yml</button>");
            h.append("</form>");
            h.append("<div class=\"form-text small mt-2\">Then run <code>/discord reload</code> in the server console (or restart) so DSRV re-reads the now-empty file.</div>");
        } else if (alreadyCleared) {
            h.append("<p class=\"small mb-2 text-success\"><i class=\"bi bi-check2 me-1\"></i>The DiscordSRV alerts.yml already looks cleared (no alert entries detected).</p>");
            h.append("<p class=\"small mb-0\">If DSRV is still firing duplicates, run <code>/discord reload</code> in console or restart the server so it re-reads the file.</p>");
        } else if (dsrvPresent) {
            h.append("<p class=\"small mb-0\">DiscordSRV is installed but I can't see <code>alerts.yml</code> on disk. Use option B.</p>");
        } else {
            h.append("<p class=\"small mb-0\">DiscordSRV doesn't appear to be running on this server. If you're migrating from a different server, log into it and follow option B there.</p>");
        }
        h.append("</div></div></div>");

        // Option B: manual instructions
        h.append("<div class=\"col-md-6\"><div class=\"card h-100\"><div class=\"card-body\">");
        h.append("<h3 class=\"h6 mb-2\"><span class=\"badge text-bg-secondary me-1\">B</span>Manual: edit + reload</h3>");
        h.append("<ol class=\"small mb-2\">")
                .append("<li>Open <code>plugins/DiscordSRV/alerts.yml</code> in any text editor.</li>")
                .append("<li>Replace its contents with <code>Alerts: []</code> (or comment out the alerts you migrated).</li>")
                .append("<li>Run <code>/discord reload</code> in the server console - <em>or</em> restart the server entirely.</li>")
                .append("</ol>");
        h.append("<p class=\"small text-secondary mb-0\">Keep a copy of the original somewhere safe before editing.</p>");
        h.append("</div></div></div>");

        h.append("</div>"); // row

        h.append("</div></div>"); // warning card

        // After-action checklist
        h.append("<div class=\"card mb-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6 mb-2\"><i class=\"bi bi-list-check me-1\"></i>Suggested next steps</h2>");
        h.append("<ul class=\"mb-0\">");
        h.append("<li>Open each imported alert and pick its Discord channel from the dropdown ")
                .append("(DSRV alerts use channel names; Warden uses the IDs your bot can see).</li>");
        h.append("<li>Hit <strong>Test fire</strong> on a couple of alerts to make sure the embed renders the way you expect.</li>");
        h.append("<li>Disable any alerts you don't actually want before re-enabling DSRV-side duplicates.</li>");
        h.append("</ul>");
        h.append("</div></div>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void clearDsrvAlertsFile(Context ctx) throws Exception {
        if (discordSrv == null) {
            ctx.redirect("/dash/alerts/import/done?err=" + url("DiscordSRV bridge is unavailable.")); return;
        }
        var backup = discordSrv.backupAndClearAlertsFile();
        if (backup.isEmpty()) {
            ctx.redirect("/dash/alerts/import/done?err="
                    + url("Could not back up and clear plugins/DiscordSRV/alerts.yml. "
                            + "Check the server log for the underlying error - common causes are "
                            + "the file not existing, file-system permissions, or DSRV holding the file open."));
            return;
        }
        ctx.redirect("/dash/alerts/import/done?msg=" + url(
                "DiscordSRV alerts.yml cleared. Backup saved at "
                        + backup.get().toAbsolutePath()
                        + ". Now run /discord reload in console (or restart) so DSRV re-reads the empty file."));
    }

    /**
     * Best-effort check: does the DSRV alerts.yml file look like our cleared
     * placeholder (or otherwise have no alert entries)? Used to suppress the
     * "clear" CTA once it's been done.
     */
    private static boolean looksAlreadyCleared(java.nio.file.Path path) {
        try {
            String body = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            String stripped = body.replaceAll("(?m)^\\s*#.*$", "").trim();
            if (stripped.isEmpty()) return true;
            if (stripped.matches("(?s)Alerts:\\s*\\[\\s*\\]\\s*")) return true;
            if (stripped.matches("(?s)Alerts:\\s*")) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String sampleFor(String var) {
        return switch (var) {
            case "player", "player_display" -> "Steve";
            case "player_uuid" -> "8667ba71-b85a-4004-af54-457a9734eed7";
            case "world" -> "world";
            case "from_world" -> "world_nether";
            case "death_message" -> "Steve was slain by Zombie";
            case "killer" -> "Zombie";
            case "advancement" -> "Stone Age";
            case "message" -> "hello world";
            case "user", "user_tag" -> "ExampleUser";
            case "user_id" -> "123456789012345678";
            case "user_mention" -> "<@123456789012345678>";
            default -> "";
        };
    }

    private static Alert fromForm(Context ctx, long id) {
        List<String> names = ctx.formParams("field_name");
        List<String> values = ctx.formParams("field_value");
        List<String> inlines = ctx.formParams("field_inline");
        List<AlertEmbedField> fields = new ArrayList<>();
        int max = Math.min(25, Math.max(names.size(), values.size()));
        for (int i = 0; i < max; i++) {
            String n = i < names.size() ? names.get(i) : "";
            String v = i < values.size() ? values.get(i) : "";
            if ((n == null || n.isBlank()) && (v == null || v.isBlank())) continue;
            boolean inline = i < inlines.size();
            fields.add(new AlertEmbedField(n, v, inline));
        }
        return new Alert(
                id,
                str(ctx, "name"),
                "1".equals(ctx.formParam("enabled")),
                str(ctx, "event"),
                str(ctx, "channel_id"),
                str(ctx, "message_content"),
                "1".equals(ctx.formParam("embed_enabled")),
                str(ctx, "embed_title"),
                str(ctx, "embed_description"),
                emptyDefault(str(ctx, "embed_color_hex"), "#5865F2"),
                str(ctx, "embed_thumbnail"),
                str(ctx, "embed_image"),
                str(ctx, "embed_footer"),
                str(ctx, "embed_author_name"),
                str(ctx, "embed_author_icon_url"),
                fields,
                str(ctx, "console_commands"),
                str(ctx, "asplayer_commands"),
                str(ctx, "papi_player_uuid"),
                str(ctx, "trigger_class"),
                str(ctx, "conditions"),
                "1".equals(ctx.formParam("expressions_enabled")),
                "1".equals(ctx.formParam("async_dispatch")),
                0, 0
        );
    }

    /* =========================== Small helpers =========================== */

    private static String summariseActions(Alert a) {
        List<String> parts = new ArrayList<>();
        if (a.channelId() != null && !a.channelId().isBlank()
                && ((a.messageContent() != null && !a.messageContent().isBlank()) || a.embedEnabled())) {
            parts.add("<span class=\"badge text-bg-primary\">Discord</span>");
        }
        if (a.consoleCommands() != null && !a.consoleCommands().isBlank()) {
            parts.add("<span class=\"badge text-bg-secondary\">Console</span>");
        }
        if (a.asPlayerCommands() != null && !a.asPlayerCommands().isBlank()) {
            parts.add("<span class=\"badge text-bg-info\">As player</span>");
        }
        if (a.conditions() != null && !a.conditions().isBlank()) {
            parts.add("<span class=\"badge text-bg-warning\">Conditional</span>");
        }
        if (parts.isEmpty()) return "<span class=text-secondary>(no actions)</span>";
        return String.join(" ", parts);
    }

    private static String channelLabel(List<GuildLookup.Option> opts, String id) {
        for (GuildLookup.Option o : opts) if (o.id().equals(id)) return Layout.escape(o.name());
        return "<code>" + Layout.escape(id) + "</code>";
    }

    private static String str(Context ctx, String key) {
        String v = ctx.formParam(key);
        return v == null ? "" : v;
    }

    private static String emptyDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String url(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Tight, flush row-action group used by the alerts list. Forms get
     * {@code display:contents} so the buttons (not the &lt;form&gt; wrapper) act as
     * direct children of the group, which gives us the seamless joined look
     * that Bootstrap's btn-group can't manage when its children are wrapped.
     *
     * Default look: subtle neutral pill that hugs the row colour. On hover the
     * primary/danger intent only lights up the relevant face, so the row stays
     * calm at rest but each action's purpose is unmistakable when reached for.
     */
    private static final String ACTIONS_CSS = """
            <style>
            .alert-actions{display:inline-flex;align-items:stretch;
              border:1px solid var(--bs-border-color);border-radius:.5rem;overflow:hidden;
              background:var(--bs-body-bg)}
            .alert-actions form{display:contents;margin:0}
            .alert-actions .aa-btn{
              display:inline-flex;align-items:center;gap:.35rem;
              padding:.32rem .7rem;font-size:.82rem;line-height:1;font-weight:500;
              color:var(--bs-body-color);background:transparent;border:0;
              border-left:1px solid var(--bs-border-color);
              text-decoration:none;cursor:pointer;
              transition:background .12s,color .12s}
            .alert-actions > :first-child .aa-btn,
            .alert-actions > .aa-btn:first-child{border-left:0}
            .alert-actions .aa-btn:hover{background:var(--bs-tertiary-bg)}
            .alert-actions .aa-btn:focus-visible{
              outline:2px solid var(--bs-primary);outline-offset:-2px}
            .alert-actions .aa-btn i{font-size:.95em;opacity:.8}
            .alert-actions .aa-btn:hover i{opacity:1}
            .alert-actions .aa-edit:hover{color:var(--bs-primary);
              background:rgba(13,110,253,.10)}
            .alert-actions .aa-test:hover{color:#b58a00;
              background:rgba(255,193,7,.14)}
            [data-bs-theme=dark] .alert-actions .aa-test:hover{color:#ffc94a}
            .alert-actions .aa-delete:hover{color:var(--bs-danger);
              background:rgba(220,53,69,.12)}
            /* Tighten the toggle button beside the actions so they read as one block */
            table .alert-actions{box-shadow:0 1px 0 rgba(0,0,0,.02)}
            @media (max-width: 575.98px){
              .alert-actions .aa-label{display:none}
              .alert-actions .aa-btn{padding:.4rem .6rem}
            }
            </style>
            """;
}
