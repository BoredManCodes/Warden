package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.discord.DiscordService;
import io.warden.reactionroles.ReactionRoleGroup;
import io.warden.reactionroles.ReactionRoleOption;
import io.warden.reactionroles.ReactionRoleService;

public final class DashReactionRolesHandlers {

    private final ReactionRoleService service;
    private final DiscordService discord;
    private final GuildLookup lookup;

    public DashReactionRolesHandlers(ReactionRoleService service, DiscordService discord,
                                     GuildLookup lookup) {
        this.service = service;
        this.discord = discord;
        this.lookup = lookup;
    }

    /** Tooltips for the abstract reaction-role mode values. */
    private static final java.util.Map<String, String> MODE_TIPS = new java.util.LinkedHashMap<>();
    static {
        MODE_TIPS.put("normal",   "Members can pick any number of these roles, in any order.");
        MODE_TIPS.put("unique",   "Members can only hold one role from this group at a time. Picking a new one removes the old.");
        MODE_TIPS.put("verify",   "One-shot opt-in: clicking grants the role; the button does nothing on re-click.");
        MODE_TIPS.put("reversed", "Clicking removes the role instead of granting it. Useful for opt-out flows.");
        MODE_TIPS.put("limit",    "Members can pick up to N roles from this group (see 'Max select' below).");
        MODE_TIPS.put("binding",  "Once a member picks a role here, the choice is permanent. They can't switch or unpick.");
    }

