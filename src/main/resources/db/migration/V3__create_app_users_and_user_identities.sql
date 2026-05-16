-- Foundational identity tables.
--
-- app_users is one row per human user of the application. Today it's filled
-- lazily on every OAuth connect (no signup form yet). external_id mirrors the
-- X-USER header value the frontend already sends, so every existing table
-- whose userId/createdBy column references that string can be joined back to
-- this row without a backfill.
--
-- user_identities is one row per (human, OAuth provider) pair. The natural
-- key (provider, provider_user_id) is the durable identifier OAuth providers
-- mint; everything else on the row is a snapshot we refresh on every connect.
--
-- Both tables use the same BINARY(16) / DATETIME(6) shape as V1 so they slot
-- in alongside the existing schema.

CREATE TABLE IF NOT EXISTS app_users (
    id                     BINARY(16)   NOT NULL,
    external_id            VARCHAR(255) NOT NULL,
    email                  VARCHAR(320),
    display_name           VARCHAR(255),
    avatar_url             VARCHAR(1024),
    first_seen_provider    VARCHAR(32),
    status                 VARCHAR(32),
    created_by             VARCHAR(255),
    created_on             DATETIME(6)  NOT NULL,
    last_modified_by       VARCHAR(255),
    last_modified_on       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_app_users_external_id UNIQUE (external_id),
    CONSTRAINT uk_app_users_email       UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS user_identities (
    id                     BINARY(16)   NOT NULL,
    app_user_id            BINARY(16)   NOT NULL,
    provider               VARCHAR(32)  NOT NULL,
    provider_user_id       VARCHAR(255) NOT NULL,
    email                  VARCHAR(320),
    display_name           VARCHAR(255),
    avatar_url             VARCHAR(1024),
    last_seen_at           DATETIME(6),
    status                 VARCHAR(32),
    created_by             VARCHAR(255),
    created_on             DATETIME(6)  NOT NULL,
    last_modified_by       VARCHAR(255),
    last_modified_on       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_user_identities_provider_provider_user_id UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_user_identities_app_user FOREIGN KEY (app_user_id)
        REFERENCES app_users (id) ON DELETE CASCADE
);