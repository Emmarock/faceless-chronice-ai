package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * One row per {@link AppUser}. Tracks which {@link Plan} the user is on and
 * the Stripe-side identifiers we need to manage their subscription.
 *
 * <p>Even {@link PlanCode#FREE} users have a row here so the credit balance,
 * monthly reset, and customer-id are stored in one place — there is no
 * "no subscription" state in this system, only different plans.
 *
 * <p>The credit balance lives on this row (rather than being recomputed from
 * the ledger every read) because:
 * <ul>
 *   <li>It's hit on every metered AI action — denormalising avoids a
 *       {@code SUM(amount)} query in the hot path.</li>
 *   <li>The ledger is still the source of truth — a reconciliation job can
 *       always rebuild this column.</li>
 * </ul>
 */
@Entity
@Table(
        name = "subscriptions",
        uniqueConstraints = @UniqueConstraint(name = "uk_subscriptions_app_user", columnNames = "app_user_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Subscription extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser appUser;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 32)
    private SubscriptionStatus subscriptionStatus;

    /**
     * Denormalised current balance. Always equals
     * {@code SUM(credit_ledger.amount WHERE subscription_id = this.id)} when
     * the system is consistent.
     */
    @Column(name = "credit_balance", nullable = false)
    private int creditBalance;

    /** Stripe Customer ID (e.g. {@code cus_...}). Created lazily at first checkout. */
    @Column(name = "stripe_customer_id", length = 128)
    private String stripeCustomerId;

    /** Stripe Subscription ID (e.g. {@code sub_...}). Null on FREE. */
    @Column(name = "stripe_subscription_id", length = 128)
    private String stripeSubscriptionId;

    /** Start of the current billing period. Drives the monthly credit grant. */
    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    /** End of the current billing period. Stripe's reported end. */
    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    /**
     * When the user requested cancellation. Stripe keeps the subscription
     * {@link SubscriptionStatus#ACTIVE} until {@link #currentPeriodEnd}, so we
     * flag the intent here for UI display ("Cancels on Mar 5").
     */
    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    /**
     * True once the user has <em>actively picked</em> a plan — either by
     * upgrading via Stripe / preview-mode activation, or by explicitly
     * choosing to stay on FREE on the pricing page.
     *
     * <p>The first-time-onboarding redirect ({@code RequirePlanSelected} on
     * the frontend) gates every non-billing route until this flips true,
     * so brand-new sign-ups always see the pricing page first.
     */
    @Column(name = "plan_selected", nullable = false)
    private boolean planSelected;
}