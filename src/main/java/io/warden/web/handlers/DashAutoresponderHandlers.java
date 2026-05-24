package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.autoresponder.Autoresponder;
import io.warden.autoresponder.AutoresponderService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /dash/autoresponders CRUD: list, new, edit, save, delete, toggle. Rules are
 * stored in the {@code autoresponders} table and applied by
 * {@link AutoresponderService} on every guild message.
 */
public final class DashAutoresponderHandlers {

    private final AutoresponderService service;
    private final GuildLookup lookup;

    public DashAutoresponderHandlers(AutoresponderService service, GuildLookup lookup) {
        this.service = service;
        this.lookup = lookup;
    }

    /* =========================== List page =========================== */

    public void list(Context ctx) throws Exception {
        List<Autoresponder> rules = service.dao().listAll();
        var channelOpts = lookup.textChannels();

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Autoresponders · Warden", "autoresponders", ctx));

        h.append("<div class=\"d-flex justify-content-between align-items-center mb-3\">");
        h.append("<div><h1 class=\"h3 mb-1\">Autoresponders</h1>")
                .append("<p class=\"text-secondary mb-0\">React to chat with a canned message or embed when the rule's pattern matches. ")
                .append("Supports exact, contains, prefix, suffix, and regex. Embeds accept image URLs and PlaceholderAPI placeholders.</p></div>");
        h.append("<a href=\"/dash/autoresponders/new\" class=\"btn btn-primary\"><i class=\"bi bi-plus-lg me-1\"></i>New rule</a>");
        h.append("</div>");

        if (!lookup.discordConnected()) {
            h.append("<div class=\"alert alert-warning\">Discord isn't connected; channel/role pickers will be empty.</div>");
        }

        h.append("<div class=\"card\"><div class=\"card-body p-0\">");
        h.append("<table class=\"table table-hover align-middle mb-0\">");
        h.append("<thead><tr>")
                .append("<th style=\"width:1%\"></th>")
                .append("<th>Name</th>")
                .append("<th>Match</th>")
                .append("<th>Pattern</th>")
                .append("<th>Response</th>")
                .append("<th>Cooldown</th>")
                .append("<th style=\"width:1%\"></th>")
                .append("</tr></thead><tbody>");
        if (rules.isEmpty()) {
            h.append("<tr><td colspan=7 class=\"text-secondary text-center py-4\">No rules yet. Click <strong>New rule</strong> to add one.</td></tr>");
        }
        for (Autoresponder r : rules) {
            String resp = r.isEmbed() ? "embed" : "content";
            h.append("<tr>")
                    .append("<td class=\"text-center\">")
                    .append("<form method=post action=\"/dash/autoresponders/").append(r.id()).append("/toggle\" class=m-0>")
                    .append("<button type=submit class=\"btn btn-sm ")
                    .append(r.enabled() ? "btn-success" : "btn-outline-secondary")
                    .append("\" title=\"").append(r.enabled() ? "Enabled - click to disable" : "Disabled - click to enable").append("\">")
                    .append("<i class=\"bi ").append(r.enabled() ? "bi-toggle-on" : "bi-toggle-off").append("\"></i>")
                    .append("</button></form></td>")
                    .append("<td><a href=\"/dash/autoresponders/").append(r.id()).append("/edit\" class=\"fw-semibold text-decoration-none\">")
                    .append(Layout.escape(r.name().isBlank() ? "(unnamed)" : r.name())).append("</a></td>")
                    .append("<td><code class=small>").append(Layout.escape(r.matchMode()))
                    .append(r.caseInsensitive() ? " <span class=\"badge text-bg-secondary\">ci</span>" : "")
                    .append("</code></td>")
                    .append("<td><code class=small>").append(Layout.escape(truncate(r.pattern(), 50))).append("</code></td>")
                    .append("<td><span class=\"badge text-bg-info\">").append(resp).append("</span></td>")
                    .append("<td>").append(r.cooldownSeconds()).append("s</td>")
                    .append("<td class=\"text-end\">")
                    .append("<div class=\"d-inline-flex gap-1\">")
                    .append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/autoresponders/").append(r.id()).append("/edit\" title=\"Edit\"><i class=\"bi bi-pencil\"></i></a>")
                    .append("<form method=post action=\"/dash/autoresponders/").append(r.id()).append("/delete\" data-confirm=\"Delete this autoresponder?\" data-confirm-kind=\"danger\" class=m-0>")
                    .append("<button type=submit class=\"btn btn-sm btn-outline-danger\" title=\"Delete\"><i class=\"bi bi-trash\"></i></button></form>")
                    .append("</div></td>")
                    .append("</tr>");
        }
        h.append("</tbody></table></div></div>");

