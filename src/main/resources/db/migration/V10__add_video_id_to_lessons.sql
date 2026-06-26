-- Link a completed lesson to a `videos` row so AI-tutor lessons can be
-- cross-posted through the existing publish pipeline (VideoPublishService /
-- SocialUploadConsumer), exactly like documentary job renders. The referenced
-- Video has a NULL job_id — it's a lesson render, not a job.
ALTER TABLE lessons
    ADD COLUMN video_id BINARY(16) NULL;
