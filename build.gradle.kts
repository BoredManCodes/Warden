import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.warden"
version = "1.0.0"
description = "Warden - Discord onboarding bot + web dashboard, packaged as a Paper plugin"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val paperApiVersion = "1.21.1-R0.1-SNAPSHOT"
val jdaVersion = "5.1.2"
val javalinVersion = "6.3.0"
val jteVersion = "3.1.13"
val sqliteJdbcVersion = "3.46.1.3"
val hikariVersion = "5.1.0"
val jacksonVersion = "2.18.1"
val geoip2Version = "4.2.1"
val springExprVersion = "5.3.39"
val snakeYamlVersion = "2.3"
val junitVersion = "5.11.3"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    // Shaded into the plugin jar.
    implementation("net.dv8tion:JDA:$jdaVersion") {
        exclude(module = "opus-java") // we don't need voice
    }
    implementation("io.javalin:javalin:$javalinVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("gg.jte:jte:$jteVersion")
    implementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    // MaxMind GeoLite2 reader for the analytics Geography page. Pulled in as
    // a regular (shaded + relocated) dependency; the .mmdb data file itself
    // is NOT bundled - operators provide their own licence key.
    implementation("com.maxmind.geoip2:geoip2:$geoip2Version")
    // Spring Expression Language: DSRV-compatible {event.player.name} templating and
    // condition evaluation for the Alerts subsystem. spring-core comes along as a
    // transitive dep; both are shaded + relocated below to avoid classpath clashes.
    implementation("org.springframework:spring-expression:$springExprVersion")
    // SnakeYAML: parses pasted DSRV alerts.yml blocks in the importer. Paper ships
    // a copy on the server classpath already, but pulling our own keeps the
    // importer independent of which Paper version the operator is running.
    implementation("org.yaml:snakeyaml:$snakeYamlVersion")
    // bStats: we ship the verbatim single-file Metrics class at
    // io.warden.metrics.Metrics (per bStats' "every plugin must use a unique
    // package" rule). Operators can opt out via metrics.enabled in config.yml,
    // or globally via plugins/bStats/config.yml.
    // Optional GrimAnticheat integration is read-only: GrimBridge opens Grim's
    // own plugins/GrimAC/violations.sqlite directly, so there is no
    // compile-time dependency on GrimAPI. Plugin presence is detected via
    // Bukkit's PluginManager at runtime; softdepend lives in plugin.yml.
    // slf4j-api itself is on Paper's classpath; we only need a provider Javalin/JDA
    // can find via ServiceLoader. slf4j-jdk14 bridges everything into java.util.logging,
    // which Paper's logger already captures, so plugin log output ends up in the
    // server console without an extra appender.
    compileOnly("org.slf4j:slf4j-api:2.0.16")
    implementation("org.slf4j:slf4j-jdk14:2.0.16")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        showStandardStreams = false
    }
}

// Token expansion in plugin.yml so the version stays single-sourced from Gradle.
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("warden")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()

    // Relocate shaded libs so we don't conflict with other plugins (e.g. DiscordSRV).
    val shadeBase = "io.warden.shaded"
    relocate("net.dv8tion.jda", "$shadeBase.jda")
    relocate("io.javalin", "$shadeBase.javalin")
    relocate("org.eclipse.jetty", "$shadeBase.jetty")
    relocate("com.fasterxml.jackson", "$shadeBase.jackson")
    relocate("okhttp3", "$shadeBase.okhttp3")
    relocate("okio", "$shadeBase.okio")
    relocate("com.neovisionaries", "$shadeBase.neovisionaries")
    relocate("com.zaxxer.hikari", "$shadeBase.hikari")
    relocate("gg.jte", "$shadeBase.jte")
    relocate("org.xerial.sqlite", "$shadeBase.sqlite")
    relocate("com.maxmind", "$shadeBase.maxmind")
    relocate("org.springframework", "$shadeBase.springframework")
    relocate("org.yaml.snakeyaml", "$shadeBase.snakeyaml")
    // slf4j-jdk14 ships its provider as org.slf4j.jul; relocate the impl so we
    // don't compete with any binding the server runtime might also expose.
    // The org.slf4j (api) package itself MUST stay as-is so Paper's slf4j-api
    // and ours are the same classes.
    relocate("org.slf4j.jul", "$shadeBase.slf4j.jul")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Convenience: `./gradlew copyToTestServer -PtestServer=path/to/server`
tasks.register<Copy>("copyToTestServer") {
    dependsOn(tasks.shadowJar)
    val target = (project.findProperty("testServer") as String?) ?: ""
    onlyIf {
        if (target.isBlank()) {
            logger.warn("copyToTestServer: pass -PtestServer=<path> to enable")
            false
        } else true
    }
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(target + "/plugins")
}
