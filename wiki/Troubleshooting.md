# Troubleshooting

The common failure modes and what to do about them.

---

## "OAuth state invalid or expired"

Your `public_url` likely does not match what your browser sees as the host. Common mismatches:

- `localhost` versus `127.0.0.1`
- LAN IP versus DNS hostname
- Plain HTTP versus HTTPS after enabling TLS
- Trailing slash in the Discord Developer Portal that is not in `public_url`

State is HMAC-signed and self-validating, so the other way to see this is if more than 10 minutes passed between starting OAuth and returning, or the bot restarted in between.

**Fix**: set `public_url` to exactly the URL your browser shows in the address bar, and set the OAuth redirect in the Discord Developer Portal to `<that url>/auth/discord/callback`.

---

## "You are signed in, but you are not a mod" / locked out of `/dash/config`

Your session cookie predates a role change. Sessions are signed with a snapshot of your role state at sign-in time; promoting yourself in Discord does not retroactively update the cookie.

**Fix**: log out (`/auth/logout`) and sign in again. The new login reads your current role / owner / admin status.

If you are still locked out (e.g. you accidentally removed yourself from the Config admin role and you are not the server Owner), have the server Owner re-grant the role; Owner access is always unconditional.

---

## Plugin remapper lock error on restart

```
Could not load plugin: java.io.IOException: Failed to lock plugins/.paper-remapped/warden-*.jar
```

The previous JVM did not shut down cleanly and is still holding the file open.

**Fix**: kill any leftover `java.exe` (Windows) or `java` (Linux/macOS) process; then start the server again.

`/stop` in the console always shuts down cleanly. `Ctrl-C` on Windows is rough on the JVM; use `/stop` whenever you can.

---

## JDA "Using fallback logger" warning

```
Using fallback logger because SLF4J binding could not be loaded.
```

Cosmetic. SLF4J's ServiceLoader binding does not survive Warden's shading config. The bot works fine. Will be fixed when shading rules are tightened.

---

## Hero / brand image not updating after a config change

`plugins/Warden/www/landing.html` is unpacked once and never overwritten. If you have upgraded from an older Warden, your template might predate the `{{brand_image_url}}` / `{{hero_image_block}}` placeholders.

**Fix**: delete `plugins/Warden/www/landing.html` (and `landing.css` if styling looks off) and restart. Warden re-unpacks the fresh defaults. Your saved Landing-tab config (server name, accent colour, hero URL, etc.) is preserved because it lives in the database, not the file.

Alternatively, open `landing.html` and add the placeholders manually. The Template fields reference panel on the Landing tab lists every supported `{{...}}` token.

---

## "Bot token used by another connection"

You have started two Warden processes (or another bot, e.g. DiscordSRV) with the same bot token. Discord allows only one gateway connection per token.

**Fix**: stop the duplicate. If DiscordSRV is using the same token, give Warden its own dedicated bot user; one token per process is the only supported configuration.

---

## OAuth callback works in browser but `/dash/*` redirects to `/`

This happens when cookies are blocked. Discord OAuth completes, sets the session cookie, redirects, and on the next request the cookie is missing.

**Fix**: ensure your browser allows cookies for `public_url`. If you are using a privacy-aggressive browser (Brave, Firefox strict mode), whitelist the host.

If you are accessing the dashboard over HTTPS but `public_url` is set to `http://`, the session cookie is sent without `Secure` and is dropped by HTTPS browsers. Fix: align `public_url` with the actual scheme.

---

## "GeoIP not configured" on /dash/stats/geo

`analytics.geoip.enabled` is false, or the licence key is missing, or the MaxMind download failed.

**Fix**: see [Configuration Reference](Configuration-Reference.md) for the `analytics.geoip.*` keys. Sign up free at [maxmind.com/en/geolite2/signup](https://www.maxmind.com/en/geolite2/signup) for a licence key. Reload the config; the first download happens within ~30 seconds.

---

## Bot connected, members joining, nothing happens

Delivery is off. Warden ships in silent mode and stays silent until at least one delivery method is enabled.

**Fix**: `/dash/config` -> **Delivery** tab. Turn on DM or public channel post, save. Next joiner gets the flow. See [First-Run Wizard](First-Run-Wizard.md).

---

## More

If you hit something this page does not cover, open an issue at the GitHub repo with `/warden status` output and the last 200 lines of `logs/latest.log`.
