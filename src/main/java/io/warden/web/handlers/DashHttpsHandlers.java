package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.config.WardenConfig;
import io.warden.web.WebService;
import io.warden.web.ssl.SslSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.Optional;

/**
 * /dash/https - read-only status panel + step-by-step walkthrough for getting
 * a real TLS certificate without a reverse proxy. The page never writes config
 * itself: changes go through plugins/Warden/config.yml plus a server restart,
 * because the Jetty connector binds at boot.
 *
 * Gated to Config admin in WebService (canEditConfig).
 */
public final class DashHttpsHandlers {

    private final WardenConfig config;
    private final WebService web;

    public DashHttpsHandlers(WardenConfig config, WebService web) {
        this.config = config;
        this.web = web;
    }

    public void page(Context ctx) {
        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("HTTPS · Warden", "https", ctx));

        h.append("<div class=\"row justify-content-center\">")
                .append("<div class=\"col-lg-10 col-xl-9\">");

        h.append("<h1 class=\"h3 mb-1\">HTTPS</h1>")
                .append("<p class=\"text-secondary mb-4\">")
                .append("Serve the dashboard, onboarding and landing pages over TLS straight from this plugin. ")
                .append("No reverse proxy required.")
                .append("</p>");

        renderStatusCard(h);
        renderConfigCard(h);
        renderWalkthroughLinux(h);
        renderWalkthroughWindows(h);
        renderNoPort80(h);
        renderRestartNote(h);

        h.append("</div></div>");
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private void renderStatusCard(StringBuilder h) {
        var sslCfg = config.ssl();
        Optional<SslSupport.CertSummary> cert = SslSupport.inspect(sslCfg.certFile());
        boolean active = web.sslActive();
        String loadError = web.sslLoadError();

        String headerIcon;
        String headerText;
        String headerClass;
        if (active && cert.isPresent() && !cert.get().expired()) {
            long days = cert.get().daysUntilExpiry();
            headerIcon = "bi-shield-check";
            headerText = "HTTPS is active. Certificate expires in " + days + " day"
                    + (days == 1 ? "" : "s") + ".";
            headerClass = days < 14 ? "text-warning" : "text-success";
        } else if (active && cert.isPresent() && cert.get().expired()) {
            headerIcon = "bi-shield-exclamation";
            headerText = "HTTPS is active but the certificate has expired. Renew it and restart the server.";
            headerClass = "text-danger";
        } else if (sslCfg.enabled() && loadError != null) {
            headerIcon = "bi-shield-x";
            headerText = "HTTPS is enabled in config.yml but failed to load: " + loadError;
            headerClass = "text-danger";
        } else if (sslCfg.enabled()) {
            headerIcon = "bi-shield-slash";
            headerText = "HTTPS is enabled in config.yml but the certificate files weren't found. "
                    + "Follow the walkthrough below.";
            headerClass = "text-warning";
        } else {
            headerIcon = "bi-shield";
            headerText = "HTTPS is off. Set web.ssl.enabled to true in config.yml after completing the walkthrough.";
            headerClass = "text-secondary";
        }

        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-activity me-1\"></i> Current status")
                .append("</h2>")
                .append("<p class=\"mb-3 ").append(headerClass).append("\">")
                .append("<i class=\"bi ").append(headerIcon).append(" me-1\"></i> ")
                .append(Layout.escape(headerText))
                .append("</p>");

        if (cert.isPresent()) {
            SslSupport.CertSummary c = cert.get();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                    .withZone(ZoneId.of("UTC"));
            h.append("<dl class=\"row mb-0 small\">");
            row(h, "Subject", prettyDn(c.subject()));
            row(h, "Issuer",  prettyDn(c.issuer()));
            row(h, "Valid from", fmt.format(c.notBefore()));
            row(h, "Valid until", fmt.format(c.notAfter()));
            row(h, "Chain length", c.chainLength() + " certificate" + (c.chainLength() == 1 ? "" : "s"));
            row(h, "Signature alg", c.sigAlg());
            h.append("</dl>");
        } else if (sslCfg.enabled()) {
            h.append("<p class=\"small text-secondary mb-0\">")
                    .append("No certificate could be read at ")
                    .append("<code>").append(Layout.escape(sslCfg.certFile().toString())).append("</code>.")
                    .append("</p>");
        }

