package io.warden.web.handlers;

import io.javalin.http.Context;

/**
 * /dash/about - credits, license and donation info. Static page, no
 * session-specific data beyond the chrome rendered by {@link Layout}.
 */
public final class DashAboutHandlers {

    private static final String VENDOR_NAME = "Border Tech Solutions";
    private static final String VENDOR_URL  = "https://bordertechsolutions.com.au";
    private static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";
    private static final String DSRV_URL    = "https://github.com/DiscordSRV/DiscordSRV";
    private static final String DONATE_URL  = "https://donate.stripe.com/9B65kE1zZdJf08d51S14400";

    public void page(Context ctx) {
        StringBuilder h = new StringBuilder(2048);
        h.append(Layout.head("About · Warden", "about", ctx));

        h.append("<div class=\"row justify-content-center\">")
                .append("<div class=\"col-lg-9 col-xl-8\">");

        h.append("<h1 class=\"h3 mb-1\">About Warden</h1>")
                .append("<p class=\"text-secondary mb-4\">")
                .append("A Discord onboarding and moderation plugin for Paper Minecraft servers.")
                .append("</p>");

        // Built by
        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-building me-1\"></i> Built by")
                .append("</h2>")
                .append("<p class=\"mb-0\">Warden is built and maintained by ")
                .append("<a href=\"").append(VENDOR_URL).append("\" target=\"_blank\" rel=\"noopener\">")
                .append(VENDOR_NAME).append("</a>, ")
                .append("an IT services and software studio based on the NSW/VIC border in Australia.")
                .append("</p>")
                .append("</div></div>");

        // License
        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-file-earmark-text me-1\"></i> License")
                .append("</h2>")
                .append("<p class=\"mb-0\">Released under the ")
                .append("<a href=\"").append(LICENSE_URL).append("\" target=\"_blank\" rel=\"noopener\">")
                .append("Apache License, Version 2.0</a>. ")
                .append("You're free to use, modify and redistribute it, provided the license terms are honoured.")
                .append("</p>")
                .append("</div></div>");

        // Credits
        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-heart me-1\"></i> Credits and thanks")
                .append("</h2>")
                .append("<ul class=\"mb-0 ps-3\">")
                .append("<li>Many thanks to the <a href=\"").append(DSRV_URL)
                .append("\" target=\"_blank\" rel=\"noopener\">DiscordSRV</a> team, ")
                .append("whose groundwork on the alerts engine informed Warden's own alert pipeline ")
                .append("and import compatibility.</li>")
                .append("<li>Built on top of <a href=\"https://papermc.io\" target=\"_blank\" rel=\"noopener\">Paper</a>, ")
                .append("<a href=\"https://jda.wiki\" target=\"_blank\" rel=\"noopener\">JDA</a>, and ")
                .append("<a href=\"https://javalin.io\" target=\"_blank\" rel=\"noopener\">Javalin</a>.</li>")
                .append("<li>Dashboard chrome courtesy of ")
                .append("<a href=\"https://adminlte.io\" target=\"_blank\" rel=\"noopener\">AdminLTE</a> and ")
                .append("<a href=\"https://getbootstrap.com\" target=\"_blank\" rel=\"noopener\">Bootstrap</a>.</li>")
                .append("</ul>")
                .append("</div></div>");

        // Support
        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-cup-hot me-1\"></i> Support development")
                .append("</h2>")
                .append("<p class=\"mb-3\">Warden is free and open source. If it saves you time, ")
                .append("a small contribution helps fund ongoing development and hosting for the demo and docs.</p>")
                .append("<a href=\"").append(DONATE_URL).append("\" target=\"_blank\" rel=\"noopener\" ")
                .append("class=\"btn btn-primary\">")
                .append("<i class=\"bi bi-heart-fill me-1\"></i> Support development")
                .append("</a>")
                .append("</div></div>");

        h.append("</div></div>");
        h.append(Layout.foot());
        ctx.html(h.toString());
    }
}