        h.append("<p class=\"small text-secondary mt-3\">Placeholders: <code>{user}</code>, <code>{user_mention}</code>, <code>{user_id}</code>, <code>{user_tag}</code>, ")
                .append("<code>{channel}</code>, <code>{channel_name}</code>, <code>{server}</code>, <code>{message}</code>, <code>{match}</code>, ")
                .append("<code>{match.1}</code>...<code>{match.N}</code> for regex groups. ")
                .append("PlaceholderAPI tokens like <code>%player_name%</code> are expanded server-side when the message author is linked via DiscordSRV.</p>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    /* =========================== New / edit form =========================== */

    public void newForm(Context ctx) {
        renderForm(ctx, Autoresponder.blank(), true);
    }

    public void editForm(Context ctx) throws Exception {
        long id = parseId(ctx);
        Optional<Autoresponder> opt = service.dao().find(id);
        if (opt.isEmpty()) {
            ctx.redirect("/dash/autoresponders");
            return;
        }
        renderForm(ctx, opt.get(), false);
    }

    private void renderForm(Context ctx, Autoresponder a, boolean isNew) {
        var channelOpts = lookup.textChannels();
        var roleOpts = lookup.roles();
        String action = isNew ? "/dash/autoresponders/new" : "/dash/autoresponders/" + a.id();

        StringBuilder h = new StringBuilder(16384);
        h.append(Layout.head((isNew ? "New autoresponder" : "Edit autoresponder") + " · Warden", "autoresponders", ctx));

        h.append("<div class=\"d-flex justify-content-between align-items-center mb-3\">");
        h.append("<div><h1 class=\"h3 mb-1\">").append(isNew ? "New autoresponder" : "Edit autoresponder").append("</h1></div>");
        h.append("<a href=\"/dash/autoresponders\" class=\"btn btn-outline-secondary\"><i class=\"bi bi-arrow-left me-1\"></i>Back to list</a>");
        h.append("</div>");

        h.append("<form method=post action=\"").append(action).append("\" class=\"vstack gap-4\">");

        // Basics
        h.append("<section><h2 class=\"h5 mb-3\">Trigger</h2><div class=\"row g-3\">");
        h.append("<div class=col-md-6><label class=form-label for=f_name>Name")
                .append(Layout.infoIcon("Internal label shown on the list. Doesn't appear in Discord."))
                .append("</label><input id=f_name class=form-control name=name maxlength=120 value=\"")
                .append(Layout.escape(a.name())).append("\"></div>");
        h.append("<div class=col-md-3><label class=form-label>Priority")
                .append(Layout.infoIcon("Higher priority rules are evaluated first. Same priority = order by id (older first)."))
                .append("</label><input class=form-control type=number name=priority value=\"").append(a.priority()).append("\"></div>");
        h.append("<div class=col-md-3 d-flex align-items-end\">")
                .append(checkbox("enabled", "Enabled", a.enabled()))
                .append("</div>");

        h.append("<div class=col-md-3><label class=form-label for=f_match>Match mode</label>")
                .append("<select id=f_match name=match_mode class=form-select>")
                .append(opt("contains", "contains (default)", a.matchMode()))
                .append(opt("exact", "exact (full message)", a.matchMode()))
                .append(opt("prefix", "prefix (starts with)", a.matchMode()))
                .append(opt("suffix", "suffix (ends with)", a.matchMode()))
                .append(opt("regex", "regex (Java syntax)", a.matchMode()))
                .append("</select></div>");
        h.append("<div class=col-md-6><label class=form-label for=f_pattern>Pattern")
                .append(Layout.infoIcon("Text to look for. For regex, capture groups are exposed as {match.1}, {match.2}, ..."))
                .append("</label><input id=f_pattern class=form-control name=pattern value=\"")
                .append(Layout.escape(a.pattern())).append("\"></div>");
        h.append("<div class=col-md-3 d-flex align-items-end\">")
                .append(checkbox("case_insensitive", "Case-insensitive", a.caseInsensitive()))
                .append("</div>");

        // AI: describe-in-English -> regex pattern. Renders even when match
        // mode isn't regex (button switches the mode automatically), so admins
        // who don't speak regex can still build complex matchers.
        h.append("<div class=\"col-12\">");
        h.append("<div class=\"ar-ai-card border rounded p-3 bg-body-tertiary\">");
        h.append("<div class=\"d-flex align-items-center gap-2 mb-2\">")
                .append("<i class=\"bi bi-stars text-warning\"></i>")
                .append("<strong>AI helper</strong>")
                .append("<span class=\"text-secondary small\">describe a trigger in plain English and let the AI build a regex pattern.</span>")
                .append("</div>");
        h.append("<div class=\"input-group\">")
                .append("<input id=\"ai-trigger-brief\" class=\"form-control\" type=\"text\" ")
                .append("placeholder=\"e.g. someone asking how to apply for whitelist\" maxlength=\"500\">")
                .append("<button type=\"button\" id=\"ai-build-regex\" class=\"btn btn-outline-primary\">")
                .append("<i class=\"bi bi-magic me-1\"></i>Build regex</button>")
                .append("</div>");
        h.append("<div id=\"ai-trigger-status\" class=\"small text-secondary mt-2\"></div>");
        h.append("</div></div>");

        h.append("</div></section>");

        // Response
        h.append("<section><h2 class=\"h5 mb-3\">Response</h2><div class=\"row g-3\">");
        h.append("<div class=col-md-3><label class=form-label>Mode</label>")
                .append("<select name=response_mode id=f_response_mode class=form-select>")
                .append(opt("content", "Plain message", a.responseMode()))
                .append(opt("embed", "Embed", a.responseMode()))
                .append("</select></div>");
        h.append("<div class=col-md-3>").append(checkbox("reply_to_trigger", "Reply to the trigger message", a.replyToTrigger())).append("</div>");
        h.append("<div class=col-md-3>").append(checkbox("mention_author", "Ping author on reply", a.mentionAuthor())).append("</div>");
        h.append("<div class=col-md-3>").append(checkbox("delete_trigger", "Delete the trigger message", a.deleteTrigger())).append("</div>");

        // AI response drafter: short brief -> filled-in content/title/description.
        // Lives above the message-content textarea so it's the first thing an
        // operator sees in the response section.
        h.append("<div class=\"col-12\">");
        h.append("<div class=\"ar-ai-card border rounded p-3 bg-body-tertiary\">");
        h.append("<div class=\"d-flex align-items-center gap-2 mb-2\">")
                .append("<i class=\"bi bi-stars text-warning\"></i>")
                .append("<strong>AI drafter</strong>")
                .append("<span class=\"text-secondary small\">describe what the bot should say and the AI will fill the fields below.</span>")
                .append("</div>");
        h.append("<div class=\"row g-2\">");
        h.append("<div class=\"col-md-8\"><input id=\"ai-response-brief\" class=\"form-control\" type=\"text\" ")
                .append("placeholder=\"e.g. point them at the apply channel and how long review takes\" maxlength=\"500\"></div>");
        h.append("<div class=\"col-md-3\"><input id=\"ai-response-tone\" class=\"form-control\" type=\"text\" ")
                .append("placeholder=\"tone (optional)\" maxlength=\"60\"></div>");
        h.append("<div class=\"col-md-1 d-grid\">")
                .append("<button type=\"button\" id=\"ai-draft-response\" class=\"btn btn-outline-primary\" title=\"Draft response\">")
                .append("<i class=\"bi bi-magic\"></i></button>")
                .append("</div>");
        h.append("</div>");
        h.append("<div id=\"ai-response-status\" class=\"small text-secondary mt-2\"></div>");
        h.append("</div></div>");

        h.append("<div class=col-12><label class=form-label>Message content")
                .append(Layout.infoIcon("Sent as plain content. For embeds, this becomes the line above the embed (optional)."))
                .append("</label><textarea class=form-control name=content id=ar-content rows=4>").append(Layout.escape(a.content())).append("</textarea></div>");

        // Embed fields (always rendered; toggled via CSS class when mode != embed).
        h.append("<div id=embed-fields class=\"col-12 vstack gap-3\">")
                .append("<div class=\"row g-3\">")
                .append("<div class=col-md-8><label class=form-label>Embed title</label>")
                .append("<input class=form-control id=ar-embed-title name=embed_title maxlength=256 value=\"").append(Layout.escape(a.embedTitle())).append("\"></div>")
                .append("<div class=col-md-4><label class=form-label>Embed color</label>")
                .append("<input class=form-control type=color name=embed_color value=\"").append(Layout.escape(coalesceColor(a.embedColor()))).append("\"></div>")
                .append("<div class=col-12><label class=form-label>Embed description</label>")
                .append("<textarea class=form-control id=ar-embed-description name=embed_description rows=4>").append(Layout.escape(a.embedDescription())).append("</textarea></div>")
                .append("<div class=col-md-6><label class=form-label>Image URL</label>")
                .append("<input class=form-control name=embed_image_url value=\"").append(Layout.escape(a.embedImageUrl())).append("\"></div>")
                .append("<div class=col-md-6><label class=form-label>Thumbnail URL</label>")
                .append("<input class=form-control name=embed_thumbnail_url value=\"").append(Layout.escape(a.embedThumbnailUrl())).append("\"></div>")
                .append("<div class=col-md-6><label class=form-label>Author name</label>")
                .append("<input class=form-control name=embed_author_name maxlength=256 value=\"").append(Layout.escape(a.embedAuthorName())).append("\"></div>")
                .append("<div class=col-md-6><label class=form-label>Author icon URL</label>")
                .append("<input class=form-control name=embed_author_icon value=\"").append(Layout.escape(a.embedAuthorIcon())).append("\"></div>")
                .append("<div class=col-md-6><label class=form-label>Footer text")
                .append(Layout.infoIcon("Combined with the 'Powered By Warden' brand footer."))
                .append("</label><input class=form-control name=embed_footer_text value=\"").append(Layout.escape(a.embedFooterText())).append("\"></div>")
                .append("<div class=col-md-6><label class=form-label>Footer icon URL</label>")
                .append("<input class=form-control name=embed_footer_icon value=\"").append(Layout.escape(a.embedFooterIcon())).append("\"></div>")
                .append("<div class=col-12><label class=form-label>Extra image URLs")
                .append(Layout.infoIcon("One per line. Each non-empty URL becomes an additional embed image card under the main embed (Discord caps at 10 embeds per message)."))
                .append("</label><textarea class=form-control name=extra_image_urls rows=3 placeholder=\"https://...\">")
                .append(Layout.escape(String.join("\n", a.extraImageUrls())))
                .append("</textarea></div>")
                .append("</div></div>");

        h.append("</div></section>");

        // Scope
        h.append("<section><h2 class=\"h5 mb-3\">Where the rule applies</h2><div class=\"row g-3\">");
        h.append("<div class=col-md-6>")
                .append(GuildLookup.multiCheckField("allow_channel_ids", "Allow channels (empty = all)",
                        channelOpts, a.allowChannelIds(),
                        "If set, the rule only fires in these channels."))
                .append("</div>");
        h.append("<div class=col-md-6>")
                .append(GuildLookup.multiCheckField("deny_channel_ids", "Deny channels",
                        channelOpts, a.denyChannelIds(),
                        "Channels where the rule never fires."))
                .append("</div>");
        h.append("<div class=col-md-6>")
                .append(GuildLookup.multiCheckField("allow_role_ids", "Allow roles (empty = all)",
                        roleOpts, a.allowRoleIds(),
                        "If set, the author must have one of these roles."))
                .append("</div>");
        h.append("<div class=col-md-6>")
                .append(GuildLookup.multiCheckField("deny_role_ids", "Deny roles",
                        roleOpts, a.denyRoleIds(),
                        "If the author has any of these roles the rule skips."))
                .append("</div>");
        h.append("<div class=col-md-3><label class=form-label>Cooldown (seconds)")
                .append(Layout.infoIcon("Per-rule guild-wide. 0 = no cooldown."))
                .append("</label><input class=form-control type=number min=0 name=cooldown_seconds value=\"").append(a.cooldownSeconds()).append("\"></div>");
        h.append("</div></section>");

        h.append("<div class=\"d-flex gap-2\"><button class=\"btn btn-primary\">Save</button>")
                .append("<a class=\"btn btn-outline-secondary\" href=\"/dash/autoresponders\">Cancel</a></div>");
        h.append("</form>");

        h.append("<script>(function(){")
                .append("var mode=document.getElementById('f_response_mode');")
                .append("var embedFields=document.getElementById('embed-fields');")
                .append("function sync(){embedFields.style.display=(mode.value==='embed')?'':'none';}")
                .append("mode.addEventListener('change',sync);sync();")
                // --- AI: build regex from plain-English brief ---
                .append("var brBtn=document.getElementById('ai-build-regex');")
                .append("var brIn=document.getElementById('ai-trigger-brief');")
                .append("var brStat=document.getElementById('ai-trigger-status');")
                .append("var matchEl=document.getElementById('f_match');")
                .append("var patIn=document.getElementById('f_pattern');")
                .append("var ciEl=document.querySelector('input[name=case_insensitive]');")
                .append("if(brBtn){brBtn.addEventListener('click',function(){")
                .append("var brief=(brIn.value||'').trim();")
                .append("if(!brief){brStat.textContent='Type a short description first.';return;}")
                .append("brBtn.disabled=true;brStat.textContent='Asking the AI...';")
                .append("var body=new URLSearchParams();body.set('brief',brief);")
                .append("fetch('/dash/ai/autoresponder-regex',{method:'POST',credentials:'same-origin',")
                .append("headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString()})")
                .append(".then(function(r){return r.json().catch(function(){return{ok:false,message:'HTTP '+r.status};});})")
                .append(".then(function(j){")
                .append("if(!j||!j.ok){brStat.textContent='Failed: '+((j&&j.message)||'unknown');return;}")
                .append("if(matchEl)matchEl.value='regex';")
                .append("if(patIn)patIn.value=j.pattern||'';")
                .append("if(ciEl){ciEl.checked=!!j.case_insensitive;}")
                .append("brStat.textContent=j.explanation||'Regex built. Test before saving.';")
                .append("})")
                .append(".catch(function(e){brStat.textContent='Network error: '+(e.message||e);})")
                .append(".finally(function(){brBtn.disabled=false;});")
                .append("});}")
                // --- AI: draft response content / embed title + description ---
                .append("var dBtn=document.getElementById('ai-draft-response');")
                .append("var dBrief=document.getElementById('ai-response-brief');")
                .append("var dTone=document.getElementById('ai-response-tone');")
                .append("var dStat=document.getElementById('ai-response-status');")
                .append("var contentTa=document.getElementById('ar-content');")
                .append("var embedTitle=document.getElementById('ar-embed-title');")
                .append("var embedDesc=document.getElementById('ar-embed-description');")
                .append("if(dBtn){dBtn.addEventListener('click',function(){")
                .append("var brief=(dBrief.value||'').trim();")
                .append("var trigger=patIn?(patIn.value||'').trim():'';")
                .append("if(!brief&&!trigger){dStat.textContent='Type what the bot should say first.';return;}")
                .append("dBtn.disabled=true;dStat.textContent='Drafting...';")
                .append("var body=new URLSearchParams();")
                .append("body.set('intent',brief);body.set('trigger',trigger);")
                .append("body.set('tone',(dTone.value||'').trim());")
                .append("body.set('mode',mode.value||'content');")
                .append("fetch('/dash/ai/autoresponder-response',{method:'POST',credentials:'same-origin',")
                .append("headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString()})")
                .append(".then(function(r){return r.json().catch(function(){return{ok:false,message:'HTTP '+r.status};});})")
                .append(".then(function(j){")
                .append("if(!j||!j.ok){dStat.textContent='Failed: '+((j&&j.message)||'unknown');return;}")
                .append("if(mode.value==='embed'){")
                .append("if(embedTitle&&j.title)embedTitle.value=j.title;")
                .append("if(embedDesc&&j.description)embedDesc.value=j.description;")
                .append("if(contentTa&&j.content)contentTa.value=j.content;")
                .append("}else{")
                .append("if(contentTa)contentTa.value=j.content||j.description||'';")
                .append("}")
                .append("dStat.textContent='Drafted. Review before saving.';")
                .append("})")
                .append(".catch(function(e){dStat.textContent='Network error: '+(e.message||e);})")
                .append(".finally(function(){dBtn.disabled=false;});")
                .append("});}")
                .append("})();</script>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    /* =========================== Save / delete / toggle =========================== */

    public void save(Context ctx) throws Exception {
        long id = isEditPath(ctx) ? parseId(ctx) : 0L;
        Autoresponder existing = id > 0
                ? service.dao().find(id).orElse(Autoresponder.blank())
                : Autoresponder.blank();
        Autoresponder next = new Autoresponder(
                id,
                str(ctx, "name"),
                bool(ctx, "enabled"),
                str(ctx, "match_mode"),
                strRaw(ctx, "pattern"),
                bool(ctx, "case_insensitive"),
                str(ctx, "response_mode"),
                strRaw(ctx, "content"),
                strRaw(ctx, "embed_title"),
                strRaw(ctx, "embed_description"),
                str(ctx, "embed_color"),
                str(ctx, "embed_image_url"),
                str(ctx, "embed_thumbnail_url"),
                strRaw(ctx, "embed_author_name"),
                str(ctx, "embed_author_icon"),
                strRaw(ctx, "embed_footer_text"),
                str(ctx, "embed_footer_icon"),
                splitLines(strRaw(ctx, "extra_image_urls")),
                multiVals(ctx, "allow_channel_ids"),
                multiVals(ctx, "deny_channel_ids"),
                multiVals(ctx, "allow_role_ids"),
                multiVals(ctx, "deny_role_ids"),
                intOr(ctx, "cooldown_seconds", 0),
                bool(ctx, "reply_to_trigger"),
                bool(ctx, "delete_trigger"),
                bool(ctx, "mention_author"),
                intOr(ctx, "priority", 0),
                existing.createdAt(),
                System.currentTimeMillis());
        if (id > 0) {
            service.dao().update(next);
        } else {
            service.dao().create(next);
        }
        ctx.redirect("/dash/autoresponders");
    }

    public void delete(Context ctx) throws Exception {
        service.dao().delete(parseId(ctx));
        ctx.redirect("/dash/autoresponders");
    }

    public void toggle(Context ctx) throws Exception {
        long id = parseId(ctx);
        Optional<Autoresponder> opt = service.dao().find(id);
        if (opt.isPresent()) service.dao().setEnabled(id, !opt.get().enabled());
        ctx.redirect("/dash/autoresponders");
    }

    /* =========================== Helpers =========================== */

    private static boolean isEditPath(Context ctx) {
        String p = ctx.path();
        return p != null && !p.endsWith("/new");
    }

    private static long parseId(Context ctx) {
        try { return Long.parseLong(ctx.pathParam("id")); }
        catch (Exception e) { return 0L; }
    }

    private static String checkbox(String name, String label, boolean checked) {
        return "<div class=\"form-check form-switch\">"
                + "<input class=form-check-input type=checkbox role=switch id=ar-" + name
                + " name=" + name + " value=on" + (checked ? " checked" : "") + ">"
                + "<label class=form-check-label for=ar-" + name + ">" + Layout.escape(label) + "</label>"
                + "</div>";
    }

    private static String opt(String value, String label, String selected) {
        boolean sel = value.equalsIgnoreCase(selected);
        return "<option value=\"" + value + "\"" + (sel ? " selected" : "") + ">" + Layout.escape(label) + "</option>";
    }

    private static String coalesceColor(String c) {
        if (c == null || c.isBlank()) return "#5865F2";
        return c.startsWith("#") ? c : "#" + c;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private static List<String> splitLines(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : s.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static List<String> multiVals(Context ctx, String key) {
        var raw = ctx.formParams(key);
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) if (s != null && !s.isBlank()) out.add(s.trim());
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
        return v == null ? "" : v.trim();
    }

    private static String strRaw(Context ctx, String key) {
        String v = ctx.formParam(key);
        return v == null ? "" : v;
    }

    private static int intOr(Context ctx, String key, int fallback) {
        try { return Integer.parseInt(ctx.formParam(key)); }
        catch (Exception e) { return fallback; }
    }
}
