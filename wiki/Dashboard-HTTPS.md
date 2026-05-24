# Dashboard: HTTPS Panel

`/dash/https` is an interactive walkthrough for setting up HTTPS. It is a documentation page, not a control surface; the actual config still goes in `config.yml`. Visible to **Config admin**.

See [HTTPS](HTTPS.md) for the standalone written guide. This page mirrors that content with a few extras:

---

## Current state panel

At the top, Warden shows what it can detect about your current HTTPS state:

- Whether `web.ssl.enabled` is true
- Whether the configured cert and key files exist and are readable
- The cert expiry date (parsed from the PEM)
- Days until expiry, with a colour-coded chip (green > 30, amber 7-30, red < 7)
- Whether `public_url` uses `https://`

---

## Walkthrough

A step-by-step OS-aware guide:

- **Linux**: certbot install, standalone challenge, renewal cron / systemd timer
- **Windows**: win-acme install, interactive cert issue, scheduled task
- **macOS** and other Unix: generic Let's Encrypt / certbot path

Each step has copy-paste commands and a "next" button to walk through linearly.

---

## No port 80? (DNS-01 / TLS-ALPN-01)

A dedicated card covers the CGNAT / blocked-80 case. The HTTP-01 challenge needs an inbound listener on port 80, which residential ISPs and some VPS providers block. Two ACME challenge types work without it:

- **DNS-01**: the client proves control by writing a `_acme-challenge.<domain>` TXT record via the DNS provider's API. No inbound ports needed; works behind NAT; supports wildcards. Copy-paste examples are shown for certbot's Cloudflare plugin and for the win-acme DNS validation flow. CNAME-delegation to [acme-dns](https://github.com/joohoi/acme-dns) is mentioned for users who do not want renewal-host keys to touch their main DNS zone.
- **TLS-ALPN-01**: uses port 443 only, via the `acme-tls/1` ALPN protocol. Useful when 443 is open but 80 is not. The card notes that the challenge briefly takes over 443, so Warden has to stop or move during issuance.

See [HTTPS](HTTPS.md#acme-without-port-80) for the full written version.

---

## Reverse-proxy alternative

A collapsible section at the bottom covers the case where you already run nginx, Caddy, or Cloudflare Tunnel and prefer to terminate TLS there. Includes a one-line Caddyfile snippet and a basic nginx config.

---

## Restart prompt

After editing `config.yml` to enable HTTPS, the page reminds you that the change is boot-bound and requires a server restart. There is no "apply now" button; the restart is mandatory and the page links to the `/warden status` command for verifying the new state.
