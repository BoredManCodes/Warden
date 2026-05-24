package io.warden.data;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-shot schema initialiser. Reads {@code /schema.sql} from the classpath and
 * executes it on the database. Every statement uses {@code CREATE TABLE IF NOT EXISTS},
 * {@code CREATE INDEX IF NOT EXISTS}, or {@code INSERT OR IGNORE}, so running
 * it on an existing DB is idempotent.
 */
public final class SchemaLoader {

    private static final String SCHEMA_PATH = "/schema.sql";

    private final Database db;
    private final Logger log;

    public SchemaLoader(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public void initialise() throws SQLException {
        String sql;
        try {
            sql = readSchema();
        } catch (IOException e) {
            throw new SQLException("Failed to read " + SCHEMA_PATH + " from classpath: " + e.getMessage(), e);
        }
        if (sql == null || sql.isBlank()) {
            log.warning("schema.sql was empty or missing on the classpath; no tables created.");
            return;
        }
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                executeBatch(c, sql);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw new SQLException("Schema init failed: " + e.getMessage(), e);
            } finally {
                c.setAutoCommit(true);
            }
        }
        log.info("Schema initialised from " + SCHEMA_PATH);
    }

    private static String readSchema() throws IOException {
        URL url = SchemaLoader.class.getResource(SCHEMA_PATH);
        if (url == null) return null;
        try (InputStream is = url.openStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void executeBatch(Connection c, String sql) throws SQLException {
        List<String> statements = splitStatements(sql);
        try (Statement st = c.createStatement()) {
            for (String s : statements) {
                String trimmed = s.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    st.execute(trimmed);
                } catch (SQLException e) {
                    throw new SQLException("Statement failed: " + trimmed.substring(0,
                            Math.min(120, trimmed.length())) + "... -> " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Split a SQL script on `;` boundaries while tolerating single-quoted strings
     * (which is all our schema uses). Comment lines starting with `--` are stripped.
     */
    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (!inString && ch == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (ch == '\'') {
                cur.append(ch);
                if (inString && i + 1 < n && sql.charAt(i + 1) == '\'') {
                    cur.append(sql.charAt(i + 1));
                    i += 2;
                    continue;
                }
                inString = !inString;
                i++;
                continue;
            }
            if (!inString && ch == ';') {
                out.add(cur.toString());
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(ch);
            i++;
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString());
        return out;
    }

    /**
     * Convenience used by tests that still want a one-shot exception swallow
     * (none currently). Kept package-private so it doesn't widen the API.
     */
    static void quietInit(Database db, Logger log) {
        try { new SchemaLoader(db, log).initialise(); }
        catch (SQLException e) { log.log(Level.SEVERE, "schema init failed", e); }
    }
}
