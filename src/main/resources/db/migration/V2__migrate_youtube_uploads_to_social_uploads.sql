-- Move existing YouTube upload rows into the unified social_uploads table.
--
-- youtube_uploads.video_id is *supposed* to be unique but the original
-- @Column(unique = true) wasn't always enforced as a real UK on TiDB, so the
-- table can contain multiple rows for the same video_id. We dedupe via
-- ROW_NUMBER() and keep the most recent row per video_id.
--
-- ON DUPLICATE KEY UPDATE makes this safe to re-run if a prior attempt
-- partially committed (TiDB DDL/DML transactional semantics differ from
-- vanilla MySQL).
--
-- IMPORTANT: assumes youtube_uploads.status is stored as VARCHAR. If your
-- live DB has it as INTEGER (ordinal), wrap the status column in a CASE.
--
-- For fresh DBs that never had youtube_uploads, set FLYWAY_ENABLED=false on
-- the very first boot OR pre-create an empty youtube_uploads table; the
-- INSERT becomes a no-op and the DROP IF EXISTS handles the rest.
INSERT INTO social_uploads (
    id, video_id, platform, provider_post_id, status,
    uploaded_at, created_by, created_on, last_modified_by, last_modified_on
)
SELECT
    id, video_id, 'YOUTUBE', youtube_video_id, status,
    uploaded_at, created_by, created_on, last_modified_by, last_modified_on
FROM (
    SELECT
        id, video_id, youtube_video_id, status, uploaded_at,
        created_by, created_on, last_modified_by, last_modified_on,
        ROW_NUMBER() OVER (
            PARTITION BY video_id
            ORDER BY COALESCE(uploaded_at, last_modified_on, created_on) DESC, id DESC
        ) AS rn
    FROM youtube_uploads
) ranked
WHERE rn = 1
ON DUPLICATE KEY UPDATE
    provider_post_id = VALUES(provider_post_id),
    status           = VALUES(status),
    uploaded_at      = VALUES(uploaded_at),
    last_modified_on = VALUES(last_modified_on);

DROP TABLE IF EXISTS youtube_uploads;