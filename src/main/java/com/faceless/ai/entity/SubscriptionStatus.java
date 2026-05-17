package com.faceless.ai.entity;

/**
 * Lifecycle of a {@link Subscription} row, kept in sync with Stripe's own
 * subscription state via the {@code customer.subscription.*} webhooks.
 *
 * <p>FREE plans always sit in {@link #ACTIVE} — they have no Stripe-side state
 * and never lapse. Paid plans transition through {@link #INCOMPLETE} during
 * the initial checkout, {@link #ACTIVE} once an invoice is paid,
 * {@link #PAST_DUE} when a renewal payment fails, and {@link #CANCELED}
 * when the user cancels or the subscription is otherwise terminated.
 */
public enum SubscriptionStatus {
    ACTIVE,
    INCOMPLETE,
    PAST_DUE,
    CANCELED
}