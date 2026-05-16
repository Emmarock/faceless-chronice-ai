-- Adds the video_format column used by the REELS vs. VIDEO toggle on
-- /create-job. Existing rows backfill to 'VIDEO' so historical jobs keep
-- behaving as long-form (their stored scripts already match that shape).
--
-- Done via dynamic SQL so this migration is safe to re-run against dev
-- databases where Hibernate's ddl-auto=update may have already created the
-- column. (MySQL pre-8.0.29 lacks ADD COLUMN IF NOT EXISTS, hence the
-- INFORMATION_SCHEMA guard.)

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'jobs'
      AND COLUMN_NAME = 'video_format'
);

SET @stmt := IF(
    @col_exists = 0,
    'ALTER TABLE jobs ADD COLUMN video_format VARCHAR(16) NULL',
    'SELECT 1'
);

PREPARE add_col FROM @stmt;
EXECUTE add_col;
DEALLOCATE PREPARE add_col;

-- Backfill nulls to 'VIDEO' so reads can rely on a non-null value going
-- forward. New rows continue to allow NULL for forward-compat; the service
-- layer treats null as VIDEO anyway.
UPDATE jobs SET video_format = 'VIDEO' WHERE video_format IS NULL;