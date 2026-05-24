package io.warden.audit;

import io.warden.data.dao.AuditDao;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Thin convenience wrapper that swallows DB failures (audit must not break a flow). */
public final class AuditService {

    public static final String ACTOR_BOT = "bot";
    public static final String ACTOR_LLM = "llm";
    public static final String ACTOR_WEB = "web";
    public static final String ACTOR_SYSTEM = "system";

    private final AuditDao dao;
    private final Logger log;

    public AuditService(AuditDao dao, Logger log) {
        this.dao = dao;
        this.log = log;
    }

    public void write(String actor, String action, String targetDiscordId, Map<String, ?> payload) {
        try {
            dao.write(actor, action, targetDiscordId, payload);
        } catch (Exception e) {
            log.log(Level.WARNING, "audit write failed (" + action + "): " + e.getMessage());
        }
    }
}