    public void list(Context ctx) throws Exception {
        var groups = service.dao().listAll();
        var roleOpts = lookup.roles();
        var channelOpts = lookup.textChannels();
        var emojiOpts = lookup.customEmojis();

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Reaction roles · Warden", "reaction-roles", ctx));
        h.append("<h1 class=\"h3 mb-3\">Reaction roles</h1>");
        h.append("<p class=\"text-secondary\">Self-assignable role panels. Posted as Discord messages with buttons.</p>");

        h.append("<form method=post action=\"/dash/reaction-roles/new\" class=\"row g-2 mb-4 align-items-end\">");
        h.append("<div class=col-md-3><label class=form-label>Name</label><input class=form-control name=name required></div>");
        h.append("<div class=col-md-3><label class=form-label>Channel</label>")
                .append(GuildLookup.selectInline("channel_id", channelOpts, null, "form-select", "(select channel)"))
                .append("</div>");
        h.append("<div class=col-md-2><label class=form-label>Mode")
                .append(Layout.infoIcon("How members interact with the panel. Hover an option to see what it does."))
                .append("</label>")
                .append(modeSelect("mode", null))
                .append("</div>");
        h.append("<div class=col-md-2><label class=form-label>Title</label><input class=form-control name=title></div>");
        h.append("<div class=col-md-2><button class=\"btn btn-primary w-100\">Create group</button></div>");
        h.append("</form>");

        for (ReactionRoleGroup g : groups) {
            h.append("<div class=\"card mb-3\"><div class=\"card-body\">");
            h.append("<h2 class=\"h5\">").append(Layout.escape(g.name())).append(" <span class=\"text-secondary fs-6\">#")
                    .append(g.id()).append(" mode=").append(g.mode()).append("</span></h2>");
            h.append("<form method=post action=\"/dash/reaction-roles/").append(g.id()).append("\" class=\"row g-2 mb-3\">");
            h.append("<div class=col-md-2><label class=form-label>Name</label><input class=form-control name=name value=\"")
                    .append(Layout.escape(g.name())).append("\"></div>");
            h.append("<div class=col-md-2><label class=form-label>Mode")
                    .append(Layout.infoIcon("How members interact with this panel. Hover an option to see what it does."))
                    .append("</label>")
                    .append(modeSelect("mode", g.mode()))
                    .append("</div>");
            h.append("<div class=col-md-2><label class=form-label>Title</label><input class=form-control name=title value=\"")
                    .append(Layout.escape(g.title())).append("\"></div>");
            h.append("<div class=col-md-2><label class=form-label>Color</label><input class=form-control type=color name=color_hex value=\"")
                    .append(Layout.escape(g.colorHex())).append("\"></div>");
            h.append("<div class=col-md-2><label class=form-label>Max select (limit mode)")
                    .append(Layout.infoIcon("Only used in 'limit' mode: maximum roles a member can pick at once."))
                    .append("</label>")
                    .append("<input class=form-control type=number name=max_selections value=\"").append(g.maxSelections()).append("\"></div>");
            h.append("<div class=col-md-2><label class=form-label>Required role")
                    .append(Layout.infoIcon("If set, only members with this role can interact with the panel."))
                    .append("</label>")
                    .append(GuildLookup.selectInline("required_role",
                            GuildLookup.withDefaults(roleOpts, g.requiredRole()),
                            g.requiredRole(), "form-select", "(none)"))
                    .append("</div>");
            h.append("<div class=col-12><label class=form-label>Description</label><textarea class=form-control name=description rows=2>")
                    .append(Layout.escape(g.description())).append("</textarea></div>");
            h.append("<div class=col-12><button class=\"btn btn-sm btn-outline-primary\">Save group</button></div>");
            h.append("</form>");

            h.append("<div class=\"rr-options\">");
            h.append("<div class=\"row g-2 rr-options-head text-secondary small fw-semibold mb-1\">")
                    .append("<div class=\"col-md-3\">Label</div>")
                    .append("<div class=\"col-md-3\">Role</div>")
                    .append("<div class=\"col-md-2\">Emoji</div>")
                    .append("<div class=\"col-md-3\">Description</div>")
                    .append("<div class=\"col-md-1\"></div>")
                    .append("</div>");
            for (ReactionRoleOption opt : g.options()) {
                h.append("<div class=\"row g-2 align-items-center rr-options-row\">")
                        .append("<div class=\"col-md-3\">").append(Layout.escape(opt.label())).append("</div>")
                        .append("<div class=\"col-md-3\">").append(roleLabel(roleOpts, opt.roleId())).append("</div>")
                        .append("<div class=\"col-md-2\">").append(emojiCellHtml(opt.emoji())).append("</div>")
                        .append("<div class=\"col-md-3\">").append(Layout.escape(opt.description())).append("</div>")
                        .append("<div class=\"col-md-1 d-flex justify-content-md-end\"><form method=post action=\"/dash/reaction-roles/").append(g.id())
                        .append("/options/").append(opt.id()).append("/delete\" class=m-0>")
                        .append("<button class=\"btn btn-sm btn-outline-danger\">Remove</button></form></div>")
                        .append("</div>");
            }
            h.append("<form method=post action=\"/dash/reaction-roles/").append(g.id()).append("/options\" class=\"row g-2 align-items-start rr-options-add\">");
            h.append("<div class=col-md-3><input class=form-control name=label placeholder=\"Label\" required></div>");
            h.append("<div class=col-md-3>")
                    .append(GuildLookup.selectInline("role_id", roleOpts, null, "form-select", "(select role)"))
                    .append("</div>");
            h.append("<div class=col-md-2>").append(emojiInputWithPicker("emoji", emojiOpts)).append("</div>");
            h.append("<div class=col-md-3><input class=form-control name=description placeholder=\"Description\"></div>");
            h.append("<div class=col-md-1><button class=\"btn btn-primary w-100\">Add</button></div>");
            h.append("</form>");
            h.append("</div>");

            h.append("<div class=\"mt-3 d-flex gap-2\">");
            h.append("<form method=post action=\"/dash/reaction-roles/").append(g.id()).append("/post\" class=m-0>")
                    .append("<button class=\"btn btn-sm btn-primary\">Post / refresh in Discord</button></form>");
            if (g.messageId() != null && !g.messageId().isBlank()
                    && g.channelId() != null && !g.channelId().isBlank()) {
                String msgUrl = "https://discord.com/channels/" + Layout.escape(lookup.guildId())
                        + "/" + Layout.escape(g.channelId()) + "/" + Layout.escape(g.messageId());
                h.append("<a class=\"text-secondary small align-self-center\" target=_blank rel=noopener href=\"")
                        .append(msgUrl).append("\">Posted in Discord <i class=\"bi bi-box-arrow-up-right\"></i></a>");
            }
            h.append("<form method=post action=\"/dash/reaction-roles/").append(g.id()).append("/delete\" class=\"m-0 ms-auto\" data-confirm=\"Delete this reaction-role group?\" data-confirm-kind=\"danger\">")
                    .append("<button class=\"btn btn-sm btn-outline-danger\">Delete group</button></form>");
            h.append("</div>");
            h.append("</div></div>");
        }
        h.append(PICKER_ASSETS);
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void createGroup(Context ctx) throws Exception {
        service.dao().createGroup(
                str(ctx, "name"), str(ctx, "channel_id"),
                str(ctx, "mode"), "buttons",
                str(ctx, "title"), str(ctx, "description"),
                "#5865F2", 0, "");
        ctx.redirect("/dash/reaction-roles");
    }

    public void updateGroup(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        service.dao().updateGroup(id,
                str(ctx, "name"), str(ctx, "mode"),
                str(ctx, "title"), str(ctx, "description"),
                str(ctx, "color_hex"),
                intOr(ctx, "max_selections", 0),
                str(ctx, "required_role"));
        ctx.redirect("/dash/reaction-roles");
    }

    public void deleteGroup(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        service.dao().deleteGroup(id);
        ctx.redirect("/dash/reaction-roles");
    }

    public void addOption(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String emoji = str(ctx, "emoji");
        String emojiError = emojiValidationError(emoji);
        if (emojiError != null) {
            ctx.redirect(Layout.flashRedirect("/dash/reaction-roles", "error", emojiError));
            return;
        }
        var g = service.dao().findById(id).orElse(null);
        int nextOrder = g == null ? 0 : g.options().size();
        service.dao().addOption(id, str(ctx, "role_id"), str(ctx, "label"),
                emoji, str(ctx, "description"), nextOrder);
        ctx.redirect("/dash/reaction-roles");
    }

    public void deleteOption(Context ctx) throws Exception {
        service.dao().deleteOption(Long.parseLong(ctx.pathParam("optId")));
        ctx.redirect("/dash/reaction-roles");
    }

    public void post(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        if (discord != null && discord.jda() != null) {
            service.postOrUpdate(discord.jda(), id);
        }
        ctx.redirect("/dash/reaction-roles");
    }

    private static String str(Context ctx, String key) {
        String v = ctx.formParam(key);
        return v == null ? "" : v;
    }

    private static int intOr(Context ctx, String key, int fallback) {
        try { return Integer.parseInt(ctx.formParam(key)); }
        catch (Exception e) { return fallback; }
    }

    private static String modeSelect(String name, String selected) {
        StringBuilder s = new StringBuilder();
        s.append("<select class=form-select name=").append(name).append(">");
        for (var e : MODE_TIPS.entrySet()) {
            s.append("<option value=\"").append(e.getKey()).append("\"")
                    .append(e.getKey().equalsIgnoreCase(selected) ? " selected" : "")
                    .append(" title=\"").append(Layout.escape(e.getValue())).append("\">")
                    .append(e.getKey()).append("</option>");
        }
        s.append("</select>");
        return s.toString();
    }

    /**
     * Returns null if the emoji string is valid (or empty), otherwise a
     * user-facing error message. Accepts empty, custom-emoji mention form
     * ({@code <:name:id>} / {@code <a:name:id>}), or a string with no ASCII
     * letters/digits (i.e. a unicode emoji). Rejects plain text like "test"
     * so the bot doesn't fail silently when Discord refuses the payload.
     */
    static String emojiValidationError(String s) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim();
        if (trimmed.matches("<a?:[A-Za-z0-9_~]{1,64}:\\d{15,25}>")) return null;
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')
                    || (cp >= '0' && cp <= '9')) {
                return "That doesn't look like a real emoji. Paste a unicode emoji like ❤️, or use the picker to insert one of this server's custom emojis.";
            }
        }
        return null;
    }

    /** Render an emoji cell: shows the custom-emoji image inline, falls back to plain text. */
    private static String emojiCellHtml(String emoji) {
        if (emoji == null || emoji.isBlank()) return "<span class=\"text-secondary\">-</span>";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^<(a?):([A-Za-z0-9_~]{1,64}):(\\d{15,25})>$").matcher(emoji.trim());
        if (m.matches()) {
            String ext = "a".equals(m.group(1)) ? "gif" : "png";
            String name = m.group(2);
            String id = m.group(3);
            String url = "https://cdn.discordapp.com/emojis/" + id + "." + ext + "?size=32&quality=lossless";
            return "<img class=\"rr-emoji-img\" src=\"" + Layout.escape(url)
                    + "\" alt=\":" + Layout.escape(name) + ":\" title=\":"
                    + Layout.escape(name) + ":\">";
        }
        return Layout.escape(emoji);
    }

    /**
     * Emoji input grouped with a picker button. When the page has any server
     * custom emojis the button opens a small popover listing them; clicking
     * one inserts its mention form into the input.
     */
    private static String emojiInputWithPicker(String name, java.util.List<GuildLookup.EmojiOption> emojiOpts) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"input-group rr-emoji-input\">");
        s.append("<input class=form-control name=").append(name).append(" placeholder=\"Emoji\" autocomplete=\"off\">");
        if (emojiOpts == null || emojiOpts.isEmpty()) {
            s.append("<button class=\"btn btn-outline-secondary\" type=button disabled")
                    .append(" title=\"No server emojis available. Connect the bot to your guild to populate this picker.\">")
                    .append("<i class=\"bi bi-emoji-smile\"></i></button>");
        } else {
            s.append("<button class=\"btn btn-outline-secondary rr-emoji-picker-btn\" type=button")
                    .append(" aria-label=\"Pick a server emoji\" title=\"Pick a server emoji\">")
                    .append("<i class=\"bi bi-emoji-smile\"></i></button>");
            s.append("<div class=\"rr-emoji-popover\" hidden>");
            s.append("<div class=\"rr-emoji-popover-head\">")
                    .append("<input type=\"search\" class=\"form-control form-control-sm rr-emoji-filter\" placeholder=\"Search server emojis...\" autocomplete=\"off\">")
                    .append("</div>");
            s.append("<div class=\"rr-emoji-grid\">");
            for (GuildLookup.EmojiOption e : emojiOpts) {
                s.append("<button type=button class=\"rr-emoji-btn\" data-mention=\"")
                        .append(Layout.escape(e.mention())).append("\" data-name=\"")
                        .append(Layout.escape(e.name())).append("\" title=\":")
                        .append(Layout.escape(e.name())).append(":\">")
                        .append("<img loading=lazy src=\"").append(Layout.escape(e.imageUrl()))
                        .append("\" alt=\":").append(Layout.escape(e.name())).append(":\"></button>");
            }
            s.append("</div>");
            s.append("<div class=\"rr-emoji-popover-foot text-secondary\">")
                    .append("Tip: paste a unicode emoji like ❤️ straight into the field.")
                    .append("</div>");
            s.append("</div>");
        }
        s.append("</div>");
        return s.toString();
    }

    /** CSS + JS for the emoji picker popover. Appended once after the page body. */
    private static final String PICKER_ASSETS = ""
            + "<style>"
            + ".rr-options-head{padding:0 .15rem}"
            + ".rr-options-row{padding:.4rem .15rem;border-top:1px solid var(--bs-border-color)}"
            + ".rr-options-row:first-of-type{border-top:none}"
            + ".rr-options-add{padding-top:.6rem;border-top:1px solid var(--bs-border-color);margin-top:.25rem}"
            + ".rr-emoji-img{width:1.4rem;height:1.4rem;object-fit:contain;vertical-align:middle}"
            + ".rr-emoji-input{position:relative}"
            + ".rr-emoji-popover{position:absolute;top:calc(100% + .25rem);right:0;z-index:1060;"
            + "width:min(20rem,calc(100vw - 2rem));background:var(--bs-body-bg);"
            + "border:1px solid var(--bs-border-color);border-radius:.5rem;"
            + "box-shadow:0 8px 28px rgba(0,0,0,.18),0 2px 6px rgba(0,0,0,.10);"
            + "padding:.5rem;display:flex;flex-direction:column;gap:.4rem}"
            + ".rr-emoji-popover[hidden]{display:none}"
            + ".rr-emoji-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(2rem,1fr));"
            + "gap:.2rem;max-height:14rem;overflow-y:auto;padding:.1rem}"
            + ".rr-emoji-btn{display:flex;align-items:center;justify-content:center;"
            + "width:2rem;height:2rem;padding:0;border:1px solid transparent;border-radius:.35rem;"
            + "background:transparent;cursor:pointer;transition:background .12s,border-color .12s}"
            + ".rr-emoji-btn:hover{background:rgba(127,127,127,.12);border-color:var(--bs-border-color)}"
            + ".rr-emoji-btn img{max-width:1.5rem;max-height:1.5rem;object-fit:contain;display:block}"
            + ".rr-emoji-popover-foot{font-size:.78rem;line-height:1.2}"
            + ".rr-emoji-grid .rr-emoji-btn.is-hidden{display:none}"
            + "</style>"
            + "<script>(function(){"
            + "function closeAll(except){document.querySelectorAll('.rr-emoji-popover').forEach(function(p){"
            + "if(p!==except)p.hidden=true;});}"
            + "document.querySelectorAll('.rr-emoji-input').forEach(function(wrap){"
            + "var btn=wrap.querySelector('.rr-emoji-picker-btn');"
            + "var pop=wrap.querySelector('.rr-emoji-popover');"
            + "var input=wrap.querySelector(':scope > input.form-control');"
            + "if(!btn||!pop||!input)return;"
            + "var filter=pop.querySelector('.rr-emoji-filter');"
            + "btn.addEventListener('click',function(e){"
            + "e.stopPropagation();"
            + "var willOpen=pop.hidden;"
            + "closeAll(willOpen?pop:null);"
            + "pop.hidden=!willOpen;"
            + "if(willOpen&&filter){try{filter.focus();}catch(_){}}"
            + "});"
            + "pop.addEventListener('click',function(e){e.stopPropagation();});"
            + "pop.querySelectorAll('.rr-emoji-btn').forEach(function(b){"
            + "b.addEventListener('click',function(){"
            + "var mention=b.getAttribute('data-mention')||'';"
            + "var start=input.selectionStart;var end=input.selectionEnd;var val=input.value;"
            + "if(typeof start==='number'&&typeof end==='number'){"
            + "input.value=val.slice(0,start)+mention+val.slice(end);"
            + "var pos=start+mention.length;"
            + "input.setSelectionRange(pos,pos);"
            + "}else{input.value=(val||'')+mention;}"
            + "pop.hidden=true;"
            + "try{input.focus();}catch(_){}"
            + "});});"
            + "if(filter){filter.addEventListener('input',function(){"
            + "var q=filter.value.toLowerCase().trim();"
            + "pop.querySelectorAll('.rr-emoji-btn').forEach(function(b){"
            + "var name=(b.getAttribute('data-name')||'').toLowerCase();"
            + "b.classList.toggle('is-hidden',q!==''&&name.indexOf(q)===-1);"
            + "});});}"
            + "});"
            + "document.addEventListener('click',function(){closeAll(null);});"
            + "document.addEventListener('keydown',function(e){if(e.key==='Escape')closeAll(null);});"
            + "})();</script>";

    private static String roleLabel(java.util.List<GuildLookup.Option> opts, String id) {
        if (id == null || id.isBlank()) return "<span class=text-secondary>(unset)</span>";
        for (GuildLookup.Option o : opts) {
            if (o.id().equals(id)) {
                return "<span>@" + Layout.escape(o.name()) + "</span>";
            }
        }
        return "<span class=text-secondary>(unknown role)</span>";
    }
}
