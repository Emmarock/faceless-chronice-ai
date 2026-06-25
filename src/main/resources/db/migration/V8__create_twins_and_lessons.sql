-- AI Tutor Twin vertical (2026-06-25).
--
-- twins   — one row per HeyGen avatar + cloned voice trained from a short clip
--           the user recorded. Async training: created QUEUED, advanced to
--           PROCESSING then COMPLETED (or FAILED) by TwinLessonPoller as
--           HeyGen finishes. The ready heygen_avatar_id / heygen_voice_id pair
--           is reused to render any number of lessons.
--
-- lessons — one row per generated lesson video (a twin teaching a topic).
--           Claude writes the spoken script, HeyGen renders it, and the
--           finished MP4 is stored in S3 (video_url). Same async status
--           progression, driven by the same poller.
--
-- BINARY(16) / DATETIME(6) shapes match V1..V7 so these slot in alongside the
-- existing schema. status/created_*/last_modified_* mirror BaseEntity.

CREATE TABLE IF NOT EXISTS twins (
    id                 BINARY(16)    NOT NULL,
    user_id            VARCHAR(255)  NOT NULL,
    name               VARCHAR(255)  NOT NULL,
    source_video_url   VARCHAR(1024),
    heygen_training_id VARCHAR(255),
    heygen_avatar_id   VARCHAR(255),
    heygen_voice_id    VARCHAR(255),
    error_message      VARCHAR(2000),
    status             VARCHAR(32),
    created_by         VARCHAR(255),
    created_on         DATETIME(6)   NOT NULL,
    last_modified_by   VARCHAR(255),
    last_modified_on   DATETIME(6),
    PRIMARY KEY (id)
);

CREATE INDEX idx_twins_user_created ON twins (user_id, created_on);
CREATE INDEX idx_twins_status       ON twins (status);

CREATE TABLE IF NOT EXISTS lessons (
    id                 BINARY(16)    NOT NULL,
    user_id            VARCHAR(255)  NOT NULL,
    twin_id            BINARY(16)    NOT NULL,
    topic              VARCHAR(1000) NOT NULL,
    style              VARCHAR(255),
    script_content     TEXT,
    heygen_video_id    VARCHAR(255),
    video_url          VARCHAR(1024),
    duration_seconds   INT,
    error_message      VARCHAR(2000),
    status             VARCHAR(32),
    created_by         VARCHAR(255),
    created_on         DATETIME(6)   NOT NULL,
    last_modified_by   VARCHAR(255),
    last_modified_on   DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_lessons_twin FOREIGN KEY (twin_id) REFERENCES twins (id)
);

CREATE INDEX idx_lessons_user_created ON lessons (user_id, created_on);
CREATE INDEX idx_lessons_status       ON lessons (status);
