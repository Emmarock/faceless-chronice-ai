-- Unified per-(video, platform) upload record. Replaces the per-platform
-- youtube_uploads table. The (video_id, platform) UK is the dedupe lock used
-- by every *UploadConsumer to make duplicate SQS deliveries safe.
CREATE TABLE IF NOT EXISTS social_uploads (
    id                 BINARY(16)   NOT NULL,
    video_id           BINARY(16),
    platform           VARCHAR(32),
    provider_post_id   VARCHAR(255),
    status             VARCHAR(32),
    uploaded_at        DATETIME(6),
    created_by         VARCHAR(255),
    created_on         DATETIME(6)  NOT NULL,
    last_modified_by   VARCHAR(255),
    last_modified_on   DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_social_uploads_video_platform UNIQUE (video_id, platform)
);