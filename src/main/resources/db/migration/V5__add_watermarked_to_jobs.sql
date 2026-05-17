-- Marker for whether the job's final video should carry a watermark.
-- Stamped at creation from the user's plan (FREE → watermarked) so the
-- output is deterministic regardless of upgrades during the pipeline.
--
-- Defensive against dev DBs where Hibernate's ddl-auto=update may have
-- already added the column.

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'jobs'
      AND COLUMN_NAME = 'watermarked'
);

SET @stmt := IF(
    @col_exists = 0,
    'ALTER TABLE jobs ADD COLUMN watermarked TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1'
);

PREPARE add_col FROM @stmt;
EXECUTE add_col;
DEALLOCATE PREPARE add_col;