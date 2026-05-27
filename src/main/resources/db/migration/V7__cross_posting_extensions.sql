-- Cross-posting expansion (2026-05-27).
--
-- Adds:
--  * scheduled_at      — when set, the SocialUpload is deferred until this
--                        instant. The PostScheduler drains rows with
--                        status=SCHEDULED AND scheduled_at <= now to SQS.
--  * title / caption / hashtags — per-platform overrides the user typed into
--                        the redesigned PublishModal. Persisted on the row
--                        so scheduled publishes can fire hours later with
--                        the captions the user originally chose.
--  * video_format       — REELS | VIDEO. Used by per-platform validation to
--                        warn about format mismatches (e.g. 9:16 to LinkedIn).
--  * source_type        — VIDEO | ASSET. Lets the scheduler dispatch the
--                        correct SocialUploadEvent variant without re-
--                        checking the videos / assets tables.
--
-- The (video_id, platform) UK is unchanged — we still dedupe one upload per
-- (source, platform). The new status,scheduled_at index covers the
-- scheduler's polling query.
ALTER TABLE social_uploads
    ADD COLUMN scheduled_at DATETIME(6) NULL,
    ADD COLUMN title        VARCHAR(500) NULL,
    ADD COLUMN caption      TEXT         NULL,
    ADD COLUMN hashtags     VARCHAR(2000) NULL,
    ADD COLUMN video_format VARCHAR(32)  NULL,
    ADD COLUMN source_type  VARCHAR(32)  NULL;

CREATE INDEX idx_social_uploads_status_scheduled
    ON social_uploads (status, scheduled_at);

-- Provider-side stable account id used by Instagram (the IG Business
-- Account id, distinct from the human-readable handle) and LinkedIn
-- (the member URN, e.g. "urn:li:person:abc123"). Both platforms need
-- this id on every upload call and we'd rather not re-fetch it on each
-- request. accountHandle stays the *display* name; this column is the
-- *machine* identifier.
ALTER TABLE social_connections
    ADD COLUMN provider_account_id VARCHAR(255) NULL;