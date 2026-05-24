# Session Secret

Warden signs dashboard and onboarding session cookies with `web.session_secret`. Without it, the OAuth flow cannot validate state, and `/onboard` cannot resume a session across pages.

You almost never need to touch this value. On the first start, Warden generates a random 32-byte hex string and writes it back to `config.yml`. You only need to override it manually if:

- You want to keep secrets out of `config.yml` and supply them via env var
- You are pre-seeding the YAML from a config management tool (Ansible, NixOS, etc.)
- You want all of your Warden installs (test, staging, prod) to sign cookies with the same key
- You suspect the secret has leaked and you want to rotate it

---

## Generating a value yourself

A session secret must be a hex-encoded value with at least 32 bytes of entropy. Pick one:

### Linux / macOS

```sh
openssl rand -hex 32
```

### Windows PowerShell

```powershell
$b = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
($b | ForEach-Object { '{0:x2}' -f $_ }) -join ''
```

### Python

```sh
python -c "import secrets; print(secrets.token_hex(32))"
```

Paste the output into `web.session_secret` in `config.yml`, or set the `WARDEN_SESSION_SECRET` env var (the env var takes precedence over the file).

---

## Rotating

Replace the value, restart the server. All existing sessions are invalidated; everyone needs to sign in again. This is fine and expected for rotation; nothing else breaks.
