# Rate Limits

The public-facing routes are rate-limited per IP to prevent abuse. Dashboard routes (behind Discord OAuth) are not rate-limited beyond what the OAuth flow itself imposes.

---

## Per-route limits

| Route | Limit |
|---|---|
| `/onboard/redeem` (code paste) | **8 per minute** |
| `/onboard/*` POSTs overall | **20 per minute** |
| `/auth/*` (OAuth start + callback) | **30 per minute** |

Exceeding any returns HTTP 429 with a 60-second `Retry-After` header. Browsers and the onboarding UI handle 429 gracefully; the member sees a "please wait a moment" message rather than a hard error.

---

## Why these numbers

- `/onboard/redeem` is the only brute-forceable surface (an 8-char code paired with a guest cookie). Eight tries per minute is enough for an honest user fat-fingering the code, low enough to make brute-force impractical.
- `/onboard/*` overall is generous enough for the rules + N questions flow. Twenty POSTs per minute covers even a 20-question form completed at speed.
- `/auth/*` covers OAuth start and callback. Thirty per minute is generous; spam on this route is harmless beyond Discord's own rate limits.

---

## IP source

The limiter reads the first IP from `X-Forwarded-For` if Warden is behind a reverse proxy you trust. If you front Warden with nginx / Caddy / Cloudflare, ensure the proxy sets `X-Forwarded-For` correctly; otherwise every request appears to come from `127.0.0.1` and you effectively have a single bucket for everyone.

For untrusted proxy setups, the fallback is the direct socket remote address.

---

## Tuning

The limits are constants in `RateLimiter.java` and not exposed in the dashboard. If you need to raise them (e.g. for a load test), edit the file, rebuild, and restart. PRs welcome to make them configurable.
