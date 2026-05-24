# Third-party licenses

Warden is distributed as a fat JAR that bundles (shades) the following
open-source libraries. Their copyrights remain with their respective
authors; their licenses apply to the bundled bytecode.

The list is grouped by license. A short note next to each entry says
what Warden uses the library for.

For Warden's own code, see `LICENSE` (Apache License, Version 2.0).

---

## Apache License, Version 2.0

Full license text: <https://www.apache.org/licenses/LICENSE-2.0>

| Library | Version | Source | Used for |
| --- | --- | --- | --- |
| JDA (Java Discord API) | 5.1.2 | <https://github.com/discord-jda/JDA> | Discord bot client (gateway, REST, slash commands, voice events) |
| Javalin | 6.3.0 | <https://github.com/javalin/javalin> | Embedded HTTP server for the dashboard, onboarding flow, and public landing |
| Eclipse Jetty (server, util, http, io, security, servlet, websocket) | 11.x (via Javalin) | <https://www.eclipse.org/jetty/> | HTTP/WebSocket server backing Javalin (dual-licensed Apache 2.0 / EPL 1.0; we elect Apache 2.0) |
| Jackson (databind, core, annotations) | 2.18.1 | <https://github.com/FasterXML/jackson> | JSON serialisation throughout (settings, audit payloads, JDA bodies) |
| HikariCP | 5.1.0 | <https://github.com/brettwooldridge/HikariCP> | SQLite connection pool |
| SQLite JDBC | 3.46.1.3 | <https://github.com/xerial/sqlite-jdbc> | Embedded SQLite driver (data file at `plugins/Warden/data/warden.db`) |
| MaxMind GeoIP2 | 4.2.1 | <https://github.com/maxmind/GeoIP2-java> | Country lookup for the optional Geography analytics page (operator-provided GeoLite2 database) |
| Spring Expression (SpEL) + Spring Core | 5.3.39 | <https://github.com/spring-projects/spring-framework> | DSRV-compatible `{event.player.name}` templating and condition evaluation for Alerts |
| SnakeYAML | 2.3 | <https://bitbucket.org/snakeyaml/snakeyaml> | Parsing pasted DiscordSRV `alerts.yml` blobs in the importer |
| JTE | 3.1.13 | <https://github.com/casid/jte> | Server-side template engine (kept on the classpath for upcoming dashboard refactor) |
| OkHttp + Okio | (transitive via JDA) | <https://square.github.io/okhttp/> | HTTP transport for JDA |
| nv-websocket-client | (transitive via JDA) | <https://github.com/TakahikoKawasaki/nv-websocket-client> | WebSocket transport for JDA gateway |

Each Apache 2.0 dependency may ship its own `NOTICE` file inside its
JAR. Where present, those notices are preserved by the shadow plugin's
`mergeServiceFiles()` configuration and travel with the bundled
classes.

---

## MIT License

| Library | Version | Source | Used for |
| --- | --- | --- | --- |
| SLF4J API + slf4j-jdk14 | 2.0.16 | <https://www.slf4j.org/> | Logging bridge: Javalin/JDA/Jetty log via SLF4J, slf4j-jdk14 funnels everything into `java.util.logging` so the lines land in the Paper server log |

---

## Build- and test-time only (not bundled)

These appear in `build.gradle.kts` but are not shaded into the
distributed plugin JAR, so no notice is strictly required. Listed for
completeness.

| Library | Version | License | Source |
| --- | --- | --- | --- |
| Paper API | 1.21.1-R0.1-SNAPSHOT | GPL-3.0 | <https://papermc.io/> |
| JUnit Jupiter / JUnit Platform Launcher | 5.11.3 | EPL-2.0 | <https://junit.org/junit5/> |

`compileOnly` and `testImplementation` artifacts do not ship in the
plugin JAR. Paper itself is supplied by the server operator's
installation.

---

## Loaded from CDN at runtime (not bundled)

The dashboard pages link these from public CDNs. They are not bundled
inside the plugin JAR and are not redistributed by us; users' browsers
fetch them directly from jsDelivr.

| Asset | Version | License | Source |
| --- | --- | --- | --- |
| Bootstrap | 5.3.8 | MIT | <https://getbootstrap.com/> |
| Bootstrap Icons | 1.13.1 | MIT | <https://icons.getbootstrap.com/> |
| AdminLTE | 4.0.0-rc7 | MIT | <https://adminlte.io/> |
| Chart.js | (per-page CDN tags) | MIT | <https://www.chartjs.org/> |

---

## DiscordSRV compatibility (not bundled, no code reused)

Warden integrates with DiscordSRV (<https://github.com/DiscordSRV/DiscordSRV>,
GPL-3.0) two ways: a reflection-only bridge that calls DiscordSRV's
public `AccountLinkManager` API for Minecraft <-> Discord account
linking, and a YAML importer that reads DiscordSRV's `alerts.yml`
file format. No DiscordSRV source code is copied, imported at compile
time, or bundled in this JAR. DiscordSRV is treated as a softdepend in
`plugin.yml`; Warden runs cleanly without it installed.

---

## Reporting an omission

If you spot a library Warden bundles that isn't credited here, please
open an issue. We aim to keep this file in sync with
`build.gradle.kts`.
