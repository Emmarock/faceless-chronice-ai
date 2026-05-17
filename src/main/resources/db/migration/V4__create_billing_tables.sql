-- Billing foundation: plan catalog, per-user subscription, append-only
-- credit ledger.
--
-- Notes:
--   * Stripe price IDs intentionally live on the `plans` row (not in app
--     config) so different deploys can point at different Stripe accounts
--     by editing a single row.
--   * subscriptions has a one-to-one with app_users (uk_subscriptions_app_user)
--     — every signed-in user has exactly one row; the row is created with
--     PlanCode.FREE on first read.
--   * credit_ledger is append-only. The subscriptions.credit_balance column
--     is a denormalised cache of SUM(amount); the ledger is the source of
--     truth and can rebuild it.
--
-- Seed values match the in-app pricing copy. Stripe price IDs are filled in
-- from chronicleai.billing.stripe.* config on first boot via DataLoader —
-- this migration leaves them NULL so a fresh checkout fails fast if config
-- is missing, rather than silently sending users to the wrong price.

CREATE TABLE IF NOT EXISTS plans (
    id                     BINARY(16)   NOT NULL,
    code                   VARCHAR(32)  NOT NULL,
    display_name           VARCHAR(64)  NOT NULL,
    tagline                VARCHAR(255),
    monthly_price_cents    INT,
    monthly_credit_grant   INT,
    stripe_price_id        VARCHAR(128),
    is_highlighted         TINYINT(1)   NOT NULL DEFAULT 0,
    status                 VARCHAR(32),
    created_by             VARCHAR(255),
    created_on             DATETIME(6)  NOT NULL,
    last_modified_by       VARCHAR(255),
    last_modified_on       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_plans_code UNIQUE (code)
);

INSERT IGNORE INTO plans
    (id, code, display_name, tagline, monthly_price_cents, monthly_credit_grant, stripe_price_id, is_highlighted, created_on)
VALUES
    (UNHEX(REPLACE(UUID(),'-','')), 'FREE',       'Free',       'Try the platform with a small monthly credit pool.', 0,    100,   NULL, 0, NOW(6)),
    (UNHEX(REPLACE(UUID(),'-','')), 'CREATOR',    'Creator',    'For solo creators shipping a few videos a week.',    1500,  625,   NULL, 0, NOW(6)),
    (UNHEX(REPLACE(UUID(),'-','')), 'PRO',        'Pro',        'For prolific creators and small studios.',           3500,  2250,  NULL, 1, NOW(6)),
    (UNHEX(REPLACE(UUID(),'-','')), 'UNLIMITED',  'Unlimited',  'Effectively unlimited credits for power users.',     9500,  10000, NULL, 0, NOW(6)),
    (UNHEX(REPLACE(UUID(),'-','')), 'ENTERPRISE', 'Enterprise', 'Custom plans for teams. Contact sales.',             NULL,  NULL,  NULL, 0, NOW(6));


CREATE TABLE IF NOT EXISTS subscriptions (
    id                     BINARY(16)   NOT NULL,
    app_user_id            BINARY(16)   NOT NULL,
    plan_id                BINARY(16)   NOT NULL,
    subscription_status    VARCHAR(32)  NOT NULL,
    credit_balance         INT          NOT NULL DEFAULT 0,
    stripe_customer_id     VARCHAR(128),
    stripe_subscription_id VARCHAR(128),
    current_period_start   DATETIME(6),
    current_period_end     DATETIME(6),
    cancel_at_period_end   TINYINT(1)   NOT NULL DEFAULT 0,
    status                 VARCHAR(32),
    created_by             VARCHAR(255),
    created_on             DATETIME(6)  NOT NULL,
    last_modified_by       VARCHAR(255),
    last_modified_on       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_subscriptions_app_user           UNIQUE (app_user_id),
    CONSTRAINT uk_subscriptions_stripe_subscription UNIQUE (stripe_subscription_id),
    CONSTRAINT fk_subscriptions_app_user FOREIGN KEY (app_user_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT fk_subscriptions_plan     FOREIGN KEY (plan_id)     REFERENCES plans      (id)
);


CREATE TABLE IF NOT EXISTS credit_ledger (
    id                     BINARY(16)   NOT NULL,
    subscription_id        BINARY(16)   NOT NULL,
    kind                   VARCHAR(32)  NOT NULL,
    amount                 INT          NOT NULL,
    memo                   VARCHAR(255),
    job_id                 BINARY(16),
    stripe_reference       VARCHAR(128),
    status                 VARCHAR(32),
    created_by             VARCHAR(255),
    created_on             DATETIME(6)  NOT NULL,
    last_modified_by       VARCHAR(255),
    last_modified_on       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_credit_ledger_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions (id) ON DELETE CASCADE,
    KEY ix_credit_ledger_subscription (subscription_id),
    KEY ix_credit_ledger_job          (job_id),
    KEY ix_credit_ledger_stripe_ref   (stripe_reference)
);