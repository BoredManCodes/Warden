# Building from Source

Warden builds with Gradle. No system Gradle is required; the Gradle wrapper handles everything. Java 21 toolchain only; no Node, no Python, no Maven, no other system tooling.

```sh
git clone <repo>
cd warden
./gradlew shadowJar
```

Or on Windows:

```powershell
.\gradlew.bat shadowJar
```

The fat JAR lands at `build/libs/warden-<version>.jar`. Drop it into your Paper server's `plugins/` directory.

---

## Common tasks

| Command | What it does |
|---|---|
| `./gradlew shadowJar` | Build the fat JAR with all dependencies shaded under `io.warden.shaded.*` |
| `./gradlew test` | Run the JUnit 5 suite |
| `./gradlew copyToTestServer -PtestServer=/path/to/paper` | Build and copy the JAR to a local test server, replacing any older Warden JAR there |
| `./gradlew clean` | Wipe `build/` |
| `./gradlew shadowJar test` | Build + test in one invocation |

---

## What gets shaded

The shadow plugin relocates every runtime dependency under `io.warden.shaded.*` so Warden can coexist with other plugins that bundle different versions of the same library. Shaded libraries include:

- JDA (Discord)
- Javalin + embedded Jetty
- Jackson (JSON)
- HikariCP (connection pool)
- JTE (templating)
- sqlite-jdbc
- OkHttp (LLM client)
- slf4j-jdk14 (logging bridge)

The Bukkit / Paper API is NOT shaded; it is provided by the server.

---

## Schema

The full SQLite schema lives in `src/main/resources/schema.sql`. `SchemaLoader` runs it once at startup using `CREATE IF NOT EXISTS` and `INSERT OR IGNORE`, so it is safe on both fresh and existing databases. To add a column or table, edit `schema.sql` directly.

---

## IDE setup

The project imports cleanly into IntelliJ IDEA as a Gradle project. No special setup required: open the directory, accept the Gradle wrapper prompt, wait for the import to finish. The Java 21 toolchain is downloaded automatically by Gradle if your system JDK is older.

VS Code with the Java extension pack works too; the Gradle integration is automatic.

---

## Running locally

The fastest dev loop is:

```sh
./gradlew shadowJar copyToTestServer -PtestServer=/path/to/your/local/paper/server
```

Then restart your local Paper server. The Discord bot will connect with whatever token is in your test server's `plugins/Warden/config.yml`; use a throwaway dev application against a private guild.

For Discord OAuth in local dev, set `web.public_url` to `http://localhost:8788` and configure the matching OAuth redirect URL in the Discord Developer Portal.
