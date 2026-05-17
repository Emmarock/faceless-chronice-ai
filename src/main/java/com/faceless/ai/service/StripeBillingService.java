package com.faceless.ai.service;

import com.faceless.ai.config.BillingProperties;
import com.faceless.ai.entity.AppUser;
import com.faceless.ai.entity.Plan;
import com.faceless.ai.entity.PlanCode;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.repository.PlanRepository;
import com.faceless.ai.repository.SubscriptionRepository;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.billingportal.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import com.stripe.param.checkout.SessionCreateParams.Mode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Thin wrapper around the Stripe SDK.
 *
 * <p>This service is the only place in the codebase that imports
 * {@code com.stripe.*} (aside from the webhook controller's signature
 * verification). Everything else takes Subscriptions in / out and never
 * touches Stripe directly — keeps the blast radius small if we ever swap
 * providers.
 *
 * <h3>Customer lifecycle</h3>
 * Stripe Customers are created lazily on the first checkout, not on FREE
 * subscription creation, so a user who never upgrades never costs a Stripe
 * "customer" record.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StripeBillingService {

    private final BillingProperties billingProperties;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;

    @PostConstruct
    void init() {
        if (billingProperties.getStripe().isConfigured()) {
            Stripe.apiKey = billingProperties.getStripe().getSecretKey();
            // Backfill Stripe price IDs from config onto the seeded plan rows
            // on every boot. Idempotent: same value writes are no-ops.
            applyPriceIdsFromConfig();
        } else {
            log.warn("Stripe is not configured (chronicleai.billing.stripe.secret-key is blank). "
                    + "Checkout / portal / webhook endpoints will refuse traffic until you set it.");
        }
    }

    private void applyPriceIdsFromConfig() {
        Map<String, String> priceIds = billingProperties.getPlans().nonBlankPriceIds();
        for (Map.Entry<String, String> e : priceIds.entrySet()) {
            PlanCode code = PlanCode.valueOf(e.getKey());
            planRepository.findByCode(code).ifPresent(plan -> {
                if (!e.getValue().equals(plan.getStripePriceId())) {
                    plan.setStripePriceId(e.getValue());
                    planRepository.save(plan);
                    log.info("Backfilled Stripe price id for {} from config", code);
                }
            });
        }
    }

    /**
     * Creates (or returns) the Stripe Customer for this subscription. We
     * stamp the AppUser id on the customer's metadata so the webhook handler
     * can route events back without an extra DB query.
     */
    @Transactional
    public String ensureStripeCustomer(Subscription subscription) throws StripeException {
        if (!billingProperties.getStripe().isConfigured()) {
            throw new IllegalStateException("Stripe is not configured.");
        }
        if (subscription.getStripeCustomerId() != null && !subscription.getStripeCustomerId().isBlank()) {
            return subscription.getStripeCustomerId();
        }
        AppUser user = subscription.getAppUser();
        CustomerCreateParams.Builder params = CustomerCreateParams.builder()
                .putMetadata("appUserId",   user.getId().toString())
                .putMetadata("externalId",  user.getExternalId());
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            params.setEmail(user.getEmail());
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            params.setName(user.getDisplayName());
        }
        Customer customer = Customer.create(params.build());
        subscription.setStripeCustomerId(customer.getId());
        subscriptionRepository.save(subscription);
        log.info("Created Stripe customer {} for AppUser {}", customer.getId(), user.getId());
        return customer.getId();
    }

    /**
     * Mints a Stripe Checkout Session URL for the given plan. The frontend
     * redirects the user there; Stripe handles card collection and fires
     * {@code checkout.session.completed} once the user pays.
     */
    public String createCheckoutSession(Subscription subscription, PlanCode targetPlan) throws StripeException {
        if (!billingProperties.getStripe().isConfigured()) {
            throw new IllegalStateException("Stripe is not configured.");
        }
        if (targetPlan == PlanCode.FREE || targetPlan == PlanCode.ENTERPRISE) {
            throw new IllegalArgumentException("Plan " + targetPlan + " is not self-serve.");
        }
        Plan plan = planRepository.findByCode(targetPlan)
                .orElseThrow(() -> new IllegalStateException("Plan row missing: " + targetPlan));
        if (plan.getStripePriceId() == null || plan.getStripePriceId().isBlank()) {
            throw new IllegalStateException(
                    "No Stripe price id configured for " + targetPlan
                            + " — set chronicleai.billing.plans." + targetPlan.name().toLowerCase() + "-price-id.");
        }

        String customerId = ensureStripeCustomer(subscription);
        com.stripe.param.checkout.SessionCreateParams params = com.stripe.param.checkout.SessionCreateParams.builder()
                .setMode(Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .setSuccessUrl(billingProperties.getStripe().getSuccessUrl())
                .setCancelUrl(billingProperties.getStripe().getCancelUrl())
                .addLineItem(LineItem.builder()
                        .setPrice(plan.getStripePriceId())
                        .setQuantity(1L)
                        .build())
                // Pass the AppUser id through so the webhook can correlate
                // even if Stripe ever changes the customer→subscription mapping.
                .putMetadata("appUserId", subscription.getAppUser().getId().toString())
                .putMetadata("targetPlan", targetPlan.name())
                .build();
        Session session = Session.create(params);
        return session.getUrl();
    }

    /**
     * Mints a Stripe Customer Portal URL so the user can manage their
     * subscription (update card, cancel, view invoices) on Stripe's hosted
     * pages. Returns the URL; caller is responsible for redirecting.
     */
    public String createPortalSession(Subscription subscription) throws StripeException {
        if (!billingProperties.getStripe().isConfigured()) {
            throw new IllegalStateException("Stripe is not configured.");
        }
        if (subscription.getStripeCustomerId() == null || subscription.getStripeCustomerId().isBlank()) {
            throw new IllegalStateException(
                    "User has no Stripe customer — they haven't checked out yet.");
        }
        SessionCreateParams params = SessionCreateParams.builder()
                .setCustomer(subscription.getStripeCustomerId())
                .setReturnUrl(billingProperties.getStripe().getPortalReturnUrl())
                .build();
        com.stripe.model.billingportal.Session session = com.stripe.model.billingportal.Session.create(params);
        return session.getUrl();
    }
}