        h.append("</div></div>");
    }

    private void renderConfigCard(StringBuilder h) {
        var sslCfg = config.ssl();
        boolean certExists = Files.exists(sslCfg.certFile());
        boolean keyExists  = Files.exists(sslCfg.keyFile());

        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-sliders me-1\"></i> Config values in use")
                .append("</h2>")
                .append("<p class=\"text-secondary small\">")
                .append("Read from <code>plugins/Warden/config.yml</code>. Edit there and restart the server to apply.")
                .append("</p>")
                .append("<dl class=\"row mb-0 small\">");
        row(h, "web.ssl.enabled", boolBadge(sslCfg.enabled()));
        row(h, "web.ssl.port", String.valueOf(sslCfg.port()));
        row(h, "web.ssl.cert_file",
                "<code>" + Layout.escape(sslCfg.certFile().toString()) + "</code> "
                + fileBadge(certExists));
        row(h, "web.ssl.key_file",
                "<code>" + Layout.escape(sslCfg.keyFile().toString()) + "</code> "
                + fileBadge(keyExists));
        row(h, "web.ssl.redirect_http", boolBadge(sslCfg.redirectHttp())
                + " <span class=\"text-secondary\">(plain HTTP requests "
                + (sslCfg.redirectHttp() ? "301 to public_url" : "are served as-is")
                + ")</span>");
        row(h, "web.public_url",
                "<code>" + Layout.escape(config.webPublicUrl()) + "</code>"
                + (config.webPublicUrl().startsWith("https://") ? ""
                    : " <span class=\"badge bg-warning text-dark\">should start with https:// when SSL is on</span>"));
        h.append("</dl>")
                .append("</div></div>");
    }

    private void renderWalkthroughLinux(StringBuilder h) {
        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-ubuntu me-1\"></i> Linux walkthrough (Let's Encrypt + certbot)")
                .append("</h2>")
                .append("<p class=\"text-secondary small\">")
                .append("Free, automated, and renews itself. Requires a DNS A or AAAA record pointing at this server, ")
                .append("and port 80 reachable from the public internet for the renewal check.")
                .append("</p>")
                .append("<ol class=\"small\">")
                .append("<li><strong>Point DNS at the server.</strong> Create an A record like ")
                .append("<code>warden.example.com</code> &rarr; this server's public IPv4.</li>")
                .append("<li><strong>Open ports.</strong> Make sure <code>80/tcp</code> and your ")
                .append("<code>web.ssl.port</code> (default <code>8443</code>) are reachable. ")
                .append("Port 80 is only needed during initial issuance and the renewal hook.</li>")
                .append("<li><strong>Install certbot.</strong> ")
                .append("<code>sudo apt install certbot</code> on Debian/Ubuntu, or follow the official guide.</li>")
                .append("<li><strong>Issue a cert (standalone mode).</strong> Stops nothing on Warden's side ")
                .append("but binds port 80 briefly:<br>")
                .append("<pre class=\"bg-body-tertiary p-2 rounded mt-1 mb-2\"><code>sudo certbot certonly --standalone -d warden.example.com</code></pre>")
                .append("</li>")
                .append("<li><strong>Point Warden at the new files.</strong> Edit ")
                .append("<code>plugins/Warden/config.yml</code>:<br>")
                .append("<pre class=\"bg-body-tertiary p-2 rounded mt-1 mb-2\"><code>web:\n")
                .append("  public_url: \"https://warden.example.com:8443\"\n")
                .append("  ssl:\n")
                .append("    enabled: true\n")
                .append("    port: 8443\n")
                .append("    cert_file: \"/etc/letsencrypt/live/warden.example.com/fullchain.pem\"\n")
                .append("    key_file:  \"/etc/letsencrypt/live/warden.example.com/privkey.pem\"</code></pre>")
                .append("Use absolute paths so the symlinks certbot maintains keep working across renewals.</li>")
                .append("<li><strong>Update the Discord OAuth redirect.</strong> Developer Portal &rarr; OAuth2 ")
                .append("&rarr; Redirects &rarr; ")
                .append("<code>https://warden.example.com:8443/auth/discord/callback</code>.</li>")
                .append("<li><strong>Restart the server.</strong> The HTTPS connector binds at boot. ")
                .append("Visit your public URL and check the lock icon.</li>")
                .append("<li><strong>Auto-renewal.</strong> certbot installs a systemd timer that renews every 60 days. ")
                .append("Add a deploy hook that restarts the Minecraft server so Warden picks up the new files:<br>")
                .append("<pre class=\"bg-body-tertiary p-2 rounded mt-1 mb-0\"><code>echo 'systemctl restart minecraft' | \\\n")
                .append("  sudo tee /etc/letsencrypt/renewal-hooks/deploy/restart-minecraft.sh\n")
                .append("sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/restart-minecraft.sh</code></pre>")
                .append("Replace <code>minecraft</code> with whatever your unit is called.</li>")
                .append("</ol>")
                .append("</div></div>");
    }

    private void renderWalkthroughWindows(StringBuilder h) {
        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-windows me-1\"></i> Windows walkthrough (Let's Encrypt + win-acme)")
                .append("</h2>")
                .append("<p class=\"text-secondary small\">")
                .append("Same flow as Linux, different tool. ")
                .append("<a href=\"https://www.win-acme.com\" target=\"_blank\" rel=\"noopener\">win-acme</a> ")
                .append("is the standard ACME client for Windows.")
                .append("</p>")
                .append("<ol class=\"small\">")
                .append("<li><strong>Point DNS at the server.</strong> Same as Linux above.</li>")
                .append("<li><strong>Open ports.</strong> <code>80/tcp</code> for issuance + renewal, ")
                .append("plus your <code>web.ssl.port</code>.</li>")
                .append("<li><strong>Install win-acme.</strong> Download the pluggable .zip from win-acme.com ")
                .append("and unzip it somewhere stable (e.g. <code>C:\\Tools\\win-acme</code>).</li>")
                .append("<li><strong>Issue a cert.</strong> Open an elevated PowerShell in that folder and run ")
                .append("<code>.\\wacs.exe</code>. Choose <em>Create renewal (full options)</em>, ")
                .append("<em>Manual input</em>, type your hostname, ")
                .append("<em>[http-01] Serve verification files from memory</em>, ")
                .append("then <em>PEM encoded files (Apache, nginx, etc.)</em> for the store and pick an output folder ")
                .append("such as <code>C:\\ssl\\warden</code>. The default schedule runs daily and renews when needed.</li>")
                .append("<li><strong>Point Warden at the files.</strong> Edit ")
                .append("<code>plugins\\Warden\\config.yml</code>:<br>")
                .append("<pre class=\"bg-body-tertiary p-2 rounded mt-1 mb-2\"><code>web:\n")
                .append("  public_url: \"https://warden.example.com:8443\"\n")
                .append("  ssl:\n")
                .append("    enabled: true\n")
                .append("    port: 8443\n")
                .append("    cert_file: \"C:/ssl/warden/warden.example.com-chain.pem\"\n")
                .append("    key_file:  \"C:/ssl/warden/warden.example.com-key.pem\"</code></pre>")
                .append("Use forward slashes in the YAML or escape backslashes - both work.</li>")
                .append("<li><strong>Update the Discord OAuth redirect</strong> the same way as the Linux flow.</li>")
                .append("<li><strong>Restart the server.</strong> Visit your public URL and check the lock icon.</li>")
                .append("<li><strong>Auto-renewal.</strong> win-acme's scheduled task picks up renewals automatically. ")
                .append("Add a post-renewal script that restarts the Minecraft service so Warden re-reads the files - ")
                .append("see the win-acme docs on <em>Scripts</em>.</li>")
                .append("</ol>")
                .append("</div></div>");
    }

    private void renderNoPort80(StringBuilder h) {
        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-router me-1\"></i> No port 80? (CGNAT, blocked ISP, locked VPS)")
                .append("</h2>")
                .append("<p class=\"text-secondary small\">")
                .append("The default HTTP-01 challenge needs a public listener on port 80, which residential ISPs and ")
                .append("some VPS providers block. Two ACME challenge types work without it.")
                .append("</p>")

                .append("<h3 class=\"h6 mb-1\">DNS-01 (recommended)</h3>")
                .append("<p class=\"small text-secondary mb-2\">")
                .append("The client proves control by writing a <code>_acme-challenge.&lt;domain&gt;</code> TXT record ")
                .append("via your DNS provider's API. No inbound ports needed, works behind NAT, and is the only ")
                .append("method that supports wildcard certs.")
                .append("</p>")
                .append("<p class=\"small mb-1\"><strong>Linux (certbot, Cloudflare example):</strong></p>")
                .append("<pre class=\"bg-body-tertiary p-2 rounded mt-1 mb-2\"><code>sudo apt install python3-certbot-dns-cloudflare\n")
                .append("echo \"dns_cloudflare_api_token = &lt;token&gt;\" | sudo tee /etc/letsencrypt/cloudflare.ini\n")
                .append("sudo chmod 600 /etc/letsencrypt/cloudflare.ini\n")
                .append("sudo certbot certonly \\\n")
                .append("  --dns-cloudflare \\\n")
                .append("  --dns-cloudflare-credentials /etc/letsencrypt/cloudflare.ini \\\n")
                .append("  -d warden.example.com</code></pre>")
                .append("<p class=\"small mb-2\">")
                .append("Plugins exist for Route53, DigitalOcean, Gandi, Namecheap, Hetzner and many others. ")
                .append("On Windows, win-acme exposes the same flow under ")
                .append("<em>Create renewal (full options)</em> &rarr; <em>DNS validation</em>.")
                .append("</p>")
                .append("<p class=\"small mb-3\">")
                .append("If you do not want renewal-host keys to touch your main DNS zone, CNAME ")
                .append("<code>_acme-challenge.warden.example.com</code> to a separate zone or to an ")
                .append("<a href=\"https://github.com/joohoi/acme-dns\" target=\"_blank\" rel=\"noopener\">acme-dns</a> ")
                .append("instance and grant the API token only on that.")
                .append("</p>")

                .append("<h3 class=\"h6 mb-1\">TLS-ALPN-01</h3>")
                .append("<p class=\"small text-secondary mb-2\">")
                .append("Uses port 443 only. Let's Encrypt connects with ALPN <code>acme-tls/1</code> and your client ")
                .append("answers with a one-shot self-signed cert containing the token. Useful when 443 is open ")
                .append("but 80 is not.")
                .append("</p>")
                .append("<pre class=\"bg-body-tertiary p-2 rounded mt-1 mb-2\"><code>sudo certbot certonly --standalone \\\n")
                .append("  --preferred-challenges tls-alpn-01 \\\n")
                .append("  --tls-alpn-01-port 443 \\\n")
                .append("  -d warden.example.com</code></pre>")
                .append("<p class=\"small mb-0\">")
                .append("Nothing else can be bound to 443 while the challenge runs, so stop the server first, or ")
                .append("issue against a different port and forward 443 to it on the router only during issuance. ")
                .append("win-acme exposes this as the <em>[tls-alpn-01]</em> validation mode in <em>full options</em>.")
                .append("</p>")
                .append("</div></div>");
    }

    private void renderRestartNote(StringBuilder h) {
        h.append("<div class=\"card mb-3\">")
                .append("<div class=\"card-body\">")
                .append("<h2 class=\"h6 text-uppercase text-secondary mb-2\">")
                .append("<i class=\"bi bi-info-circle me-1\"></i> Notes")
                .append("</h2>")
                .append("<ul class=\"small mb-0\">")
                .append("<li>HTTPS is a boot-time concern. After editing <code>config.yml</code>, ")
                .append("run <code>/stop</code> on the server console and start it again.</li>")
                .append("<li>Ports under 1024 (like 443) need root on Linux. The defaults use 8443 ")
                .append("for that reason. If you want 443, either run the JVM with ")
                .append("<code>setcap 'cap_net_bind_service=+ep' $(readlink -f $(which java))</code> ")
                .append("or front Warden with a tiny port forwarder.</li>")
                .append("<li>Self-signed certs work for testing - generate with ")
                .append("<code>openssl req -x509 -newkey rsa:4096 -keyout privkey.pem -out fullchain.pem ")
                .append("-days 365 -nodes -subj '/CN=localhost'</code> - but browsers warn loudly. ")
                .append("Use a real Let's Encrypt cert in production.</li>")
                .append("<li>Behind a home NAT? Forward your <code>web.ssl.port</code> (and 80 if renewing via the ")
                .append("HTTP challenge) on the router. If 80 is blocked upstream, see the ")
                .append("<em>No port 80?</em> card above for DNS-01 and TLS-ALPN-01.</li>")
                .append("</ul>")
                .append("</div></div>");
    }

    private static void row(StringBuilder h, String label, String htmlValue) {
        h.append("<dt class=\"col-sm-4\">").append(Layout.escape(label)).append("</dt>")
                .append("<dd class=\"col-sm-8\">").append(htmlValue).append("</dd>");
    }

    private static String boolBadge(boolean v) {
        return v ? "<span class=\"badge bg-success\">true</span>"
                 : "<span class=\"badge bg-secondary\">false</span>";
    }

    private static String fileBadge(boolean exists) {
        return exists ? "<span class=\"badge bg-success\">found</span>"
                      : "<span class=\"badge bg-danger\">not found</span>";
    }

    /**
     * X.500 DNs come out as comma-separated key=value pairs. The CN is the
     * interesting bit on a server cert; lift it to the front when present
     * and quietly truncate the rest so the status panel stays compact.
     */
    private static String prettyDn(String dn) {
        if (dn == null || dn.isBlank()) return "";
        String cn = null;
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=")) { cn = p.substring(3); break; }
        }
        if (cn != null) return Layout.escape(cn) + " <span class=\"text-secondary\">(" + Layout.escape(dn) + ")</span>";
        return Layout.escape(dn);
    }
}
