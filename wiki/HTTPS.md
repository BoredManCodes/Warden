# HTTPS

Warden can terminate TLS natively, no reverse proxy required. This page is the short version. The dashboard ships an interactive walkthrough at `/dash/https` (Config-admin only) covering both Linux (certbot) and Windows (win-acme), see [Dashboard HTTPS](Dashboard-HTTPS.md).

---

## Why HTTPS

The first-run setup will work fine over plain HTTP for local testing. Once you are inviting real members from Discord, they will see the dashboard URL in DMs and OAuth consent dialogs; serving that over HTTPS prevents anyone on the network path from observing or tampering with session cookies, onboarding answers, or OAuth codes.

Discord also enforces HTTPS on OAuth redirect URLs for production-grade applications. The login flow continues to work over `http://localhost`, but not over `http://` to a public hostname.

---

## Native TLS

Point Warden at a pair of PEM files and flip a flag:

```yaml
web:
  bind_host: "0.0.0.0"
  bind_port: 8788
  public_url: "https://warden.example.com:8443"
  ssl:
    enabled: true
    port: 8443
    cert_file: "/etc/letsencrypt/live/warden.example.com/fullchain.pem"
    key_file: "/etc/letsencrypt/live/warden.example.com/privkey.pem"
    redirect_http: true
```

After restart, Warden listens on both ports. Plain HTTP requests on `bind_port` are 301'd to `public_url`. The Discord Developer Portal redirect URL must be updated to match, otherwise OAuth will fail.

---

## Getting a free Let's Encrypt cert

### Linux: certbot

The standalone certbot plugin temporarily occupies port 80 to prove control of the domain:

```sh
sudo certbot certonly --standalone -d warden.example.com
```

The output gives you the paths to `fullchain.pem` and `privkey.pem`. Paste them into `config.yml`.

For renewal, certbot installs a cron / systemd timer automatically. Each renewal writes new PEM files. Warden picks up the new cert on its next restart, so add a hook to bounce the Paper server (or just live with the 90-day cert and bounce it manually).

### Windows: win-acme

Run [win-acme](https://www.win-acme.com/) interactively. Pick:

- Create new certificate (simple for IIS)
- Manual input -> enter the hostname
- Single certificate (not SAN)
- Save as PEM (`.pem`)

The output directory contains `fullchain.pem` and `privkey.pem`. win-acme installs a scheduled task for renewal.

---

## ACME without port 80

If your ISP blocks inbound 80 (CGNAT, residential, locked-down VPS), the HTTP-01 challenge cannot work. Two alternatives exist.

### DNS-01 challenge

The ACME client proves control by writing a `_acme-challenge.<domain>` TXT record. No inbound ports are needed at all, it works behind NAT, and it is the only method that supports wildcard certs. The DNS provider must expose an API so the client can automate the record.

certbot example with Cloudflare:

```sh
sudo apt install python3-certbot-dns-cloudflare
echo "dns_cloudflare_api_token = <token>" | sudo tee /etc/letsencrypt/cloudflare.ini
sudo chmod 600 /etc/letsencrypt/cloudflare.ini
sudo certbot certonly \
  --dns-cloudflare \
  --dns-cloudflare-credentials /etc/letsencrypt/cloudflare.ini \
  -d warden.example.com
```

Plugins exist for Route53, DigitalOcean, Gandi, Namecheap, Hetzner and many others. On Windows, win-acme ships DNS plugins under *Create renewal (full options)* &rarr; *DNS validation*.

If you do not want to give the renewal box keys to your main DNS account, delegate just `_acme-challenge.warden.example.com` via a CNAME to a separate zone (or to an [acme-dns](https://github.com/joohoi/acme-dns) instance). The main zone keys never leave wherever you keep them.

### TLS-ALPN-01 challenge

Uses port 443 only. The ACME server connects on 443 with ALPN protocol `acme-tls/1` and the client answers with a one-shot self-signed cert containing the token. Useful when 443 is open but 80 is not.

certbot:

```sh
sudo certbot certonly --standalone \
  --preferred-challenges tls-alpn-01 \
  --tls-alpn-01-port 443 \
  -d warden.example.com
```

Nothing else can be bound to 443 while the challenge runs, so stop Warden first (or use a different port and forward 443 to it on the router only during issuance). win-acme exposes this as the *[tls-alpn-01]* validation mode in *full options*.

For everyday home-server use, DNS-01 is the lower-friction choice: it does not collide with the running plugin and renews silently in the background.

---

## Port 80 and 443 on Linux

Binding to ports below 1024 requires root. The easiest options:

- Run Paper as root (not recommended)
- Give the Java binary the `CAP_NET_BIND_SERVICE` capability: `sudo setcap 'cap_net_bind_service=+ep' /path/to/java`
- Use unprivileged ports (e.g. `8443`) and put a `:8443` in `public_url`
- Front Warden with nginx / Caddy / Cloudflare Tunnel (see below)

---

## Reverse-proxy alternative

If you already run nginx / Caddy / Cloudflare Tunnel, leave `web.ssl.enabled` off, keep Warden on plain HTTP bound to `127.0.0.1`, and point `public_url` at the HTTPS hostname your proxy serves. A one-line Caddyfile is enough:

```
warden.example.com {
    reverse_proxy 127.0.0.1:8788
}
```

---

## After enabling HTTPS

Update the **OAuth2 redirect** in the Discord Developer Portal:

```
https://warden.example.com:8443/auth/discord/callback
```

Without this update, sign-in fails with "OAuth state invalid or expired" or Discord refuses to redirect back. See [Troubleshooting](Troubleshooting.md) for related issues.
