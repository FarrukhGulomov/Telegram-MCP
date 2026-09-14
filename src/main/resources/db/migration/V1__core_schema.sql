-- Consent-gated Telegram MCP: core schema.
-- Every content-bearing table carries tenant_id and is indexed tenant-first so that
-- no query path can accidentally cross a tenant boundary.

CREATE TABLE tenants (
    id           uuid PRIMARY KEY,
    slug         varchar(64)  NOT NULL UNIQUE,
    name         varchar(200) NOT NULL,
    demo_mode    boolean      NOT NULL DEFAULT true,
    created_at   timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE app_users (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email         varchar(320) NOT NULL,
    password_hash varchar(200) NOT NULL,
    display_name  varchar(200) NOT NULL,
    role          varchar(32)  NOT NULL,          -- OWNER | ADMIN | MEMBER
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT app_users_email_unique UNIQUE (email)
);
CREATE INDEX app_users_tenant_idx ON app_users (tenant_id);

-- A Telegram *client session* is broader than read-only permission. It is tracked
-- separately from any authorization to process chat content (chat_activations).
CREATE TABLE telegram_connections (
    id                uuid PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    owner_user_id     uuid        NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    adapter           varchar(16) NOT NULL,        -- MOCK | TDLIB
    status            varchar(32) NOT NULL,        -- DISCONNECTED | WAIT_PHONE | WAIT_CODE | WAIT_PASSWORD | WAIT_QR | READY | ERROR
    tg_user_id        bigint,
    tg_username       varchar(64),
    -- AES-GCM ciphertext of the adapter's session material. The key lives outside the
    -- database (env / KMS); only the key id is stored here.
    session_ciphertext bytea,
    session_key_id    varchar(64),
    last_error        varchar(500),
    connected_at      timestamptz,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT telegram_connections_one_per_user UNIQUE (tenant_id, owner_user_id)
);
CREATE INDEX telegram_connections_tenant_idx ON telegram_connections (tenant_id);

CREATE TABLE chats (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    connection_id  uuid         NOT NULL REFERENCES telegram_connections(id) ON DELETE CASCADE,
    tg_chat_id     bigint       NOT NULL,
    title          varchar(300) NOT NULL,
    chat_type      varchar(32)  NOT NULL,          -- GROUP | SUPERGROUP | PRIVATE | CHANNEL
    username       varchar(64),                    -- public @username, when the chat has one
    member_count   integer,
    discovered_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chats_unique UNIQUE (tenant_id, connection_id, tg_chat_id)
);
CREATE INDEX chats_tenant_idx ON chats (tenant_id);

-- Authorization to *process* a chat's content. Separate from the connection above.
CREATE TABLE chat_activations (
    id                     uuid PRIMARY KEY,
    tenant_id              uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    chat_id                uuid        NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    state                  varchar(32) NOT NULL,   -- INACTIVE | AWAITING_CONSENT | ACTIVE | PAUSED | REVOKED
    purpose                varchar(500) NOT NULL,
    provider_scope         varchar(200) NOT NULL,  -- comma separated: ANTHROPIC_CLAUDE,OPENAI_CHATGPT
    consent_version        varchar(32) NOT NULL,
    approved_from          timestamptz NOT NULL,
    approved_to            timestamptz NOT NULL,
    retention_days         integer     NOT NULL,
    membership_fingerprint varchar(64),            -- sha-256 over the sorted participant id set
    pause_reason           varchar(200),
    activated_at           timestamptz,
    activated_by_user_id   uuid REFERENCES app_users(id),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chat_activations_chat_unique UNIQUE (chat_id),
    CONSTRAINT chat_activations_range CHECK (approved_to > approved_from),
    CONSTRAINT chat_activations_retention CHECK (retention_days BETWEEN 1 AND 3650)
);
CREATE INDEX chat_activations_tenant_state_idx ON chat_activations (tenant_id, state);

CREATE TABLE chat_participants (
    id              uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    chat_id         uuid         NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    tg_user_id      bigint       NOT NULL,
    display_name    varchar(200) NOT NULL,
    is_bot          boolean      NOT NULL DEFAULT false,
    first_seen_at   timestamptz  NOT NULL DEFAULT now(),
    left_at         timestamptz,
    CONSTRAINT chat_participants_unique UNIQUE (tenant_id, chat_id, tg_user_id)
);
CREATE INDEX chat_participants_tenant_chat_idx ON chat_participants (tenant_id, chat_id);

-- One row per participant per chat per consent version. An administrator cannot
-- silently consent for someone else: recorded_by_user_id plus method make the
-- provenance of every record explicit and auditable.
CREATE TABLE consent_records (
    id                        uuid PRIMARY KEY,
    tenant_id                 uuid         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    chat_id                   uuid         NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    participant_tg_user_id    bigint       NOT NULL,
    participant_display_name  varchar(200) NOT NULL,
    purpose                   varchar(500) NOT NULL,
    provider_scope            varchar(200) NOT NULL,
    consent_version           varchar(32)  NOT NULL,
    method                    varchar(48)  NOT NULL, -- SELF_SERVICE_LINK | SIGNED_ATTESTATION | DEMO_SYNTHETIC
    evidence_ref              varchar(300),
    granted_at                timestamptz  NOT NULL,
    expires_at                timestamptz,
    revoked_at                timestamptz,
    revoked_reason            varchar(300),
    recorded_by_user_id       uuid REFERENCES app_users(id),
    created_at                timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX consent_records_lookup_idx
    ON consent_records (tenant_id, chat_id, participant_tg_user_id, granted_at DESC);

CREATE TABLE messages (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    chat_id             uuid        NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    tg_message_id       bigint      NOT NULL,
    sender_tg_user_id   bigint,
    sender_display_name varchar(200),
    sent_at             timestamptz NOT NULL,
    edited_at           timestamptz,
    deleted_at          timestamptz,
    -- Message text is stored as opaque data. It is never treated as instructions and
    -- never rendered as trusted markup.
    content             text        NOT NULL DEFAULT '',
    content_tsv         tsvector GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED,
    reply_to_tg_message_id bigint,
    source_link         varchar(500),
    ingested_at         timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT messages_unique UNIQUE (tenant_id, chat_id, tg_message_id)
);
CREATE INDEX messages_history_idx ON messages (tenant_id, chat_id, sent_at, tg_message_id);
CREATE INDEX messages_search_idx  ON messages USING gin (content_tsv);
CREATE INDEX messages_retention_idx ON messages (tenant_id, sent_at);

CREATE TABLE sync_checkpoints (
    id                       uuid PRIMARY KEY,
    tenant_id                uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    chat_id                  uuid        NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    newest_synced_message_id bigint,
    oldest_synced_message_id bigint,
    synced_from              timestamptz,
    synced_to                timestamptz,
    backfill_complete        boolean     NOT NULL DEFAULT false,
    last_attempt_at          timestamptz,
    last_success_at          timestamptz,
    consecutive_failures     integer     NOT NULL DEFAULT 0,
    next_attempt_at          timestamptz,
    last_error_code          varchar(48),
    last_error_message       varchar(500),
    halted                   boolean     NOT NULL DEFAULT false,  -- set on non-retryable auth/permission failures
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT sync_checkpoints_chat_unique UNIQUE (chat_id)
);
CREATE INDEX sync_checkpoints_tenant_idx ON sync_checkpoints (tenant_id);

CREATE TABLE audit_events (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    actor_type   varchar(24) NOT NULL,   -- UI_USER | MCP_CLIENT | SYSTEM
    actor_id     varchar(200),
    action       varchar(64) NOT NULL,
    chat_id      uuid,
    outcome      varchar(24) NOT NULL,   -- ALLOWED | DENIED | ERROR
    -- Access *metadata* only. Message text is never written here.
    detail       jsonb       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_events_tenant_time_idx ON audit_events (tenant_id, occurred_at DESC);
