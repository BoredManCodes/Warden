package io.warden.onboarding;

import io.warden.data.dao.LinkCodeDao;
import io.warden.data.dao.LinkCodeDao.ClaimedCode;
import io.warden.data.dao.LinkCodeDao.IssuedCode;

import java.sql.SQLException;
import java.util.Optional;

/** Thin wrapper around {@link LinkCodeDao} with throw-on-failure semantics. */
public final class LinkCodeService {

    private final LinkCodeDao dao;

    public LinkCodeService(LinkCodeDao dao) {
        this.dao = dao;
    }

    /** Mint a code for the given guest browser session (used by /onboard). */
    public IssuedCode issueForSession(String webSessionId) throws SQLException {
        return dao.issueForSession(webSessionId);
    }

    /** DM listener -> claim a code on behalf of the DMing Discord user. */
    public Optional<String> claim(String code, String discordId) throws SQLException {
        return dao.claim(code, discordId);
    }

    /** Web poller -> has a code for this guest session been DM'd to the bot yet? */
    public Optional<ClaimedCode> findClaimedFor(String webSessionId, long lookbackMs) throws SQLException {
        return dao.findClaimedFor(webSessionId, lookbackMs);
    }
}
