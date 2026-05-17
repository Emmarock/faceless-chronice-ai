-- Tracks whether the user has explicitly chosen a plan (paid OR a deliberate
-- "stay on Free" click). The frontend gates non-billing routes on this so
-- brand-new sign-ups always see the pricing page first.
--
-- Existing rows are backfilled to 1 so users created before this column
-- existed aren't kicked through the onboarding flow.

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'subscriptions'
      AND COLUMN_NAME = 'plan_selected'
);

SET @stmt := IF(
    @col_exists = 0,
    'ALTER TABLE subscriptions ADD COLUMN plan_selected TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1'
);

PREPARE add_col FROM @stmt;
EXECUTE add_col;
DEALLOCATE PREPARE add_col;

-- Backfill: anyone who already had a Subscription row before this migration
-- ran was already using the product, so treat them as onboarded.
UPDATE subscriptions SET plan_selected = 1 WHERE plan_selected = 0;