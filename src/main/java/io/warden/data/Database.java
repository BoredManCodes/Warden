package io.warden.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.warden.config.WardenConfig;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite connection pool tuned for in-process plugin use.
 *
 * SQLite serializes writes regardless of pool size, so we keep the pool small.
 * WAL mode lets multiple readers proceed in parallel with one writer.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource ds;
    private final Path file;

    public Database(WardenConfig config) {
        this.file = config.dbFile();
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create db parent dir " + file, e);
        }

        HikariConfig hk = new HikariConfig();
        hk.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        hk.setDriverClassName("org.sqlite.JDBC");
        hk.setMaximumPoolSize(4);
        hk.setMinimumIdle(1);
        hk.setPoolName("warden-sqlite");
        hk.setAutoCommit(true);
        hk.setConnectionTestQuery("SELECT 1");
        // SQLite has no statement timeout; rely on busy_timeout below.
        this.ds = new HikariDataSource(hk);

        // Apply pragmas on a checkout. The first time also sets WAL persistently.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite at " + file, e);
        }
    }

    public Connection connection() throws SQLException {
        Connection c = ds.getConnection();
        // Every checkout also enables per-connection pragmas (foreign_keys is per-connection in SQLite).
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    public DataSource dataSource() {
        return ds;
    }

    public Path file() {
        return file;
    }

    @Override
    public void close() {
        ds.close();
    }
}
