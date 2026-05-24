package io.warden.data;

import io.warden.config.WardenConfig;
import io.warden.data.dao.LinkCodeDao;
import io.warden.data.dao.LinkCodeDao.ClaimedCode;
import io.warden.data.dao.LinkCodeDao.IssuedCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkCodeDaoTest {

    @TempDir Path tmp;
    Database db;
    LinkCodeDao dao;

    @BeforeEach
    void setup() throws Exception {
        Path file = tmp.resolve("warden.db");
        this.db = new Database(testConfig(file));
        new SchemaLoader(db, Logger.getLogger("test")).initialise();
        this.dao = new LinkCodeDao(db);
    }

    @AfterEach
    void teardown() {
        if (db != null) db.close();
    }

    @Test
    void issueGeneratesUnique8CharCodeBoundToSession() throws Exception {
        IssuedCode a = dao.issueForSession("session-a");
        IssuedCode b = dao.issueForSession("session-b");
        assertEquals(8, a.code().length());
        assertEquals(8, b.code().length());
        assertNotEquals(a.code(), b.code());
        assertTrue(a.code().matches("[A-Z0-9]{8}"), "code should be A-Z0-9: " + a.code());
        assertTrue(a.expiresAt() > System.currentTimeMillis(), "expires in the future");
    }

    @Test
    void claimRecordsDiscordIdAndReturnsWebSession() throws Exception {
        IssuedCode issued = dao.issueForSession("session-x");
        Optional<String> first = dao.claim(issued.code(), "discord-1");
        assertTrue(first.isPresent());
        assertEquals("session-x", first.get());
        // Second claim of same code must fail.
        Optional<String> second = dao.claim(issued.code(), "discord-2");
        assertFalse(second.isPresent(), "second claim should fail (already claimed)");
    }

    @Test
    void claimIsCaseInsensitiveAndTrimmed() throws Exception {
        IssuedCode issued = dao.issueForSession("session-y");
        Optional<String> out = dao.claim("   " + issued.code().toLowerCase() + "   ", "discord-y");
        assertTrue(out.isPresent());
        assertEquals("session-y", out.get());
    }

    @Test
    void expiredCodeCannotBeClaimed() throws Exception {
        IssuedCode issued = dao.issueForSession("session-z");
        // Manually backdate expiry.
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE link_codes SET expires_at = 1 WHERE code = ?")) {
            ps.setString(1, issued.code());
            ps.executeUpdate();
        }
        Optional<String> out = dao.claim(issued.code(), "discord-z");
        assertFalse(out.isPresent(), "expired code must not claim");
    }

    @Test
    void unknownCodeReturnsEmpty() throws Exception {
        assertFalse(dao.claim("XXXXXXXX", "discord-x").isPresent());
        assertFalse(dao.claim(null, "discord-x").isPresent());
        assertFalse(dao.claim("short", "discord-x").isPresent());
    }

    @Test
    void findClaimedForReturnsRecentClaim() throws Exception {
        IssuedCode issued = dao.issueForSession("session-poll");
        assertTrue(dao.findClaimedFor("session-poll", 60_000).isEmpty(),
                "unclaimed code shouldn't show up");
        dao.claim(issued.code(), "discord-poll");
        Optional<ClaimedCode> claimed = dao.findClaimedFor("session-poll", 60_000);
        assertTrue(claimed.isPresent());
        assertEquals("discord-poll", claimed.get().discordId());
        assertEquals(issued.code(), claimed.get().code());
    }

    private static WardenConfig testConfig(Path dbFile) {
        return new WardenConfig(
                "", "", "", "", "",
                "127.0.0.1", 0, "http://localhost", "",
                dbFile, dbFile.getParent().resolve("www"),
                new WardenConfig.Ssl(false, 8443,
                        dbFile.getParent().resolve("ssl/fullchain.pem"),
                        dbFile.getParent().resolve("ssl/privkey.pem"),
                        true),
                new WardenConfig.GeoIp(false, "", "GeoLite2-Country", 7,
                        dbFile.getParent().resolve("geoip")),
                WardenConfig.Modules.allOn()
        );
    }
}
