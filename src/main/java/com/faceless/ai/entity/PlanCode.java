package com.faceless.ai.entity;

/**
 * Canonical pricing tier identifiers.
 *
 * <p>Values are wire-stable strings persisted in the {@code plans} table and
 * referenced by Stripe price-id config keys. Adding a new tier means a new
 * enum value AND a new row in {@code plans} (or a config-driven seed update).
 *
 * <p>{@link #ENTERPRISE} is intentionally not self-serve — its row exists for
 * display on the pricing page only; checkout is gated behind a "contact sales"
 * mailto and provisioned manually.
 */
public enum PlanCode {
    FREE,
    CREATOR,
    PRO,
    UNLIMITED,
    ENTERPRISE
}