package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Catalog row for a pricing tier.
 *
 * <p>Owned by config + a seed migration — the values here are not edited at
 * runtime. The row exists so subscriptions can FK to a plan and so the
 * pricing page has a single source of truth for display copy / amounts.
 *
 * <p>Stripe price IDs are kept here (rather than in {@code application.yaml})
 * so the same deployment can serve multiple environments with different
 * Stripe accounts — flip the row, no redeploy. {@link #stripePriceId} is null
 * for {@link PlanCode#FREE} and {@link PlanCode#ENTERPRISE} (neither has a
 * self-serve checkout).
 */
@Entity
@Table(name = "plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Plan extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 32)
    private PlanCode code;

    @Column(nullable = false, length = 64)
    private String displayName;

    /** Short tagline rendered under the plan name on the pricing page. */
    @Column(length = 255)
    private String tagline;

    /** Monthly price in cents (USD). 0 for FREE, NULL for ENTERPRISE. */
    @Column(name = "monthly_price_cents")
    private Integer monthlyPriceCents;

    /** Credits granted on every monthly renewal. NULL for ENTERPRISE. */
    @Column(name = "monthly_credit_grant")
    private Integer monthlyCreditGrant;

    /**
     * Stripe Price ID for the monthly recurring product
     * (e.g. {@code price_1Ab...}). Null for non-self-serve tiers.
     */
    @Column(name = "stripe_price_id", length = 128)
    private String stripePriceId;

    /** Whether to flag this card as "Most popular" on the pricing page. */
    @Column(name = "is_highlighted", nullable = false)
    private boolean highlighted;
}