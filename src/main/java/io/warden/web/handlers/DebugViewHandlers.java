package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.debug.DebugReport;
import io.warden.debug.DebugReportDao;

import java.util.Map;

/**
 * Public debug viewer. Two endpoints:
 *
 *   GET /debug/{id}              - serves the viewer shell (no auth required)
 *   GET /api/debug/{id}/payload  - returns the encrypted blob + status
 *
 * The viewer shell loads the AES-GCM key from the URL fragment (#k=...) and
 * decrypts the payload entirely in the browser using the Web Crypto API.
 * The key is NEVER sent to the server on this or any subsequent request.
 */
public final class DebugViewHandlers {

    private final DebugReportDao dao;
    private final Services       services;

    public DebugViewHandlers(Services services) {
        this.services = services;
        this.dao      = services.debugReportDao;
    }

    // ── GET /debug/{id} ───────────────────────────────────────────────────────

    public void view(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        DebugReport report = dao.findById(id);
        if (report == null) {
            ctx.status(404).html(notFoundPage(id));
            return;
        }
        ctx.html(viewerPage(report));
    }

    // ── GET /api/debug/{id}/payload ───────────────────────────────────────────

    public void payload(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        DebugReport report = dao.findById(id);
        if (report == null) {
            ctx.status(404).json(Map.of("error", "not_found"));
            return;
        }
        ctx.json(Map.of(
                "encrypted_payload", report.encryptedPayload(),
                "analysis_status",   report.analysisStatus()
        ));
    }

    // ── Page builder ──────────────────────────────────────────────────────────

    private static String viewerPage(DebugReport report) {
        return "<!doctype html><html lang=en><head>"
                + "<meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>Debug Report · Warden</title>"
                + Layout.THEME_BOOT_SCRIPT
                + "<link rel=icon type=\"image/svg+xml\" href=\"/static/img/warden-icon.svg\">"
                + "<link rel=stylesheet href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css\">"
                + "<link rel=stylesheet href=\"https://cdn.jsdelivr.net/npm/bootstrap-icons@1.13.1/font/bootstrap-icons.min.css\">"
                + "<link rel=stylesheet href=\"https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/styles/github-dark.min.css\">"
                + "<style>" + VIEWER_CSS + "</style>"
                + "</head><body>"
                + "<div id=\"dv-loading\" class=\"dv-loading\">"
                + "<div class=\"dv-loading-inner\">"
                + "<img src=\"/static/img/warden-icon.svg\" class=\"dv-loading-logo\" alt=\"\">"
                + "<div class=\"dv-loading-title\">Warden Debug Viewer</div>"
                + "<div class=\"dv-loading-status\" id=\"dv-status\">Decrypting report…</div>"
                + "<div class=\"dv-spinner\"><div></div><div></div><div></div></div>"
                + "</div>"
                + "</div>"
                + "<div id=\"dv-error\" class=\"dv-error\" style=\"display:none\">"
                + "<img src=\"/static/img/warden-icon.svg\" class=\"dv-err-logo\" alt=\"\">"
                + "<div class=\"dv-err-title\" id=\"dv-err-title\">Error</div>"
                + "<div class=\"dv-err-msg\" id=\"dv-err-msg\"></div>"
                + "</div>"
                + "<div id=\"dv-content\" style=\"display:none\">"
                + "<header class=\"dv-header\">"
                + "<div class=\"dv-header-inner\">"
                + "<a href=\"/\" class=\"dv-brand\"><img src=\"/static/img/warden-icon.svg\" alt=\"Warden\" class=\"dv-brand-logo\">Warden</a>"
                + "<div class=\"dv-header-meta\">"
                + "<div class=\"dv-report-id\">Debug Report <code id=\"dv-id\">" + escJs(report.id()) + "</code></div>"
                + "<div class=\"dv-report-date\" id=\"dv-date\">Generated <time id=\"dv-gen-time\"></time></div>"
                + "</div>"
                + "<div class=\"dv-header-actions\">"
                + "<button class=\"dv-btn\" id=\"dv-copy-btn\" onclick=\"copyUrl()\"><i class=\"bi bi-share\"></i> Share</button>"
                + "<a class=\"dv-btn\" href=\"/dash/debug\"><i class=\"bi bi-list-ul\"></i> All reports</a>"
                + "</div>"
                + "</div>"
                + "</header>"
                + "<main class=\"dv-main\">"
                + "<div id=\"dv-analysis-card\"></div>"
                + "<div id=\"dv-health-bar\" class=\"dv-section\"></div>"
                + "<div id=\"dv-sections\"></div>"
                + "</main>"
                + "</div>"
                + "<script src=\"https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/highlight.min.js\"></script>"
                + "<script>" + viewerScript(report.id()) + "</script>"
                + "<script src=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/js/bootstrap.bundle.min.js\"></script>"
                + "</body></html>";
    }

