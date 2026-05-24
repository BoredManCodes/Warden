-- Warden schema. SQLite. WAL mode is set at connection-open time.
-- Re-applied on every boot with CREATE IF NOT EXISTS + INSERT OR IGNORE so a
-- fresh DB is initialised end-to-end and an existing DB is a no-op.

-- =====================================================================
-- CORE
-- =====================================================================

CREATE TABLE IF NOT EXISTS users (
    discord_id      TEXT PRIMARY KEY,
    username        TEXT NOT NULL,
    joined_at       INTEGER NOT NULL,
    state           TEXT NOT NULL DEFAULT 'pending_link',
    web_session_id  TEXT,
    updated_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS users_state_idx ON users(state);

CREATE TABLE IF NOT EXISTS link_codes (
    code             TEXT PRIMARY KEY,
    web_session_id   TEXT NOT NULL,
    claimed_by       TEXT,
    expires_at       INTEGER NOT NULL,
    consumed_at      INTEGER,
    created_at       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS link_codes_session_idx ON link_codes(web_session_id);
CREATE INDEX IF NOT EXISTS link_codes_claimed_idx ON link_codes(claimed_by);

CREATE TABLE IF NOT EXISTS questions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    order_index   INTEGER NOT NULL,
    prompt        TEXT NOT NULL,
    kind          TEXT NOT NULL,
    choices_json  TEXT NOT NULL DEFAULT '[]',
    required      INTEGER NOT NULL DEFAULT 1,
    active        INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS answers (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_id    TEXT NOT NULL,
    question_id   INTEGER NOT NULL,
    value         TEXT NOT NULL,
    submitted_at  INTEGER NOT NULL,
    UNIQUE(discord_id, question_id)
);

CREATE TABLE IF NOT EXISTS applications (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_id               TEXT NOT NULL,
    submitted_at             INTEGER NOT NULL,
    llm_decision             TEXT,
    llm_confidence_x1000     INTEGER,
    llm_reasoning            TEXT,
    final_decision           TEXT,
    decided_by               TEXT,
    decided_at               INTEGER,
    mod_note                 TEXT,
    mod_message_id           TEXT
);
CREATE INDEX IF NOT EXISTS applications_user_idx    ON applications(discord_id);
CREATE INDEX IF NOT EXISTS applications_pending_idx ON applications(final_decision);

CREATE TABLE IF NOT EXISTS settings (
    id                                 INTEGER PRIMARY KEY,
    rules_markdown                     TEXT NOT NULL DEFAULT '',
    gated_role_id                      TEXT NOT NULL DEFAULT '',
    full_role_id                       TEXT NOT NULL DEFAULT '',
    mod_role_id                        TEXT NOT NULL DEFAULT '',
    welcome_channel_id                 TEXT NOT NULL DEFAULT '',
    mod_review_channel_id              TEXT NOT NULL DEFAULT '',
    llm_system_prompt                  TEXT NOT NULL DEFAULT '',
    llm_auto_approve_threshold_x1000   INTEGER NOT NULL DEFAULT 850,
    llm_auto_deny_threshold_x1000      INTEGER NOT NULL DEFAULT 150,
    -- Flow config
    delivery_via_dm                    INTEGER NOT NULL DEFAULT 0,
    delivery_via_channel               INTEGER NOT NULL DEFAULT 0,
    delivery_channel_id                TEXT    NOT NULL DEFAULT '',
    delivery_message_template          TEXT    NOT NULL DEFAULT '',
    entry_via_discord_button           INTEGER NOT NULL DEFAULT 1,
    entry_via_web_code                 INTEGER NOT NULL DEFAULT 1,
    entry_via_web_oauth                INTEGER NOT NULL DEFAULT 0,
    gating_enabled                     INTEGER NOT NULL DEFAULT 0,
    triage_mode                        TEXT    NOT NULL DEFAULT 'mod_only',
    approve_dm_enabled                 INTEGER NOT NULL DEFAULT 0,
    approve_dm_template                TEXT    NOT NULL DEFAULT '',
    approve_channel_announce           INTEGER NOT NULL DEFAULT 0,
    approve_channel_template           TEXT    NOT NULL DEFAULT '',
    approve_extra_roles_json           TEXT    NOT NULL DEFAULT '[]',
    deny_dm_enabled                    INTEGER NOT NULL DEFAULT 0,
    deny_dm_template                   TEXT    NOT NULL DEFAULT '',
    deny_action                        TEXT    NOT NULL DEFAULT 'leave_gated',
    llm_auto_deny_enabled              INTEGER NOT NULL DEFAULT 1,
    -- LLM endpoint
    llm_api_key                        TEXT    NOT NULL DEFAULT '',
    llm_base_url                       TEXT    NOT NULL DEFAULT 'https://app.manifest.build/v1',
    llm_model                          TEXT    NOT NULL DEFAULT 'auto',
    -- Landing
    landing_mode                       TEXT    NOT NULL DEFAULT 'enabled',
    landing_redirect_url               TEXT    NOT NULL DEFAULT '',
    landing_server_name                TEXT    NOT NULL DEFAULT '',
    landing_server_address             TEXT    NOT NULL DEFAULT '',
    landing_tagline                    TEXT    NOT NULL DEFAULT '',
    landing_join_url                   TEXT    NOT NULL DEFAULT '',
    landing_map_enabled                INTEGER NOT NULL DEFAULT 0,
    landing_map_provider               TEXT    NOT NULL DEFAULT '',
    landing_map_url                    TEXT    NOT NULL DEFAULT '',
    landing_map_label                  TEXT    NOT NULL DEFAULT 'Live Map',
    landing_features_json              TEXT    NOT NULL DEFAULT '[]',
    landing_faqs_json                  TEXT    NOT NULL DEFAULT '[]',
    -- Role gates + landing imagery
    config_admin_role_id               TEXT    NOT NULL DEFAULT '',
    web_manager_role_id                TEXT    NOT NULL DEFAULT '',
    landing_brand_image_url            TEXT    NOT NULL DEFAULT '',
    landing_hero_image_url             TEXT    NOT NULL DEFAULT '',
    landing_accent_color               TEXT    NOT NULL DEFAULT '#39beff',
    landing_papi_players_online        TEXT    NOT NULL DEFAULT '',
    landing_papi_discord_members       TEXT    NOT NULL DEFAULT '',
    landing_stat_players_label         TEXT    NOT NULL DEFAULT 'Players online',
    landing_stat_members_label         TEXT    NOT NULL DEFAULT 'Discord members',
    -- GeoIP
    geoip_enabled                      INTEGER NOT NULL DEFAULT 0,
    geoip_license_key                  TEXT    NOT NULL DEFAULT '',
    -- Google Analytics on the public site
    landing_google_analytics_id        TEXT    NOT NULL DEFAULT '',
    landing_cookie_banner              INTEGER NOT NULL DEFAULT 0,
    -- Public leaderboard customisation
    landing_leaderboard_enabled        INTEGER NOT NULL DEFAULT 0,
    landing_leaderboard_title          TEXT    NOT NULL DEFAULT 'Leaderboard',
    landing_leaderboard_description    TEXT    NOT NULL DEFAULT 'Top members ranked by activity.',
    landing_leaderboard_top_n          INTEGER NOT NULL DEFAULT 25,
    landing_leaderboard_label          TEXT    NOT NULL DEFAULT 'Leaderboard',
    landing_promo_video_url            TEXT    NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS audit_log (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    actor              TEXT NOT NULL,
    action             TEXT NOT NULL,
    target_discord_id  TEXT,
    payload_json       TEXT NOT NULL DEFAULT '{}',
    at                 INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS audit_target_idx ON audit_log(target_discord_id);
CREATE INDEX IF NOT EXISTS audit_at_idx     ON audit_log(at);

-- =====================================================================
-- ANALYTICS
-- =====================================================================

CREATE TABLE IF NOT EXISTS discord_messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_id      TEXT    NOT NULL,
    channel_id      TEXT    NOT NULL,
    guild_id        TEXT    NOT NULL,
    at              INTEGER NOT NULL,
    length          INTEGER NOT NULL,
    has_attachment  INTEGER NOT NULL DEFAULT 0,
    is_reply        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_discord_messages_at      ON discord_messages(at);
CREATE INDEX IF NOT EXISTS idx_discord_messages_user    ON discord_messages(discord_id, at);
CREATE INDEX IF NOT EXISTS idx_discord_messages_channel ON discord_messages(channel_id, at);

CREATE TABLE IF NOT EXISTS discord_member_events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_id  TEXT    NOT NULL,
    kind        TEXT    NOT NULL,
    at          INTEGER NOT NULL,
    reason      TEXT,
    invite_code TEXT
);
CREATE INDEX IF NOT EXISTS idx_discord_member_events_user   ON discord_member_events(discord_id, at);
CREATE INDEX IF NOT EXISTS idx_discord_member_events_kind   ON discord_member_events(kind, at);
CREATE INDEX IF NOT EXISTS idx_discord_member_events_invite ON discord_member_events(invite_code);

CREATE TABLE IF NOT EXISTS discord_voice_sessions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_id   TEXT    NOT NULL,
    channel_id   TEXT    NOT NULL,
    guild_id     TEXT    NOT NULL,
    started_at   INTEGER NOT NULL,
    ended_at     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_discord_voice_sessions_user    ON discord_voice_sessions(discord_id, started_at);
CREATE INDEX IF NOT EXISTS idx_discord_voice_sessions_channel ON discord_voice_sessions(channel_id, started_at);
CREATE INDEX IF NOT EXISTS idx_discord_voice_sessions_open    ON discord_voice_sessions(discord_id) WHERE ended_at IS NULL;

CREATE TABLE IF NOT EXISTS mc_sessions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    mc_uuid       TEXT    NOT NULL,
    mc_name       TEXT    NOT NULL,
    started_at    INTEGER NOT NULL,
    ended_at      INTEGER,
    ip_hash       TEXT,
    country       TEXT,
    client_brand  TEXT
);
CREATE INDEX IF NOT EXISTS idx_mc_sessions_user    ON mc_sessions(mc_uuid, started_at);
CREATE INDEX IF NOT EXISTS idx_mc_sessions_started ON mc_sessions(started_at);
CREATE INDEX IF NOT EXISTS idx_mc_sessions_open    ON mc_sessions(mc_uuid) WHERE ended_at IS NULL;

CREATE TABLE IF NOT EXISTS daily_metrics (
    day        TEXT    NOT NULL,
    metric     TEXT    NOT NULL,
    dimension  TEXT    NOT NULL DEFAULT '',
    value      INTEGER NOT NULL,
    PRIMARY KEY (day, metric, dimension)
);
CREATE INDEX IF NOT EXISTS idx_daily_metrics_metric ON daily_metrics(metric, day);

CREATE TABLE IF NOT EXISTS cohort_membership (
    discord_id  TEXT NOT NULL,
    mc_uuid     TEXT,
    cohort      TEXT NOT NULL,
    source      TEXT NOT NULL,
    joined_at   INTEGER NOT NULL,
    PRIMARY KEY (discord_id, source)
);
CREATE INDEX IF NOT EXISTS idx_cohort_cohort ON cohort_membership(cohort, source);

CREATE TABLE IF NOT EXISTS analytics_meta (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS invites (
    code        TEXT PRIMARY KEY,
    guild_id    TEXT NOT NULL,
    channel_id  TEXT,
    inviter_id  TEXT,
    label       TEXT,
    uses        INTEGER NOT NULL DEFAULT 0,
    max_uses    INTEGER NOT NULL DEFAULT 0,
    expires_at  INTEGER,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    deleted_at  INTEGER
);
CREATE INDEX IF NOT EXISTS idx_invites_label   ON invites(label);
CREATE INDEX IF NOT EXISTS idx_invites_active  ON invites(deleted_at);

-- =====================================================================
-- MODERATION
-- =====================================================================

CREATE TABLE IF NOT EXISTS automod_config (
    id                      INTEGER PRIMARY KEY,
    enabled                 INTEGER NOT NULL DEFAULT 0,
    spam_enabled            INTEGER NOT NULL DEFAULT 1,
    spam_threshold          INTEGER NOT NULL DEFAULT 5,
    spam_window_seconds     INTEGER NOT NULL DEFAULT 7,
    caps_enabled            INTEGER NOT NULL DEFAULT 1,
    caps_min_length         INTEGER NOT NULL DEFAULT 10,
    caps_percent            INTEGER NOT NULL DEFAULT 70,
    bad_words_enabled       INTEGER NOT NULL DEFAULT 0,
    bad_words_list          TEXT    NOT NULL DEFAULT '',
    links_enabled           INTEGER NOT NULL DEFAULT 0,
    links_allowlist         TEXT    NOT NULL DEFAULT '',
    invites_enabled         INTEGER NOT NULL DEFAULT 0,
    mass_mention_enabled    INTEGER NOT NULL DEFAULT 1,
    mass_mention_threshold  INTEGER NOT NULL DEFAULT 5,
    emoji_flood_enabled     INTEGER NOT NULL DEFAULT 1,
    emoji_flood_threshold   INTEGER NOT NULL DEFAULT 8,
    zalgo_enabled           INTEGER NOT NULL DEFAULT 1,
    action_default          TEXT    NOT NULL DEFAULT 'delete',
    exempt_role_ids_json    TEXT    NOT NULL DEFAULT '[]',
    exempt_channel_ids_json TEXT    NOT NULL DEFAULT '[]',
    log_channel_id          TEXT    NOT NULL DEFAULT '',
    warn_thresholds_json    TEXT    NOT NULL DEFAULT '[]'
);
INSERT OR IGNORE INTO automod_config (id) VALUES (1);

CREATE TABLE IF NOT EXISTS warnings (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_id      TEXT    NOT NULL,
    moderator_id    TEXT    NOT NULL,
    reason          TEXT    NOT NULL DEFAULT '',
    severity        INTEGER NOT NULL DEFAULT 1,
    created_at      INTEGER NOT NULL,
    cleared_at      INTEGER
);
CREATE INDEX IF NOT EXISTS warnings_user_idx ON warnings(discord_id);
CREATE INDEX IF NOT EXISTS warnings_created_idx ON warnings(created_at);

CREATE TABLE IF NOT EXISTS mod_actions (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    action            TEXT    NOT NULL,
    target_discord_id TEXT    NOT NULL,
    moderator_id      TEXT    NOT NULL,
    reason            TEXT    NOT NULL DEFAULT '',
    duration_seconds  INTEGER,
    expires_at        INTEGER,
    revoked_at        INTEGER,
    created_at        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS mod_actions_target_idx ON mod_actions(target_discord_id);
CREATE INDEX IF NOT EXISTS mod_actions_expires_idx ON mod_actions(expires_at);

CREATE TABLE IF NOT EXISTS raid_protection (
    id                       INTEGER PRIMARY KEY,
    enabled                  INTEGER NOT NULL DEFAULT 0,
    joins_threshold          INTEGER NOT NULL DEFAULT 10,
    joins_window_seconds     INTEGER NOT NULL DEFAULT 30,
    account_age_min_days     INTEGER NOT NULL DEFAULT 0,
    lockdown_action          TEXT    NOT NULL DEFAULT 'kick',
    lockdown_until           INTEGER,
    log_channel_id           TEXT    NOT NULL DEFAULT '',
    auto_disable_minutes     INTEGER NOT NULL DEFAULT 15
);
INSERT OR IGNORE INTO raid_protection (id) VALUES (1);

-- =====================================================================
-- LEVELING / XP
-- =====================================================================

CREATE TABLE IF NOT EXISTS level_config (
    id                       INTEGER PRIMARY KEY,
    enabled                  INTEGER NOT NULL DEFAULT 0,
    xp_per_message_min       INTEGER NOT NULL DEFAULT 15,
    xp_per_message_max       INTEGER NOT NULL DEFAULT 25,
    cooldown_seconds         INTEGER NOT NULL DEFAULT 60,
    levelup_announce         INTEGER NOT NULL DEFAULT 1,
    levelup_channel_id       TEXT    NOT NULL DEFAULT '',
    levelup_message_template TEXT    NOT NULL DEFAULT 'GG {user_mention}, you reached level **{level}**!',
    leaderboard_public       INTEGER NOT NULL DEFAULT 1,
    no_xp_role_ids_json      TEXT    NOT NULL DEFAULT '[]',
    no_xp_channel_ids_json   TEXT    NOT NULL DEFAULT '[]',
    rank_card_accent         TEXT    NOT NULL DEFAULT '#5865F2',
    rank_card_background     TEXT    NOT NULL DEFAULT '',
    mc_xp_enabled            INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO level_config (id) VALUES (1);

CREATE TABLE IF NOT EXISTS level_users (
    discord_id     TEXT PRIMARY KEY,
    xp             INTEGER NOT NULL DEFAULT 0,
    level          INTEGER NOT NULL DEFAULT 0,
    messages       INTEGER NOT NULL DEFAULT 0,
    last_grant_at  INTEGER NOT NULL DEFAULT 0,
    updated_at     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS level_users_xp_idx ON level_users(xp DESC);

CREATE TABLE IF NOT EXISTS level_role_rewards (
    level    INTEGER NOT NULL,
    role_id  TEXT    NOT NULL,
    stack    INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (level, role_id)
);

CREATE TABLE IF NOT EXISTS level_multipliers (
    kind         TEXT    NOT NULL,
    target_id    TEXT    NOT NULL,
    multiplier   INTEGER NOT NULL DEFAULT 100,
    PRIMARY KEY (kind, target_id)
);

-- =====================================================================
-- REACTION ROLES
-- =====================================================================

CREATE TABLE IF NOT EXISTS reaction_role_groups (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,
    channel_id      TEXT    NOT NULL DEFAULT '',
    message_id      TEXT    NOT NULL DEFAULT '',
    mode            TEXT    NOT NULL DEFAULT 'normal',
    style           TEXT    NOT NULL DEFAULT 'buttons',
    title           TEXT    NOT NULL DEFAULT '',
    description     TEXT    NOT NULL DEFAULT '',
    color_hex       TEXT    NOT NULL DEFAULT '#5865F2',
    max_selections  INTEGER NOT NULL DEFAULT 0,
    required_role   TEXT    NOT NULL DEFAULT '',
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS reaction_role_options (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id     INTEGER NOT NULL,
    role_id      TEXT    NOT NULL,
    label        TEXT    NOT NULL,
    emoji        TEXT    NOT NULL DEFAULT '',
    description  TEXT    NOT NULL DEFAULT '',
    order_index  INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (group_id) REFERENCES reaction_role_groups(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS rr_options_group_idx ON reaction_role_options(group_id);

-- =====================================================================
-- POLLS
-- =====================================================================

CREATE TABLE IF NOT EXISTS polls (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id   TEXT    NOT NULL,
    message_id   TEXT    NOT NULL DEFAULT '',
    creator_id   TEXT    NOT NULL,
    question     TEXT    NOT NULL,
    options_json TEXT    NOT NULL,
    anonymous    INTEGER NOT NULL DEFAULT 0,
    multi_choice INTEGER NOT NULL DEFAULT 0,
    ends_at      INTEGER,
    closed_at    INTEGER,
    created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS polls_ends_idx ON polls(ends_at);

CREATE TABLE IF NOT EXISTS poll_votes (
    poll_id      INTEGER NOT NULL,
    discord_id   TEXT    NOT NULL,
    option_index INTEGER NOT NULL,
    voted_at     INTEGER NOT NULL,
    PRIMARY KEY (poll_id, discord_id, option_index),
    FOREIGN KEY (poll_id) REFERENCES polls(id) ON DELETE CASCADE
);

-- =====================================================================
-- GIVEAWAYS
-- =====================================================================

CREATE TABLE IF NOT EXISTS giveaways (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id      TEXT    NOT NULL,
    message_id      TEXT    NOT NULL DEFAULT '',
    creator_id      TEXT    NOT NULL,
    prize           TEXT    NOT NULL,
    description     TEXT    NOT NULL DEFAULT '',
    winners         INTEGER NOT NULL DEFAULT 1,
    required_role   TEXT    NOT NULL DEFAULT '',
    ends_at         INTEGER NOT NULL,
    drawn_at        INTEGER,
    cancelled_at    INTEGER,
    winners_json    TEXT    NOT NULL DEFAULT '[]',
    created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS giveaways_ends_idx ON giveaways(ends_at);

CREATE TABLE IF NOT EXISTS giveaway_entries (
    giveaway_id  INTEGER NOT NULL,
    discord_id   TEXT    NOT NULL,
    entered_at   INTEGER NOT NULL,
    PRIMARY KEY (giveaway_id, discord_id),
    FOREIGN KEY (giveaway_id) REFERENCES giveaways(id) ON DELETE CASCADE
);

-- =====================================================================
-- REMINDERS
-- =====================================================================

CREATE TABLE IF NOT EXISTS reminders (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_id   TEXT    NOT NULL,
    channel_id   TEXT    NOT NULL DEFAULT '',
    message      TEXT    NOT NULL,
    fires_at     INTEGER NOT NULL,
    delivered_at INTEGER,
    cancelled_at INTEGER,
    created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS reminders_fires_idx ON reminders(fires_at);
CREATE INDEX IF NOT EXISTS reminders_user_idx  ON reminders(discord_id);

-- =====================================================================
-- GRIM VIOLATIONS
-- =====================================================================

CREATE TABLE IF NOT EXISTS grim_violations (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ts          INTEGER NOT NULL,
    uuid        TEXT    NOT NULL,
    name        TEXT    NOT NULL DEFAULT '',
    check_name  TEXT    NOT NULL,
    vl          REAL    NOT NULL DEFAULT 0,
    verbose     TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS grim_violations_ts_idx    ON grim_violations(ts DESC);
CREATE INDEX IF NOT EXISTS grim_violations_uuid_idx  ON grim_violations(uuid);
CREATE INDEX IF NOT EXISTS grim_violations_check_idx ON grim_violations(check_name);

-- =====================================================================
-- ALERTS
-- =====================================================================

CREATE TABLE IF NOT EXISTS alerts (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    name                  TEXT    NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1,
    event                 TEXT    NOT NULL,
    channel_id            TEXT    NOT NULL DEFAULT '',
    message_content       TEXT    NOT NULL DEFAULT '',
    embed_enabled         INTEGER NOT NULL DEFAULT 0,
    embed_title           TEXT    NOT NULL DEFAULT '',
    embed_description     TEXT    NOT NULL DEFAULT '',
    embed_color_hex       TEXT    NOT NULL DEFAULT '#5865F2',
    embed_thumbnail       TEXT    NOT NULL DEFAULT '',
    embed_image           TEXT    NOT NULL DEFAULT '',
    embed_footer          TEXT    NOT NULL DEFAULT '',
    embed_fields_json     TEXT    NOT NULL DEFAULT '[]',
    console_commands      TEXT    NOT NULL DEFAULT '',
    asplayer_commands     TEXT    NOT NULL DEFAULT '',
    papi_player_uuid      TEXT    NOT NULL DEFAULT '',
    trigger_class         TEXT    NOT NULL DEFAULT '',
    conditions            TEXT    NOT NULL DEFAULT '',
    expressions_enabled   INTEGER NOT NULL DEFAULT 0,
    async_dispatch        INTEGER NOT NULL DEFAULT 0,
    embed_author_name     TEXT    NOT NULL DEFAULT '',
    embed_author_icon_url TEXT    NOT NULL DEFAULT '',
    created_at            INTEGER NOT NULL,
    updated_at            INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS alerts_event_idx   ON alerts(event);
CREATE INDEX IF NOT EXISTS alerts_enabled_idx ON alerts(enabled);

-- =====================================================================
-- TICKETS
-- =====================================================================

CREATE TABLE IF NOT EXISTS tickets_config (
    id                       INTEGER PRIMARY KEY,
    staff_channel_id         TEXT    NOT NULL DEFAULT '',
    dm_reporter_on_open      INTEGER NOT NULL DEFAULT 1,
    dm_reporter_on_reply     INTEGER NOT NULL DEFAULT 1,
    dm_reporter_on_status    INTEGER NOT NULL DEFAULT 1,
    open_ack_message         TEXT    NOT NULL DEFAULT 'Thanks, your ticket has been opened. Staff will be in touch.',
    closed_lock_replies      INTEGER NOT NULL DEFAULT 1,
    default_mode             TEXT    NOT NULL DEFAULT 'dm',
    channel_category_id      TEXT    NOT NULL DEFAULT ''
);
INSERT OR IGNORE INTO tickets_config (id) VALUES (1);

CREATE TABLE IF NOT EXISTS ticket_categories (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    slug                TEXT    NOT NULL UNIQUE,
    name                TEXT    NOT NULL,
    description         TEXT    NOT NULL DEFAULT '',
    emoji               TEXT    NOT NULL DEFAULT '',
    button_style        TEXT    NOT NULL DEFAULT 'SECONDARY',
    sort_order          INTEGER NOT NULL DEFAULT 0,
    enabled             INTEGER NOT NULL DEFAULT 1,
    delivery_mode       TEXT    NOT NULL DEFAULT 'inherit',
    channel_category_id TEXT    NOT NULL DEFAULT '',
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL
);

INSERT OR IGNORE INTO ticket_categories
    (slug, name, description, emoji, button_style, sort_order, enabled, created_at, updated_at)
VALUES
    ('suggestion', 'Suggestion',  'Suggest a feature, change, or improvement.',   '💡', 'SUCCESS',   20, 1, 0, 0),
    ('bug',        'Bug report',  'Report a bug or something broken in-game.',    '🐞', 'DANGER',    30, 1, 0, 0),
    ('other',      'Other',       'Anything else.',                                '❓', 'SECONDARY', 40, 1, 0, 0);

CREATE TABLE IF NOT EXISTS ticket_panels (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id   TEXT    NOT NULL,
    message_id   TEXT    NOT NULL DEFAULT '',
    title        TEXT    NOT NULL DEFAULT 'Open a ticket',
    description  TEXT    NOT NULL DEFAULT 'Pick a category below to open a ticket. Staff will reply here in Discord and on the dashboard.',
    color_hex    TEXT    NOT NULL DEFAULT '#5865F2',
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tickets (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id              INTEGER,
    discord_id               TEXT    NOT NULL,
    discord_username         TEXT    NOT NULL DEFAULT '',
    subject                  TEXT    NOT NULL,
    body                     TEXT    NOT NULL,
    status                   TEXT    NOT NULL DEFAULT 'open',
    assignee_id              TEXT    NOT NULL DEFAULT '',
    assignee_name            TEXT    NOT NULL DEFAULT '',
    staff_channel_id         TEXT    NOT NULL DEFAULT '',
    staff_message_id         TEXT    NOT NULL DEFAULT '',
    last_activity_at         INTEGER NOT NULL,
    created_at               INTEGER NOT NULL,
    closed_at                INTEGER,
    mode                     TEXT    NOT NULL DEFAULT 'dm',
    channel_id               TEXT    NOT NULL DEFAULT '',
    transcript_path          TEXT    NOT NULL DEFAULT '',
    transcript_token         TEXT    NOT NULL DEFAULT '',
    transcript_generated_at  INTEGER,
    mirror_channel_id        TEXT    NOT NULL DEFAULT '',
    mirror_webhook_id        TEXT    NOT NULL DEFAULT '',
    mirror_webhook_token     TEXT    NOT NULL DEFAULT '',
    FOREIGN KEY (category_id) REFERENCES ticket_categories(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS tickets_status_idx   ON tickets(status);
CREATE INDEX IF NOT EXISTS tickets_user_idx     ON tickets(discord_id);
CREATE INDEX IF NOT EXISTS tickets_activity_idx ON tickets(last_activity_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS tickets_transcript_token_idx
    ON tickets(transcript_token) WHERE transcript_token != '';

CREATE TABLE IF NOT EXISTS ticket_messages (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id    INTEGER NOT NULL,
    author_kind  TEXT    NOT NULL,
    author_id    TEXT    NOT NULL DEFAULT '',
    author_name  TEXT    NOT NULL DEFAULT '',
    body         TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    attachments  TEXT    NOT NULL DEFAULT '',
    FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ticket_messages_ticket_idx ON ticket_messages(ticket_id, id);

-- =====================================================================
-- FEEDBACK
-- =====================================================================

CREATE TABLE IF NOT EXISTS feedback_config (
    id                          INTEGER PRIMARY KEY,
    channel_id                  TEXT    NOT NULL DEFAULT '',
    open_via_command            INTEGER NOT NULL DEFAULT 1,
    dm_reporter_on_status       INTEGER NOT NULL DEFAULT 1,
    dm_reporter_on_response     INTEGER NOT NULL DEFAULT 1,
    require_unique_per_user     INTEGER NOT NULL DEFAULT 0,
    locked_when_resolved        INTEGER NOT NULL DEFAULT 1
);
INSERT OR IGNORE INTO feedback_config (id) VALUES (1);

CREATE TABLE IF NOT EXISTS feedback (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_id          TEXT    NOT NULL,
    discord_username    TEXT    NOT NULL DEFAULT '',
    title               TEXT    NOT NULL,
    body                TEXT    NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'open',
    staff_response      TEXT    NOT NULL DEFAULT '',
    channel_id          TEXT    NOT NULL DEFAULT '',
    message_id          TEXT    NOT NULL DEFAULT '',
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    closed_at           INTEGER
);
CREATE INDEX IF NOT EXISTS feedback_status_idx  ON feedback(status);
CREATE INDEX IF NOT EXISTS feedback_updated_idx ON feedback(updated_at DESC);

CREATE TABLE IF NOT EXISTS feedback_votes (
    feedback_id  INTEGER NOT NULL,
    discord_id   TEXT    NOT NULL,
    vote         INTEGER NOT NULL,
    voted_at     INTEGER NOT NULL,
    PRIMARY KEY (feedback_id, discord_id),
    FOREIGN KEY (feedback_id) REFERENCES feedback(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS feedback_votes_user_idx ON feedback_votes(discord_id);

CREATE TABLE IF NOT EXISTS feedback_notes (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    feedback_id  INTEGER NOT NULL,
    author_kind  TEXT    NOT NULL,
    author_id    TEXT    NOT NULL DEFAULT '',
    author_name  TEXT    NOT NULL DEFAULT '',
    body         TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    FOREIGN KEY (feedback_id) REFERENCES feedback(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS feedback_notes_feedback_idx ON feedback_notes(feedback_id, id);

-- =====================================================================
-- PAGE ACCESS
-- =====================================================================

CREATE TABLE IF NOT EXISTS page_access (
    page_key       TEXT    PRIMARY KEY,
    role_ids_json  TEXT    NOT NULL DEFAULT '[]'
);
INSERT OR IGNORE INTO page_access (page_key, role_ids_json) VALUES
    ('stats',          '[]'),
    ('pending',        '[]'),
    ('audit',          '[]'),
    ('members',        '[]'),
    ('invites',        '[]'),
    ('moderation',     '[]'),
    ('violations',     '[]'),
    ('levels',         '[]'),
    ('reaction-roles', '[]'),
    ('engagement',     '[]'),
    ('tickets',        '[]'),
    ('feedback',       '[]'),
    ('alerts',         '[]'),
    ('autoresponders', '[]'),
    ('scheduler',      '[]');

-- =====================================================================
-- TIMEZONES + EVENT SCHEDULER
-- =====================================================================

CREATE TABLE IF NOT EXISTS user_timezones (
    discord_id  TEXT PRIMARY KEY,
    tz_id       TEXT NOT NULL,
    source      TEXT NOT NULL DEFAULT 'manual',
    updated_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS timezone_config (
    id                   INTEGER PRIMARY KEY,
    onboarding_required  INTEGER NOT NULL DEFAULT 0,
    geoip_enabled        INTEGER NOT NULL DEFAULT 0,
    scheduler_enabled    INTEGER NOT NULL DEFAULT 1
);
INSERT OR IGNORE INTO timezone_config (id) VALUES (1);

CREATE TABLE IF NOT EXISTS scheduled_events (
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    title                       TEXT NOT NULL,
    description                 TEXT NOT NULL DEFAULT '',
    starts_at_utc               INTEGER NOT NULL,
    duration_minutes            INTEGER NOT NULL DEFAULT 60,
    creator_id                  TEXT NOT NULL,
    creator_name                TEXT NOT NULL DEFAULT '',
    target_roles_json           TEXT NOT NULL DEFAULT '[]',
    discord_announce_channel_id TEXT NOT NULL DEFAULT '',
    discord_announce_message_id TEXT NOT NULL DEFAULT '',
    created_at                  INTEGER NOT NULL,
    cancelled_at                INTEGER
);
CREATE INDEX IF NOT EXISTS scheduled_events_starts_idx ON scheduled_events(starts_at_utc);

CREATE TABLE IF NOT EXISTS scheduled_event_rsvps (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id      INTEGER NOT NULL,
    discord_id    TEXT NOT NULL,
    rsvp          TEXT NOT NULL,
    responded_at  INTEGER NOT NULL,
    UNIQUE(event_id, discord_id),
    FOREIGN KEY (event_id) REFERENCES scheduled_events(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS scheduled_event_rsvps_event_idx ON scheduled_event_rsvps(event_id);
CREATE INDEX IF NOT EXISTS scheduled_event_rsvps_user_idx  ON scheduled_event_rsvps(discord_id);

-- =====================================================================
-- AUTORESPONDERS
-- =====================================================================

CREATE TABLE IF NOT EXISTS autoresponders (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    name                 TEXT    NOT NULL DEFAULT '',
    enabled              INTEGER NOT NULL DEFAULT 1,
    match_mode           TEXT    NOT NULL DEFAULT 'contains',
    pattern              TEXT    NOT NULL DEFAULT '',
    case_insensitive     INTEGER NOT NULL DEFAULT 1,
    response_mode        TEXT    NOT NULL DEFAULT 'content',
    content              TEXT    NOT NULL DEFAULT '',
    embed_title          TEXT    NOT NULL DEFAULT '',
    embed_description    TEXT    NOT NULL DEFAULT '',
    embed_color          TEXT    NOT NULL DEFAULT '#5865F2',
    embed_image_url      TEXT    NOT NULL DEFAULT '',
    embed_thumbnail_url  TEXT    NOT NULL DEFAULT '',
    embed_author_name    TEXT    NOT NULL DEFAULT '',
    embed_author_icon    TEXT    NOT NULL DEFAULT '',
    embed_footer_text    TEXT    NOT NULL DEFAULT '',
    embed_footer_icon    TEXT    NOT NULL DEFAULT '',
    extra_image_urls     TEXT    NOT NULL DEFAULT '[]',
    allow_channel_ids    TEXT    NOT NULL DEFAULT '[]',
    deny_channel_ids     TEXT    NOT NULL DEFAULT '[]',
    allow_role_ids       TEXT    NOT NULL DEFAULT '[]',
    deny_role_ids        TEXT    NOT NULL DEFAULT '[]',
    cooldown_seconds     INTEGER NOT NULL DEFAULT 0,
    reply_to_trigger     INTEGER NOT NULL DEFAULT 0,
    delete_trigger       INTEGER NOT NULL DEFAULT 0,
    mention_author       INTEGER NOT NULL DEFAULT 0,
    priority             INTEGER NOT NULL DEFAULT 0,
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS autoresponders_priority_idx ON autoresponders(priority, id);

-- =====================================================================
-- DEBUG REPORTS
-- =====================================================================
-- Encrypted plugin-state snapshots generated on demand from /dash/debug.
-- The decrypt_key (base64url AES-256) is stored here so the dashboard
-- can reconstruct share URLs; the actual payload is encrypted so the
-- key material in this row is the only thing that unlocks it.
CREATE TABLE IF NOT EXISTS debug_reports (
    id               TEXT    PRIMARY KEY,
    created_at       INTEGER NOT NULL,
    label            TEXT    NOT NULL DEFAULT '',
    encrypted_payload TEXT   NOT NULL,
    decrypt_key      TEXT    NOT NULL,
    analysis_status  TEXT    NOT NULL DEFAULT 'pending'
);
CREATE INDEX IF NOT EXISTS debug_reports_created_idx ON debug_reports(created_at DESC);

-- =====================================================================
-- API KEYS
-- =====================================================================
-- Bearer tokens that grant scoped, read/write access to /api/v1/*.
-- Plaintext secrets are never stored: we keep an SHA-256 hash of the
-- full token plus a short prefix so the UI can show which key is which.
CREATE TABLE IF NOT EXISTS api_keys (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    label         TEXT    NOT NULL DEFAULT '',
    prefix        TEXT    NOT NULL,
    token_hash    TEXT    NOT NULL UNIQUE,
    scopes_json   TEXT    NOT NULL DEFAULT '[]',
    created_by    TEXT    NOT NULL DEFAULT '',
    created_at    INTEGER NOT NULL,
    last_used_at  INTEGER,
    revoked_at    INTEGER
);
CREATE INDEX IF NOT EXISTS api_keys_prefix_idx ON api_keys(prefix);
CREATE INDEX IF NOT EXISTS api_keys_revoked_idx ON api_keys(revoked_at);

-- =====================================================================
-- SETTINGS SEED + DEFAULTS
-- =====================================================================

INSERT OR IGNORE INTO settings (id) VALUES (1);

UPDATE settings
   SET rules_markdown = '# Server Rules

1. **Be kind.** No harassment, slurs, threats, or doxxing.
2. **No spam or unsolicited DMs.** Don''t promote external servers.
3. **Keep NSFW out.** This is a safe-for-work community.
4. **Use channels for their purpose.** Read pins before posting.
5. **Respect mod decisions.** Disputes go to DMs with a mod, not the channel.

By continuing you confirm you understand and will follow these rules.'
 WHERE id = 1 AND rules_markdown = '';

UPDATE settings
   SET llm_system_prompt = 'You are a Discord community moderator triaging a new applicant''s onboarding answers.

You will receive: the server''s published rules, the configured questions, and the applicant''s answers (with account-age metadata).

Decide whether this person should be auto-approved, auto-denied, or escalated to human moderators.

Return ONLY a single JSON object - no prose, no code fences - with this exact shape:
{
  "decision": "approve" | "deny" | "escalate",
  "confidence": <number from 0 to 1>,
  "reasoning": "<one short paragraph explaining your reasoning for the mod log>"
}

Guidance:
- "approve" with high confidence (>=0.85): the answers are coherent, on-topic, and show genuine intent to participate within the rules.
- "deny" with high confidence (>=0.85): the answers contain slurs, threats, obvious troll/spam patterns, explicit rule violations, or admit to being a banned user evading. Use a LOW confidence value when uncertain - denial is severe.
- "escalate" otherwise: when answers are ambiguous, terse-but-not-malicious, mention edge-case interests, or the account is suspiciously new without other red flags.
- Confidence reflects YOUR certainty in the decision, not the probability of approval. Be honest: when you don''t know, set confidence near 0.5 and pick "escalate".'
 WHERE id = 1 AND llm_system_prompt = '';

UPDATE settings
   SET delivery_message_template = 'Hey **{username}**, welcome to **{guild_name}**!

Before you can see the rest of the server, we need to ask you a few quick questions.

{entry_options}{code_note}'
 WHERE id = 1 AND delivery_message_template = '';

UPDATE settings
   SET approve_dm_template = 'You''re in - welcome to **{guild_name}**, {username}! Have a look around, say hi, read the pins.'
 WHERE id = 1 AND approve_dm_template = '';

UPDATE settings
   SET approve_channel_template = 'Everyone welcome {mention} to the server!'
 WHERE id = 1 AND approve_channel_template = '';

UPDATE settings
   SET deny_dm_template = 'Thanks for applying to **{guild_name}**, but we''re not able to approve your application at this time. You''re free to try again later.'
 WHERE id = 1 AND deny_dm_template = '';

UPDATE settings
   SET landing_features_json = '[' ||
       '{"icon":"shield","title":"Land protection","body":"Claim your builds with a simple in-game tool. No griefing, no rollbacks, no drama."},' ||
       '{"icon":"sun","title":"Quality of life","body":"Sleep through nights together, set homes, teleport to friends, and use a tidy economy."},' ||
       '{"icon":"shop","title":"Player shops","body":"Open a stall at spawn, sell what you craft, or just window-shop the latest builds."},' ||
       '{"icon":"globe","title":"Multiple modes","body":"Hop between survival, SkyBlock, and seasonal events without leaving the server."},' ||
       '{"icon":"users","title":"Active community","body":"Voice channels, weekly events, and staff who actually play on the server."},' ||
       '{"icon":"grid","title":"Fair and bot-checked","body":"New joiners pass a quick onboarding step so the door stays open without letting raiders in."}' ||
       ']'
 WHERE id = 1 AND (landing_features_json IS NULL OR landing_features_json = '' OR landing_features_json = '[]');

UPDATE settings
   SET landing_faqs_json = '[' ||
       '{"question":"How do I connect?","answer":"Copy the address above, open Minecraft, add a new server, and paste it in. That is the whole process."},' ||
       '{"question":"Which Minecraft version do you run?","answer":"The latest stable Paper release. Older clients usually connect fine thanks to ViaVersion."},' ||
       '{"question":"Do I need to apply?","answer":"No application. First-time joiners run through a short onboarding step in Discord so we can keep raiders out."},' ||
       '{"question":"Can I bring friends?","answer":"Always. Bring a whole guild if you like. Co-op projects make the server better."}' ||
       ']'
 WHERE id = 1 AND (landing_faqs_json IS NULL OR landing_faqs_json = '' OR landing_faqs_json = '[]');

-- Default question set: only seeded when the questions table is empty.
INSERT INTO questions (order_index, prompt, kind, choices_json, required, active)
SELECT 0, 'How did you find this server?', 'short_text', '[]', 1, 1
 WHERE NOT EXISTS (SELECT 1 FROM questions);

INSERT INTO questions (order_index, prompt, kind, choices_json, required, active)
SELECT 1, 'What do you hope to get out of being here? (a sentence or two is fine)', 'long_text', '[]', 1, 1
 WHERE (SELECT COUNT(*) FROM questions) = 1;

INSERT INTO questions (order_index, prompt, kind, choices_json, required, active)
SELECT 2, 'Have you read and do you agree to follow the server rules?', 'single_choice', '["Yes, I agree","No / I have questions"]', 1, 1
 WHERE (SELECT COUNT(*) FROM questions) = 2;

INSERT INTO questions (order_index, prompt, kind, choices_json, required, active)
SELECT 3, 'Are you an alt of an existing member, or returning after being removed? (Honesty here makes mods more lenient - we''d rather know than find out.)', 'long_text', '[]', 0, 1
 WHERE (SELECT COUNT(*) FROM questions) = 3;
