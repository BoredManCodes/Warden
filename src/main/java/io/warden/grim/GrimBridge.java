package io.warden.grim;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional integration with the GrimAnticheat (https://github.com/GrimAnticheat/Grim)
 * plugin. Rather than subscribe to GrimAPI events (whose surface shifts between
 * versions and forces a tight compile-time coupling), Warden reads Grim's own
 * {@code plugins/GrimAC/violations.sqlite} in read-only mode. This makes the
 * dashboard surface every violation Grim has ever recorded, with no
 * registration step and no risk of {@link NoSuchMethodError} when Grim
 * updates its API.
 */
public final class GrimBridge {

    public static final String DOWNLOAD_URL = "https://github.com/GrimAnticheat/Grim";
    public static final String API_URL      = "https://github.com/GrimAnticheat/GrimAPI";

    public enum Status { MISSING, ACTIVE, ERROR }

    private final Logger log;
    private volatile GrimViolationDao dao;
    private volatile Status status = Status.MISSING;
    private volatile String errorMessage = "";

    public GrimBridge(Logger log) {
        this.log = log;
    }

    /** True when GrimAC is enabled on the server right now. */
    public boolean isPresent() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled("GrimAC");
        } catch (Throwable t) {
            return false;
        }
    }

    public Status status() { return status; }
    public String errorMessage() { return errorMessage; }
    public Path violationsFile() { return dao == null ? null : dao.file(); }

    /**
     * Locate Grim's violations sqlite and probe it. Safe to call when Grim
     * is absent; in that case the bridge stays in MISSING.
     */
    public void open() {
        if (!isPresent()) {
            status = Status.MISSING;
            return;
        }
        try {
            Plugin grim = Bukkit.getPluginManager().getPlugin("GrimAC");
            if (grim == null) {
                status = Status.MISSING;
                return;
            }
            Path file = grim.getDataFolder().toPath().resolve("violations.sqlite");
            GrimViolationDao d = new GrimViolationDao(file);
            if (!d.exists()) {
                status = Status.ERROR;
                errorMessage = "violations.sqlite not found at " + file;
                log.warning("GrimBridge: " + errorMessage);
                return;
            }
            d.totalCount(); // smoke test - throws if the schema isn't what we expect
            this.dao = d;
            this.status = Status.ACTIVE;
            this.errorMessage = "";
            log.info("GrimBridge: reading violations from " + file);
        } catch (SQLException e) {
            status = Status.ERROR;
            errorMessage = "probe query failed: " + e.getMessage();
            log.log(Level.WARNING, "GrimBridge: probe failed", e);
        } catch (Throwable t) {
            status = Status.ERROR;
            errorMessage = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            log.log(Level.WARNING, "GrimBridge: failed to open violations.sqlite", t);
        }
    }

    /** Nothing to release - DAO opens per-query connections. */
    public void shutdown() {
        // no-op; kept so callers can stay symmetric with open()
    }

    /* ----- read-side passthroughs for the dashboard handler ----- */

    public List<GrimViolationDao.Violation> recentViolations(int limit) {
        if (dao == null) return List.of();
        try {
            return dao.recent(limit);
        } catch (SQLException e) {
            log.log(Level.WARNING, "GrimBridge: failed to read recent violations", e);
            return List.of();
        }
    }

    public long totalViolationCount() {
        if (dao == null) return 0L;
        try { return dao.totalCount(); }
        catch (SQLException e) {
            log.log(Level.WARNING, "GrimBridge: failed to count violations", e);
            return 0L;
        }
    }

    public List<GrimViolationDao.CheckCount> topChecks(int limit) {
        if (dao == null) return List.of();
        try { return dao.topChecks(limit); }
        catch (SQLException e) {
            log.log(Level.WARNING, "GrimBridge: failed to read top checks", e);
            return List.of();
        }
    }
}