    private static String viewerScript(String id) {
        return "var REPORT_ID=" + jsStr(id) + ";"
                + VIEWER_JS;
    }

    // ── Error pages ───────────────────────────────────────────────────────────

    private static String notFoundPage(String id) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8>"
                + "<title>Report not found · Warden</title>"
                + Layout.THEME_BOOT_SCRIPT
                + "<link rel=icon type=\"image/svg+xml\" href=\"/static/img/warden-icon.svg\">"
                + "<link rel=stylesheet href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css\">"
                + "<style>body{display:flex;align-items:center;justify-content:center;min-height:100vh;}</style>"
                + "</head><body>"
                + "<div class=\"text-center p-4\">"
                + "<img src=\"/static/img/warden-icon.svg\" style=\"width:48px;opacity:.4\" class=\"mb-3\" alt=\"\">"
                + "<h2 class=\"h4\">Report not found</h2>"
                + "<p class=\"text-secondary\">No debug report exists with ID <code>" + escJs(id) + "</code>.</p>"
                + "<a href=\"/dash/debug\" class=\"btn btn-primary btn-sm\">All reports</a>"
                + "</div></body></html>";
    }

    // ── CSS ───────────────────────────────────────────────────────────────────

    private static final String VIEWER_CSS =
            // Loading overlay
            ".dv-loading{position:fixed;inset:0;background:var(--bs-body-bg,#0d1117);display:flex;align-items:center;justify-content:center;z-index:9999;transition:opacity .4s;}"
            + ".dv-loading.dv-fade{opacity:0;pointer-events:none;}"
            + ".dv-loading-inner{text-align:center;}"
            + ".dv-loading-logo{width:56px;height:56px;animation:dv-pulse 2s ease-in-out infinite;}"
            + "@keyframes dv-pulse{0%,100%{opacity:.7;transform:scale(1)}50%{opacity:1;transform:scale(1.05)}}"
            + ".dv-loading-title{font-size:1.15rem;font-weight:600;margin-top:.75rem;color:var(--bs-emphasis-color,#e6edf3);}"
            + ".dv-loading-status{font-size:.85rem;color:var(--bs-secondary-color,#8b949e);margin-top:.3rem;}"
            + ".dv-spinner{display:flex;gap:.35rem;justify-content:center;margin-top:1rem;}"
            + ".dv-spinner>div{width:8px;height:8px;border-radius:50%;background:#5865f2;animation:dv-bounce 1.2s ease-in-out infinite;}"
            + ".dv-spinner>div:nth-child(2){animation-delay:.2s}.dv-spinner>div:nth-child(3){animation-delay:.4s}"
            + "@keyframes dv-bounce{0%,80%,100%{transform:scale(.7);opacity:.5}40%{transform:scale(1);opacity:1}}"
            // Error page
            + ".dv-error{min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:2rem;text-align:center;}"
            + ".dv-err-logo{width:48px;opacity:.4;margin-bottom:1rem;}"
            + ".dv-err-title{font-size:1.2rem;font-weight:600;margin-bottom:.5rem;}"
            + ".dv-err-msg{color:var(--bs-secondary-color,#8b949e);max-width:420px;font-size:.9rem;}"
            // Header
            + ".dv-header{position:sticky;top:0;z-index:100;background:var(--bs-body-bg,#0d1117);border-bottom:1px solid var(--bs-border-color,#30363d);backdrop-filter:blur(8px);}"
            + ".dv-header-inner{max-width:960px;margin:0 auto;padding:.75rem 1.25rem;display:flex;align-items:center;gap:1rem;flex-wrap:wrap;}"
            + ".dv-brand{display:flex;align-items:center;gap:.5rem;font-weight:700;font-size:1rem;text-decoration:none;color:inherit;flex-shrink:0;}"
            + ".dv-brand-logo{width:28px;height:28px;}"
            + ".dv-header-meta{flex:1;min-width:0;}"
            + ".dv-report-id{font-size:.85rem;font-weight:600;}"
            + ".dv-report-id code{font-size:.8rem;opacity:.7;}"
            + ".dv-report-date{font-size:.78rem;color:var(--bs-secondary-color,#8b949e);}"
            + ".dv-header-actions{display:flex;gap:.5rem;flex-shrink:0;}"
            + ".dv-btn{display:inline-flex;align-items:center;gap:.35rem;padding:.3rem .75rem;border-radius:.375rem;font-size:.82rem;font-weight:500;border:1px solid var(--bs-border-color,#30363d);background:transparent;color:inherit;cursor:pointer;text-decoration:none;transition:background .15s,border-color .15s;}"
            + ".dv-btn:hover{background:var(--bs-secondary-bg,#21262d);border-color:var(--bs-border-color-translucent,#8b949e50);}"
            // Main layout
            + ".dv-main{max-width:960px;margin:0 auto;padding:1.5rem 1.25rem 3rem;}"
            + ".dv-section{margin-bottom:1.25rem;}"
            // Health bar
            + ".dv-health{display:flex;gap:.6rem;flex-wrap:wrap;padding:1rem 1.25rem;background:var(--bs-body-bg,#0d1117);border:1px solid var(--bs-border-color,#30363d);border-radius:.5rem;}"
            + ".dv-health-item{display:flex;align-items:center;gap:.35rem;font-size:.82rem;font-weight:500;padding:.3rem .65rem;border-radius:2rem;border:1px solid;}"
            + ".dv-health-ok{color:#3fb950;border-color:#3fb95033;background:#3fb95010;}"
            + ".dv-health-warn{color:#d29922;border-color:#d2992233;background:#d2992210;}"
            + ".dv-health-error{color:#f85149;border-color:#f8514933;background:#f8514910;}"
            + ".dv-health-muted{color:var(--bs-secondary-color,#8b949e);border-color:var(--bs-border-color,#30363d);background:transparent;}"
            // Analysis card
            + ".dv-analysis{border:1px solid var(--bs-border-color,#30363d);border-radius:.5rem;overflow:hidden;margin-bottom:1.25rem;}"
            + ".dv-analysis-hd{display:flex;align-items:center;gap:.6rem;padding:.9rem 1.1rem;background:var(--bs-secondary-bg,#161b22);border-bottom:1px solid var(--bs-border-color,#30363d);}"
            + ".dv-analysis-hd-title{font-weight:600;font-size:.9rem;}"
            + ".dv-analysis-summary{padding:.85rem 1.1rem;font-size:.88rem;color:var(--bs-secondary-color,#8b949e);border-bottom:1px solid var(--bs-border-color,#30363d);}"
            + ".dv-finding{padding:.85rem 1.1rem;display:grid;grid-template-columns:auto 1fr;gap:.5rem 1rem;border-bottom:1px solid var(--bs-border-color,#30363d);}"
            + ".dv-finding:last-child{border-bottom:0;}"
            + ".dv-finding-badge{padding:.2rem .55rem;border-radius:.25rem;font-size:.72rem;font-weight:700;letter-spacing:.03em;text-transform:uppercase;white-space:nowrap;}"
            + ".badge-error{background:#f8514933;color:#f85149;border:1px solid #f8514944;}"
            + ".badge-warning{background:#d2992233;color:#d29922;border:1px solid #d2992244;}"
            + ".badge-info{background:#1f6feb33;color:#58a6ff;border:1px solid #1f6feb44;}"
            + ".dv-finding-body{min-width:0;}"
            + ".dv-finding-title{font-weight:600;font-size:.88rem;margin-bottom:.2rem;}"
            + ".dv-finding-detail{font-size:.83rem;color:var(--bs-secondary-color,#8b949e);margin-bottom:.3rem;}"
            + ".dv-finding-fix{font-size:.81rem;padding:.35rem .65rem;background:var(--bs-secondary-bg,#161b22);border-radius:.3rem;border-left:2px solid #58a6ff44;}"
            + ".dv-finding-fix-label{font-size:.73rem;font-weight:600;color:#58a6ff;text-transform:uppercase;letter-spacing:.04em;display:block;margin-bottom:.15rem;}"
            + ".dv-analysis-pending{padding:1rem 1.1rem;display:flex;align-items:center;gap:.75rem;color:var(--bs-secondary-color,#8b949e);font-size:.88rem;}"
            + ".dv-analysis-spinner{width:16px;height:16px;border:2px solid var(--bs-border-color,#30363d);border-top-color:#5865f2;border-radius:50%;animation:spin .8s linear infinite;}"
            + "@keyframes spin{to{transform:rotate(360deg)}}"
            // Data sections
            + ".dv-card{border:1px solid var(--bs-border-color,#30363d);border-radius:.5rem;overflow:hidden;margin-bottom:1rem;}"
            + ".dv-card-hd{display:flex;align-items:center;gap:.6rem;padding:.75rem 1.1rem;background:var(--bs-secondary-bg,#161b22);cursor:pointer;user-select:none;}"
            + ".dv-card-hd:hover{background:var(--bs-tertiary-bg,#1c2128);}"
            + ".dv-card-title{font-weight:600;font-size:.88rem;flex:1;}"
            + ".dv-card-toggle{font-size:.7rem;color:var(--bs-secondary-color,#8b949e);transition:transform .2s;}"
            + ".dv-card.dv-collapsed .dv-card-toggle{transform:rotate(-90deg);}"
            + ".dv-card-body{padding:0;}"
            + ".dv-card.dv-collapsed .dv-card-body{display:none;}"
            + ".dv-kv-table{width:100%;border-collapse:collapse;font-size:.83rem;}"
            + ".dv-kv-table tr{border-bottom:1px solid var(--bs-border-color,#30363d);}"
            + ".dv-kv-table tr:last-child{border-bottom:0;}"
            + ".dv-kv-table td{padding:.45rem .85rem;vertical-align:top;}"
            + ".dv-kv-table td:first-child{width:38%;color:var(--bs-secondary-color,#8b949e);font-family:monospace;white-space:nowrap;font-size:.79rem;}"
            + ".dv-kv-table td:last-child{font-family:monospace;font-size:.8rem;word-break:break-all;}"
            + ".dv-val-true{color:#3fb950;}"
            + ".dv-val-false{color:#f85149;}"
            + ".dv-val-null{color:var(--bs-secondary-color,#8b949e);font-style:italic;}"
            + ".dv-val-redacted{color:var(--bs-secondary-color,#8b949e);opacity:.6;}"
            + ".dv-val-notset{color:#f85149;}"
            + ".dv-nested{padding:.5rem .85rem;}"
            + ".dv-nested-title{font-size:.75rem;font-weight:600;text-transform:uppercase;letter-spacing:.04em;color:var(--bs-secondary-color,#8b949e);margin-bottom:.4rem;}"
            + ".dv-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:.8rem;padding:.85rem;}"
            + ".dv-pill{display:flex;align-items:center;gap:.4rem;font-size:.79rem;padding:.25rem .6rem;border-radius:.3rem;border:1px solid var(--bs-border-color,#30363d);}"
            + ".dv-pill.ok{color:#3fb950;border-color:#3fb95030;}"
            + ".dv-pill.off{color:var(--bs-secondary-color,#8b949e);}"
            // Polished toast-like copy feedback
            + ".dv-copy-toast{position:fixed;bottom:1.5rem;left:50%;transform:translateX(-50%);background:#238636;color:#fff;padding:.45rem 1.1rem;border-radius:2rem;font-size:.83rem;font-weight:500;box-shadow:0 4px 12px #0008;opacity:0;transition:opacity .25s;pointer-events:none;z-index:9999;}"
            + ".dv-copy-toast.show{opacity:1;}"
            // File cards
            + ".dv-file-item{border-bottom:1px solid var(--bs-border-color,#30363d);}"
            + ".dv-file-item:last-child{border-bottom:0;}"
            + ".dv-file-item.dv-collapsed .dv-card-body{display:none;}"
            + ".dv-file-item.dv-collapsed .dv-card-toggle{transform:rotate(-90deg);}"
            + ".dv-file-hd{display:flex;align-items:center;gap:.5rem;padding:.55rem 1.1rem;cursor:pointer;font-size:.82rem;}"
            + ".dv-file-hd:hover{background:var(--bs-tertiary-bg,#1c2128);}"
            + ".dv-file-path{flex:1;font-family:monospace;font-size:.79rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}"
            + ".dv-file-badge-trunc{flex-shrink:0;font-size:.69rem;padding:.12rem .4rem;background:#d2992228;color:#d29922;border:1px solid #d2992240;border-radius:.25rem;}"
            + ".dv-card-badge{font-size:.71rem;padding:.12rem .45rem;background:var(--bs-secondary-bg,#21262d);border:1px solid var(--bs-border-color,#30363d);border-radius:.25rem;color:var(--bs-secondary-color,#8b949e);}"
            + ".dv-code-pre{margin:0;border-radius:0;max-height:500px;overflow-y:auto;font-size:.76rem;line-height:1.5;border-top:1px solid var(--bs-border-color,#30363d);}"
            + ".dv-code-pre code.hljs{padding:.85rem 1.1rem;background:var(--bs-body-bg,#0d1117);}";

    // ── JavaScript ────────────────────────────────────────────────────────────

    private static final String VIEWER_JS =
            // ── Helpers
            "(function(){"
            + "var POLL_INTERVAL=4000;"
            + "var pollingTimer=null;"
            + "var lastPayloadB64=null;"
            + "var currentData=null;"

            // base64 decode (standard + url-safe variants)
            + "function b64Decode(s){"
            + "s=s.replace(/-/g,'+').replace(/_/g,'/');"
            + "while(s.length%4!==0)s+='=';"
            + "var bin=atob(s);"
            + "var bytes=new Uint8Array(bin.length);"
            + "for(var i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);"
            + "return bytes;"
            + "}"

            // Extract key from fragment  #k=<base64url>
            + "function keyFromHash(){"
            + "var h=window.location.hash.replace(/^#/,'');"
            + "var pairs=h.split('&');"
            + "for(var i=0;i<pairs.length;i++){"
            + "var kv=pairs[i].split('=');"
            + "if(kv[0]==='k'&&kv.length>=2)return decodeURIComponent(kv.slice(1).join('='));"
            + "}return null;"
            + "}"

            // AES-GCM decrypt via WebCrypto
            + "async function aesGcmDecrypt(encB64,keyB64url){"
            + "var keyBytes=b64Decode(keyB64url);"
            + "var cryptoKey=await crypto.subtle.importKey('raw',keyBytes,{name:'AES-GCM'},false,['decrypt']);"
            + "var combined=b64Decode(encB64);"
            + "var iv=combined.slice(0,12);"
            + "var ct=combined.slice(12);"
            + "var plain=await crypto.subtle.decrypt({name:'AES-GCM',iv:iv},cryptoKey,ct);"
            + "return new TextDecoder().decode(plain);"
            + "}"

            // Show error state
            + "function showError(title,msg){"
            + "clearInterval(pollingTimer);"
            + "document.getElementById('dv-loading').style.display='none';"
            + "document.getElementById('dv-error').style.display='';"
            + "document.getElementById('dv-err-title').textContent=title;"
            + "document.getElementById('dv-err-msg').textContent=msg;"
            + "}"

            // Fade loading overlay
            + "function hideLoding(){"
            + "var l=document.getElementById('dv-loading');"
            + "l.classList.add('dv-fade');"
            + "setTimeout(function(){l.style.display='none';},450);"
            + "document.getElementById('dv-content').style.display='';"
            + "}"

            // Fetch payload from server
            + "async function fetchPayload(){"
            + "var res=await fetch('/api/debug/'+REPORT_ID+'/payload');"
            + "if(!res.ok)throw new Error('HTTP '+res.status);"
            + "return await res.json();"
            + "}"

            // ── Rendering

            + "function esc(s){if(s==null)return '';return String(s).replace(/[&<>\"']/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}"

            + "function valHtml(v){"
            + "if(v===null||v===undefined)return '<span class=\"dv-val-null\">null</span>';"
            + "if(v===true)return '<span class=\"dv-val-true\">true</span>';"
            + "if(v===false)return '<span class=\"dv-val-false\">false</span>';"
            + "var s=String(v);"
            + "if(s==='[REDACTED]')return '<span class=\"dv-val-redacted\">[REDACTED]</span>';"
            + "if(s==='[NOT SET]')return '<span class=\"dv-val-notset\">[NOT SET]</span>';"
            + "if(s==='[SET]')return '<span class=\"dv-val-true\">[SET]</span>';"
            + "return esc(s);"
            + "}"

            + "function kvTable(obj){"
            + "if(!obj||typeof obj!=='object')return '';"
            + "var rows='';"
            + "for(var k in obj){"
            + "var v=obj[k];"
            + "if(v!==null&&typeof v==='object'&&!Array.isArray(v))continue;"
            + "rows+='<tr><td>'+esc(k)+'</td><td>'+valHtml(v)+'</td></tr>';"
            + "}"
            + "return '<table class=\"dv-kv-table\">'+rows+'</table>';"
            + "}"

            + "function renderSection(icon,title,obj,expandedByDefault){"
            + "var id='sec-'+title.replace(/\\W/g,'');"
            + "var collapsed=expandedByDefault?'':' dv-collapsed';"
            + "var body=buildSectionBody(obj);"
            + "return '<div class=\"dv-card'+collapsed+'\" id=\"'+id+'\">'+"
            + "'<div class=\"dv-card-hd\" onclick=\"toggleCard(\\'' +id+'\\')\">'+"
            + "'<i class=\"bi '+icon+'\"></i>'+"
            + "'<span class=\"dv-card-title\">'+esc(title)+'</span>'+"
            + "'<i class=\"bi bi-chevron-down dv-card-toggle\"></i>'+"
            + "'</div>'+"
            + "'<div class=\"dv-card-body\">'+body+'</div>'+"
            + "'</div>';"
            + "}"

            + "function buildSectionBody(obj){"
            + "if(!obj||typeof obj!=='object')return '<div class=\"p-3 text-secondary small\">No data</div>';"
            + "var out='';"
            // Top-level flat keys
            + "var flatKeys=Object.keys(obj).filter(function(k){var v=obj[k];return v===null||typeof v!=='object'||Array.isArray(v);});"
            + "if(flatKeys.length>0){"
            + "out+='<table class=\"dv-kv-table\">';"
            + "flatKeys.forEach(function(k){out+='<tr><td>'+esc(k)+'</td><td>'+valHtml(obj[k])+'</td></tr>';});"
            + "out+='</table>';"
            + "}"
            // Nested objects (subsections)
            + "var objKeys=Object.keys(obj).filter(function(k){var v=obj[k];return v!==null&&typeof v==='object'&&!Array.isArray(v);});"
            + "objKeys.forEach(function(k){"
            + "out+='<div class=\"dv-nested\"><div class=\"dv-nested-title\">'+esc(k)+'</div>'+kvTable(obj[k])+'</div>';"
            + "});"
            + "return out||'<div class=\"p-3 text-secondary small\">No data</div>';"
            + "}"

            + "function renderModules(mods){"
            + "if(!mods||typeof mods!=='object')return '';"
            + "var pills='';"
            + "for(var k in mods){"
            + "var on=mods[k]===true;"
            + "pills+='<div class=\"dv-pill '+(on?'ok':'off')+'\">'+"
            + "'<i class=\"bi '+(on?'bi-check-circle-fill':'bi-dash-circle')+'\"></i>'+"
            + "esc(k.replace(/_/g,' '))+'</div>';"
            + "}"
            + "return '<div class=\"dv-grid\">'+pills+'</div>';"
            + "}"

            // ── Health bar

            + "function renderHealthBar(data){"
            + "var items=[];"
            + "var discord=data.discord||{};"
            + "var dReady=discord.ready===true;"
            + "items.push(healthItem('bi-discord','Discord',dReady?'ok':(discord.status==='not_started'?'error':'warn'),dReady?'Ready':discord.jda_status||'Not ready'));"
            + "var web=data.web||{};"
            + "var wRun=web.running===true;"
            + "items.push(healthItem('bi-globe','Web server',wRun?'ok':'error',wRun?(web.ssl_active?'HTTPS':'HTTP')+' :'+web.bind_port:'Down'));"
            + "var llm=data.llm||{};"
            + "var lSet=llm.api_key_set===true;"
            + "items.push(healthItem('bi-cpu','LLM',lSet?'ok':'muted',lSet?llm.model||'ready':'Not configured'));"
            + "var db=data.database||{};"
            + "var hasError=db.count_error!=null||db.error!=null;"
            + "items.push(healthItem('bi-database',hasError?'Database error':'Database',hasError?'error':'ok',hasError?(db.count_error||db.error):((db.file_size_kb||0)+' KB')));"
            + "return '<div class=\"dv-health\">'+items.join('')+'</div>';"
            + "}"

            + "function healthItem(icon,label,kind,detail){"
            + "return '<div class=\"dv-health-item dv-health-'+kind+'\">'+"
            + "'<i class=\"bi '+icon+'\"></i>'+"
            + "'<span>'+esc(label)+'</span>'+"
            + "'<span style=\"opacity:.65;font-size:.75rem;\">&middot; '+esc(detail)+'</span>'+"
            + "'</div>';"
            + "}"

            // ── Analysis card

            + "function renderAnalysis(analysis,status){"
            + "var el=document.getElementById('dv-analysis-card');"
            + "if(!el)return;"
            + "if(status==='pending'||status==='skipped'){"
            + "var msg=status==='pending'?'AI analysis is running in the background...':'No LLM configured - AI analysis skipped.';"
            + "var cls=status==='pending'?'<div class=\"dv-analysis-spinner\"></div>':'<i class=\"bi bi-dash-circle text-secondary\"></i>';"
            + "el.innerHTML='<div class=\"dv-analysis\"><div class=\"dv-analysis-hd\"><i class=\"bi bi-robot\"></i><span class=\"dv-analysis-hd-title\">AI Analysis</span></div><div class=\"dv-analysis-pending\">'+cls+'<span>'+esc(msg)+'</span></div></div>';"
            + "return;"
            + "}"
            + "if(!analysis){"
            + "if(status==='failed')el.innerHTML='<div class=\"dv-analysis\"><div class=\"dv-analysis-hd\"><i class=\"bi bi-robot\"></i><span class=\"dv-analysis-hd-title\">AI Analysis</span><span class=\"dv-finding-badge badge-error ms-auto\">Failed</span></div><div class=\"dv-analysis-pending\"><i class=\"bi bi-x-circle text-danger\"></i><span>Analysis failed - check server log for details.</span></div></div>';"
            + "return;"
            + "}"
            + "var html='<div class=\"dv-analysis\">';"
            + "html+='<div class=\"dv-analysis-hd\"><i class=\"bi bi-robot text-primary\"></i><span class=\"dv-analysis-hd-title\">AI Analysis</span></div>';"
            + "if(analysis.summary)html+='<div class=\"dv-analysis-summary\">'+esc(analysis.summary)+'</div>';"
            + "var findings=analysis.findings||[];"
            + "findings.forEach(function(f){"
            + "var sev=f.severity||'info';"
            + "var badgeCls=sev==='error'?'badge-error':sev==='warning'?'badge-warning':'badge-info';"
            + "html+='<div class=\"dv-finding\">';"
            + "html+='<div><span class=\"dv-finding-badge '+badgeCls+'\">'+esc(sev)+'</span></div>';"
            + "html+='<div class=\"dv-finding-body\">';"
            + "html+='<div class=\"dv-finding-title\">'+esc(f.title||'')+'</div>';"
            + "if(f.detail)html+='<div class=\"dv-finding-detail\">'+esc(f.detail)+'</div>';"
            + "if(f.fix)html+='<div class=\"dv-finding-fix\"><span class=\"dv-finding-fix-label\">Fix</span>'+esc(f.fix)+'</div>';"
            + "html+='</div></div>';"
            + "});"
            + "if(findings.length===0)html+='<div class=\"dv-analysis-pending\"><i class=\"bi bi-check-circle-fill text-success\"></i><span>No issues found.</span></div>';"
            + "html+='</div>';"
            + "el.innerHTML=html;"
            + "}"

            // ── Files section

            + "function renderFilesSection(files){"
            + "if(!files||!files.length)return '';"
            + "var html='<div class=\"dv-card dv-collapsed\" id=\"sec-Files\">';"
            + "html+='<div class=\"dv-card-hd\" onclick=\"toggleCard(\\'sec-Files\\')\"><i class=\"bi bi-files\"></i>';"
            + "html+='<span class=\"dv-card-title\">Config Files &amp; Logs</span>';"
            + "html+='<span class=\"dv-card-badge ms-2\">'+files.length+' files</span>';"
            + "html+='<i class=\"bi bi-chevron-down dv-card-toggle ms-auto\"></i></div>';"
            + "html+='<div class=\"dv-card-body\">';"
            + "files.forEach(function(f,i){"
            + "var fid='dv-file-'+i;"
            + "var ext=f.path?f.path.split('.').pop().toLowerCase():'';"
            + "var lang=ext==='yml'||ext==='yaml'?'yaml':ext==='properties'?'properties':ext==='json'?'json':ext==='xml'?'xml':'plaintext';"
            + "html+='<div class=\"dv-file-item dv-collapsed\" id=\"'+fid+'\">';"
            + "html+='<div class=\"dv-file-hd\" onclick=\"toggleCard(\\'' +fid+'\\')\">';"
            + "html+='<i class=\"bi bi-file-code text-secondary\"></i>';"
            + "html+='<span class=\"dv-file-path\">'+esc(f.path||'')+'</span>';"
            + "if(f.truncated)html+='<span class=\"dv-file-badge-trunc\">truncated</span>';"
            + "html+='<i class=\"bi bi-chevron-down dv-card-toggle ms-2\"></i></div>';"
            + "html+='<div class=\"dv-card-body\">';"
            + "if(f.error){html+='<div class=\"p-3 text-danger small\">'+esc(f.error)+'</div>';}"
            + "else if(f.content){html+='<pre class=\"dv-code-pre\"><code class=\"language-'+lang+'\">'+esc(f.content)+'</code></pre>';}"
            + "else{html+='<div class=\"p-3 text-secondary small\">Empty file</div>';}"
            + "html+='</div></div>';"
            + "});"
            + "html+='</div></div>';"
            + "return html;"
            + "}"

            // ── Main render

            + "function render(data,analysisStatus){"
            + "currentData=data;"
            + "var gen=data.generated_at||'';"
            + "var t=document.getElementById('dv-gen-time');"
            + "if(t){t.textContent=gen;t.setAttribute('title',gen);}"
            + "var hb=document.getElementById('dv-health-bar');"
            + "if(hb)hb.innerHTML=renderHealthBar(data);"
            + "var sections=["
            + "{icon:'bi-cpu',title:'System',key:'system',exp:true},"
            + "{icon:'bi-box',title:'Plugin',key:'plugin',exp:true},"
            + "{icon:'bi-sliders',title:'Config',key:'config',exp:true},"
            + "{icon:'bi-discord',title:'Discord',key:'discord',exp:true},"
            + "{icon:'bi-globe',title:'Web Server',key:'web',exp:true},"
            + "{icon:'bi-database',title:'Database',key:'database',exp:false},"
            + "{icon:'bi-puzzle',title:'Modules',key:'modules',exp:false},"
            + "{icon:'bi-cpu-fill',title:'LLM Config',key:'llm',exp:false}"
            + "];"
            + "var secsHtml='';"
            + "sections.forEach(function(s){"
            + "var val=data[s.key];"
            + "if(s.key==='modules'){"
            + "secsHtml+='<div class=\"dv-card\" id=\"sec-Modules\">'+"
            + "'<div class=\"dv-card-hd\" onclick=\"toggleCard(\\' sec-Modules\\')\"><i class=\"bi bi-puzzle\"></i><span class=\"dv-card-title\">Modules</span><i class=\"bi bi-chevron-down dv-card-toggle\"></i></div>'+"
            + "'<div class=\"dv-card-body\">'+renderModules(val)+'</div></div>';"
            + "}else{secsHtml+=renderSection(s.icon,s.title,val,s.exp);}"
            + "});"
            + "if(data.files&&data.files.length)secsHtml+=renderFilesSection(data.files);"
            + "document.getElementById('dv-sections').innerHTML=secsHtml;"
            + "if(typeof hljs!=='undefined')hljs.highlightAll();"
            + "renderAnalysis(data.analysis,analysisStatus);"
            + "}"

            // ── Poll logic

            + "function startPolling(keyB64url){"
            + "pollingTimer=setInterval(async function(){"
            + "try{"
            + "var result=await fetchPayload();"
            + "var status=result.analysis_status;"
            + "if(result.encrypted_payload&&result.encrypted_payload!==lastPayloadB64){"
            + "lastPayloadB64=result.encrypted_payload;"
            + "var json=await aesGcmDecrypt(result.encrypted_payload,keyB64url);"
            + "var data=JSON.parse(json);"
            + "render(data,status);"
            + "}"
            + "if(status==='done'||status==='failed'||status==='skipped'){"
            + "clearInterval(pollingTimer);"
            + "}"
            + "}catch(e){}"
            + "},POLL_INTERVAL);"
            + "}"

            // ── Bootstrap

            + "window.toggleCard=function(id){"
            + "var el=document.getElementById(id);"
            + "if(el)el.classList.toggle('dv-collapsed');"
            + "};"

            + "window.copyUrl=function(){"
            + "var url=window.location.href;"
            + "var toast=document.createElement('div');"
            + "toast.className='dv-copy-toast';"
            + "toast.textContent='Share URL copied!';"
            + "document.body.appendChild(toast);"
            + "requestAnimationFrame(function(){toast.classList.add('show');});"
            + "setTimeout(function(){toast.classList.remove('show');setTimeout(function(){if(toast.parentNode)toast.parentNode.removeChild(toast);},300);},2500);"
            + "if(navigator.clipboard&&window.isSecureContext){"
            + "navigator.clipboard.writeText(url).catch(function(){});"
            + "}else{"
            + "var t=document.createElement('textarea');t.value=url;t.style.cssText='position:fixed;opacity:0;';"
            + "document.body.appendChild(t);t.focus();t.select();"
            + "try{document.execCommand('copy');}catch(_){}"
            + "document.body.removeChild(t);"
            + "}"
            + "};"

            + "async function init(){"
            + "var keyB64url=keyFromHash();"
            + "if(!keyB64url){"
            + "showError('No decryption key','This URL is incomplete. Use the full share link that was generated when the report was created. The key is in the part after the # character.');"
            + "return;"
            + "}"
            + "if(!window.crypto||!window.crypto.subtle){"
            + "showError('Browser not supported','This browser does not support the Web Crypto API required to decrypt this report. Please use a modern browser.');"
            + "return;"
            + "}"
            + "try{"
            + "document.getElementById('dv-status').textContent='Fetching encrypted payload…';"
            + "var result=await fetchPayload();"
            + "lastPayloadB64=result.encrypted_payload;"
            + "document.getElementById('dv-status').textContent='Decrypting…';"
            + "var json=await aesGcmDecrypt(result.encrypted_payload,keyB64url);"
            + "var data=JSON.parse(json);"
            + "render(data,result.analysis_status);"
            + "hideLoding();"
            + "if(result.analysis_status==='pending')startPolling(keyB64url);"
            + "}catch(e){"
            + "if(e.name==='OperationError'||e.message&&e.message.includes('decrypt')){"
            + "showError('Decryption failed','The key in this URL does not match this report. Make sure you are using the exact share URL that was generated.');"
            + "}else{"
            + "showError('Failed to load report',e.message||String(e));"
            + "}"
            + "}"
            + "}"

            + "init();"
            + "})();";

    private static String escJs(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
