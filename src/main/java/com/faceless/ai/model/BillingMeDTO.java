package com.faceless.ai.model;

import com.faceless.ai.entity.PlanCode;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.entity.SubscriptionStatus;

import java.time.Instant;

/**
 * Snapshot of the caller's current billing state — the payload behind
 * {@code GET /api/billing/me}. Renders the credit chip in the header and the
 * /billing page.
 */
public record BillingMeDTO(
        PlanCode planCode,
        String planDisplayName,
        SubscriptionStatus status,
        int creditBalance,
        Integer monthlyCreditGrant,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        boolean hasStripeCustomer,
        /** Server-side flag — drives whether the frontend calls Stripe Checkout
         *  or the no-payment activation endpoint. */
        boolean paymentsRequired,
        /** True once the user has actively picked a plan (paid or explicit Free).
         *  Gates the new-user onboarding redirect on the frontend. */
        boolean planSelected) {

    public static BillingMeDTO from(Subscription s, boolean paymentsRequired) {
        return new BillingMeDTO(
                s.getPlan().getCode(),
                s.getPlan().getDisplayName(),
                s.getSubscriptionStatus(),
                s.getCreditBalance(),
                s.getPlan().getMonthlyCreditGrant(),
                s.getCurrentPeriodStart(),
                s.getCurrentPeriodEnd(),
                s.isCancelAtPeriodEnd(),
                s.getStripeCustomerId() != null && !s.getStripeCustomerId().isBlank(),
                paymentsRequired,
                s.isPlanSelected());
    }
}