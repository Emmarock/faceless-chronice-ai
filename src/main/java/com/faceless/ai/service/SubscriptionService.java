package com.faceless.ai.service;

import com.faceless.ai.config.BillingProperties;
import com.faceless.ai.entity.AppUser;
import com.faceless.ai.entity.CreditLedger;
import com.faceless.ai.entity.LedgerKind;
import com.faceless.ai.entity.Plan;
import com.faceless.ai.entity.PlanCode;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.entity.SubscriptionStatus;
import com.faceless.ai.exceptions.InsufficientCreditsException;
import com.faceless.ai.repository.AppUserRepository;
import com.faceless.ai.repository.CreditLedgerRepository;
import com.faceless.ai.repository.PlanRepository;
import com.faceless.ai.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns subscription lifecycle + the credit ledger.
 *
 * <p>Every other piece of the billing stack (controllers, webhooks, AI hooks)
 * funnels through this class so the invariants — "every AppUser has exactly
 * one Subscription", "every balance change writes a ledger row" — live in
 * one place.
 *
 * <h3>Idempotency</h3>
 * Stripe webhook handlers pass a {@code stripeReference} (event / invoice id)
 * to {@link #grantCredits} so a redelivered event doesn't double-credit the
 * user. The same hook is used by {@link #consume} for refund replay safety.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final CreditLedgerRepository creditLedgerRepository;
    private final AppUserRepository appUserRepository;
    private final BillingProperties billingProperties;

    // ------------------------------------------------------------------ //
    //  Read APIs
    // ------------------------------------------------------------------ //

    /**
     * Returns the user's subscription, creating a FREE one on first read
     * along with the welcome credit grant. This is the single entry point
     * every other caller uses — there is no "no subscription" state.
     */
    @Transactional
    public Subscription getOrCreateForUser(AppUser user) {
        return subscriptionRepository.findByAppUser_Id(user.getId())
                .orElseGet(() -> bootstrapFreeSubscription(user));
    }

    /**
     * Convenience for callers that have only the external id (X-USER header).
     * Throws if no AppUser exists — call sites that need to auto-provision
     * should hit UserService first.
     */
    @Transactional
    public Subscription getOrCreateForExternalId(String externalId) {
        AppUser user = appUserRepository.findByExternalId(externalId)
                .orElseThrow(() -> new IllegalStateException(
                        "No AppUser for externalId " + externalId + " — sign in before accessing billing."));
        return getOrCreateForUser(user);
    }

    // ------------------------------------------------------------------ //
    //  Credit grants (positive ledger rows)
    // ------------------------------------------------------------------ //

    /**
     * Adds {@code amount} credits and writes a ledger row. No-ops (returns
     * the existing row) if a ledger row with the same {@code stripeReference}
     * already exists — Stripe redelivers events liberally and we mustn't
     * double-credit.
     */
    @Transactional
    public Subscription grantCredits(Subscription subscription,
                                     int amount,
                                     LedgerKind kind,
                                     String memo,
                                     String stripeReference) {
        if (amount <= 0) {
            throw new IllegalArgumentException("grantCredits amount must be positive, got " + amount);
        }
        if (stripeReference != null && !stripeReference.isBlank()) {
            Optional<CreditLedger> existing = creditLedgerRepository.findByStripeReference(stripeReference);
            if (existing.isPresent()) {
                log.info("Skipping duplicate Stripe credit grant for ref {}", stripeReference);
                return subscription;
            }
        }
        writeLedger(subscription, kind, amount, memo, null, stripeReference);
        subscription.setCreditBalance(subscription.getCreditBalance() + amount);
        return subscriptionRepository.save(subscription);
    }

    // ------------------------------------------------------------------ //
    //  Credit consumption (negative ledger rows)
    // ------------------------------------------------------------------ //

    /**
     * Debits the configured cost for {@code kind}, throwing
     * {@link InsufficientCreditsException} if the balance would go negative.
     *
     * <p>Called from the AI pipeline at the point of consumption. The
     * caller passes the optional {@code jobId} so per-job cost reports stay
     * accurate.
     */
    @Transactional
    public Subscription consume(Subscription subscription, LedgerKind kind, String memo, UUID jobId) {
        int cost = billingProperties.costFor(kind);
        if (cost <= 0) {
            throw new IllegalArgumentException(
                    "consume() called with a non-debit kind: " + kind
                            + " — only DEBIT_* kinds carry a configured cost.");
        }
        if (subscription.getCreditBalance() < cost) {
            throw new InsufficientCreditsException(
                    "Out of credits — need " + cost + " for " + kind
                            + ", have " + subscription.getCreditBalance() + ".");
        }
        writeLedger(subscription, kind, -cost, memo, jobId, null);
        subscription.setCreditBalance(subscription.getCreditBalance() - cost);
        return subscriptionRepository.save(subscription);
    }

    /**
     * Returns true when the user has enough credits for the given action,
     * without debiting. Cheap front-of-pipeline guard so we don't kick off
     * a job we can't pay for.
     */
    public boolean canAfford(Subscription subscription, LedgerKind kind) {
        return subscription.getCreditBalance() >= billingProperties.costFor(kind);
    }

    /**
     * Charges the flat per-format job budget at job creation. Single hook
     * for "starter" enforcement — the user is debited once, fails fast if
     * the balance is too low, and granular DEBIT_* rows can layer on top
     * later without changing the contract.
     */
    @Transactional
    public Subscription consumeJobBudget(Subscription subscription,
                                         com.faceless.ai.model.VideoFormat format,
                                         UUID jobId) {
        int cost = billingProperties.budgetFor(format);
        if (cost <= 0) return subscription;
        if (subscription.getCreditBalance() < cost) {
            throw new InsufficientCreditsException(
                    "Out of credits — need " + cost + " for a " + format + " job, "
                            + "have " + subscription.getCreditBalance() + ".");
        }
        writeLedger(subscription, LedgerKind.DEBIT_JOB_BUDGET, -cost,
                "Job budget (" + format + ")", jobId, null);
        subscription.setCreditBalance(subscription.getCreditBalance() - cost);
        return subscriptionRepository.save(subscription);
    }

    // ------------------------------------------------------------------ //
    //  Plan transitions (called from webhooks)
    // ------------------------------------------------------------------ //

    /**
     * Activates a paid plan for a user after a successful Stripe checkout /
     * subscription update. Idempotent: callers can pass the same Stripe
     * subscription id on every webhook delivery without piling on credits.
     */
    @Transactional
    public Subscription applyPlanFromStripe(Subscription subscription,
                                            PlanCode newPlan,
                                            String stripeSubscriptionId,
                                            String stripeCustomerId,
                                            Instant periodStart,
                                            Instant periodEnd,
                                            boolean cancelAtPeriodEnd) {
        Plan target = planRepository.findByCode(newPlan)
                .orElseThrow(() -> new IllegalStateException("Missing plan row for " + newPlan));
        subscription.setPlan(target);
        subscription.setStripeSubscriptionId(stripeSubscriptionId);
        if (stripeCustomerId != null && !stripeCustomerId.isBlank()) {
            subscription.setStripeCustomerId(stripeCustomerId);
        }
        subscription.setCurrentPeriodStart(periodStart);
        subscription.setCurrentPeriodEnd(periodEnd);
        subscription.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        subscription.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        // Any path that lands here is a deliberate plan pick (Stripe checkout
        // success or admin reapply) — mark onboarded so the new-user redirect
        // releases this user.
        subscription.setPlanSelected(true);
        return subscriptionRepository.save(subscription);
    }

    /**
     * Marks a subscription as canceled and drops it back to FREE so the user
     * doesn't lose access entirely. Called from
     * {@code customer.subscription.deleted}.
     */
    @Transactional
    public Subscription cancelSubscription(Subscription subscription) {
        Plan free = planRepository.findByCode(PlanCode.FREE)
                .orElseThrow(() -> new IllegalStateException("Missing FREE plan row"));
        subscription.setPlan(free);
        subscription.setStripeSubscriptionId(null);
        subscription.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        subscription.setCancelAtPeriodEnd(false);
        return subscriptionRepository.save(subscription);
    }

    @Transactional
    public Subscription markPastDue(Subscription subscription) {
        subscription.setSubscriptionStatus(SubscriptionStatus.PAST_DUE);
        return subscriptionRepository.save(subscription);
    }

    /**
     * Marks the subscription as onboarded without changing anything else.
     * Used when a new user explicitly clicks "Continue with Free" on the
     * pricing page — their plan stays FREE but the new-user redirect now
     * releases them on subsequent visits.
     *
     * <p>Idempotent: re-calling it on an already-onboarded subscription is
     * a no-op save (Hibernate may still hit the DB but the value is
     * unchanged).
     */
    @Transactional
    public Subscription confirmPlan(Subscription subscription) {
        if (subscription.isPlanSelected()) return subscription;
        subscription.setPlanSelected(true);
        return subscriptionRepository.save(subscription);
    }

    /**
     * Activates a paid plan without going through Stripe — used when the
     * {@code billing.payments-required} feature flag is false (typically
     * during early product life when Stripe isn't set up yet).
     *
     * <p>Grants the plan's monthly credit allowance with a deterministic
     * ledger reference of {@code "demo-<sub>-<plan>-YYYY-MM"} so repeated
     * clicks within the same calendar month are idempotent — but the next
     * month the user can re-activate for a fresh allowance, mimicking a
     * real subscription's monthly renewal.
     *
     * <p>{@code stripeSubscriptionId} is cleared so a later flag flip to
     * {@code true} doesn't leave a phantom Stripe reference behind.
     */
    @Transactional
    public Subscription applyPlanWithoutPayment(Subscription subscription, PlanCode newPlan) {
        if (newPlan == PlanCode.FREE) {
            throw new IllegalArgumentException(
                    "Use the regular subscription flow to drop to FREE — applyPlanWithoutPayment is for paid plans only.");
        }
        if (newPlan == PlanCode.ENTERPRISE) {
            throw new IllegalArgumentException(
                    "ENTERPRISE plans are provisioned manually via sales — they cannot be self-served, paid or otherwise.");
        }
        Plan target = planRepository.findByCode(newPlan)
                .orElseThrow(() -> new IllegalStateException("Missing plan row for " + newPlan));

        Instant now = Instant.now();
        Instant periodEnd = now.plusSeconds(30L * 24 * 60 * 60);
        subscription.setPlan(target);
        subscription.setStripeSubscriptionId(null);
        subscription.setCurrentPeriodStart(now);
        subscription.setCurrentPeriodEnd(periodEnd);
        subscription.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        subscription.setCancelAtPeriodEnd(false);
        // Preview-mode activation is the user actively picking a paid plan —
        // mark onboarded so the new-user redirect releases them.
        subscription.setPlanSelected(true);
        subscription = subscriptionRepository.save(subscription);

        Integer grant = target.getMonthlyCreditGrant();
        if (grant != null && grant > 0) {
            String yearMonth = java.time.YearMonth.now().toString(); // YYYY-MM
            String ref = "demo-" + subscription.getId() + "-" + newPlan + "-" + yearMonth;
            subscription = grantCredits(
                    subscription, grant, LedgerKind.GRANT_MONTHLY,
                    "Demo activation of " + newPlan + " (no payment, billing.payments-required=false)",
                    ref);
        }
        return subscription;
    }

    // ------------------------------------------------------------------ //
    //  Internals
    // ------------------------------------------------------------------ //

    private Subscription bootstrapFreeSubscription(AppUser user) {
        Plan free = planRepository.findByCode(PlanCode.FREE)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing FREE plan row — did the V4 migration run?"));
        Subscription sub = Subscription.builder()
                .appUser(user)
                .plan(free)
                .subscriptionStatus(SubscriptionStatus.ACTIVE)
                .creditBalance(0)
                .cancelAtPeriodEnd(false)
                .createdBy(user.getExternalId())
                .lastModifiedBy(user.getExternalId())
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();
        sub = subscriptionRepository.save(sub);
        log.info("Created FREE subscription for AppUser {} ({})", user.getId(), user.getExternalId());

        int welcome = billingProperties.getWelcomeGrant();
        int monthly = free.getMonthlyCreditGrant() != null ? free.getMonthlyCreditGrant() : 0;
        int initial = welcome + monthly;
        if (initial > 0) {
            sub = grantCredits(sub, initial, LedgerKind.GRANT_WELCOME,
                    "Welcome grant (" + welcome + " welcome + " + monthly + " free-tier monthly)", null);
        }
        return sub;
    }

    private CreditLedger writeLedger(Subscription subscription,
                                     LedgerKind kind,
                                     int signedAmount,
                                     String memo,
                                     UUID jobId,
                                     String stripeReference) {
        CreditLedger row = CreditLedger.builder()
                .subscription(subscription)
                .kind(kind)
                .amount(signedAmount)
                .memo(memo)
                .jobId(jobId)
                .stripeReference(stripeReference)
                .createdBy(subscription.getAppUser().getExternalId())
                .lastModifiedBy(subscription.getAppUser().getExternalId())
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();
        return creditLedgerRepository.save(row);
    }
}