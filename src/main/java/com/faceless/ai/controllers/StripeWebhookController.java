package com.faceless.ai.controllers;

import com.faceless.ai.config.BillingProperties;
import com.faceless.ai.entity.LedgerKind;
import com.faceless.ai.entity.PlanCode;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.repository.PlanRepository;
import com.faceless.ai.repository.SubscriptionRepository;
import com.faceless.ai.service.SubscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Optional;

/**
 * Receives Stripe webhook deliveries. Mounted on a dedicated path so the
 * (forthcoming) auth middleware can be configured to skip {@code X-USER}
 * for this route — Stripe authenticates itself via the
 * {@code Stripe-Signature} header, not our header convention.
 *
 * <h3>Events handled</h3>
 * <ul>
 *   <li>{@code checkout.session.completed} — first-time upgrade. Look up the
 *       subscription from the session's {@code subscription} field, flip
 *       the plan to the one we tagged in metadata.</li>
 *   <li>{@code customer.subscription.updated} — plan change, cancellation
 *       scheduled, period boundary moved. We mirror these into our row.</li>
 *   <li>{@code customer.subscription.deleted} — terminal. User goes back to
 *       FREE; existing credits aren't clawed back.</li>
 *   <li>{@code invoice.paid} — credit grant. Drops the plan's monthly
 *       allowance into the ledger. Idempotent via the invoice id.</li>
 *   <li>{@code invoice.payment_failed} — flag the subscription PAST_DUE so
 *       the UI can prompt for a card update.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/billing/webhooks/stripe")
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    private final BillingProperties billingProperties;
    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final ObjectMapper objectMapper;

    @PostMapping(consumes = "application/json")
    public ResponseEntity<String> receive(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {
        String webhookSecret = billingProperties.getStripe().getWebhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("Stripe webhook received but chronicleai.billing.stripe.webhook-secret is not set.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Stripe webhook secret not configured.");
        }
        if (sigHeader == null) {
            return ResponseEntity.badRequest().body("Missing Stripe-Signature.");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature mismatch: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature.");
        }

        try {
            dispatch(event, payload);
        } catch (Exception e) {
            // Swallow + 500. Stripe will retry; we don't want a partial-state
            // panic to leave a stuck event blocking the queue.
            log.error("Failed to handle Stripe webhook {} ({}): {}",
                    event.getId(), event.getType(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Handler error.");
        }
        return ResponseEntity.ok("ok");
    }

    private void dispatch(Event event, String rawPayload) throws Exception {
        switch (event.getType()) {
            case "checkout.session.completed":      handleCheckoutCompleted(event, rawPayload); break;
            case "customer.subscription.updated":   handleSubscriptionUpdated(event, rawPayload); break;
            case "customer.subscription.deleted":   handleSubscriptionDeleted(event, rawPayload); break;
            case "invoice.paid":                    handleInvoicePaid(event, rawPayload); break;
            case "invoice.payment_failed":          handleInvoiceFailed(event, rawPayload); break;
            default:
                log.debug("Ignoring Stripe event type {}", event.getType());
        }
    }

    private void handleCheckoutCompleted(Event event, String raw) throws Exception {
        JsonNode obj = readObject(raw);
        String customerId       = textOrNull(obj, "customer");
        String subscriptionId   = textOrNull(obj, "subscription");
        String targetPlanString = obj.path("metadata").path("targetPlan").asText(null);
        if (subscriptionId == null || customerId == null || targetPlanString == null) {
            log.warn("checkout.session.completed missing fields: customer={}, sub={}, plan={}",
                    customerId, subscriptionId, targetPlanString);
            return;
        }
        PlanCode target = PlanCode.valueOf(targetPlanString);
        Subscription sub = subscriptionRepository.findByStripeCustomerId(customerId).orElse(null);
        if (sub == null) {
            log.warn("checkout.session.completed for unknown customer {}", customerId);
            return;
        }
        Instant periodStart = parseEpoch(obj, "current_period_start");
        Instant periodEnd   = parseEpoch(obj, "current_period_end");
        subscriptionService.applyPlanFromStripe(
                sub, target, subscriptionId, customerId, periodStart, periodEnd, false);
        log.info("Activated {} for AppUser {} via checkout session {}",
                target, sub.getAppUser().getId(), event.getId());
    }

    private void handleSubscriptionUpdated(Event event, String raw) throws Exception {
        JsonNode obj = readObject(raw);
        String subscriptionId = textOrNull(obj, "id");
        if (subscriptionId == null) return;
        Subscription sub = subscriptionRepository.findByStripeSubscriptionId(subscriptionId).orElse(null);
        if (sub == null) {
            log.warn("subscription.updated for unknown sub {}", subscriptionId);
            return;
        }
        String priceId = obj.path("items").path("data").path(0).path("price").path("id").asText(null);
        PlanCode target = priceId == null ? sub.getPlan().getCode() : planForPriceId(priceId, sub.getPlan().getCode());
        Instant periodStart = parseEpoch(obj, "current_period_start");
        Instant periodEnd   = parseEpoch(obj, "current_period_end");
        boolean cancelAtEnd = obj.path("cancel_at_period_end").asBoolean(false);
        subscriptionService.applyPlanFromStripe(
                sub, target, subscriptionId, textOrNull(obj, "customer"),
                periodStart, periodEnd, cancelAtEnd);
    }

    private void handleSubscriptionDeleted(Event event, String raw) throws Exception {
        JsonNode obj = readObject(raw);
        String subscriptionId = textOrNull(obj, "id");
        if (subscriptionId == null) return;
        subscriptionRepository.findByStripeSubscriptionId(subscriptionId)
                .ifPresent(sub -> {
                    subscriptionService.cancelSubscription(sub);
                    log.info("Canceled subscription {} — AppUser {} back to FREE", subscriptionId, sub.getAppUser().getId());
                });
    }

    private void handleInvoicePaid(Event event, String raw) throws Exception {
        JsonNode obj = readObject(raw);
        String invoiceId       = textOrNull(obj, "id");
        String subscriptionId  = textOrNull(obj, "subscription");
        if (invoiceId == null || subscriptionId == null) return;
        Optional<Subscription> sub = subscriptionRepository.findByStripeSubscriptionId(subscriptionId);
        if (sub.isEmpty()) {
            log.warn("invoice.paid for unknown sub {}", subscriptionId);
            return;
        }
        Subscription s = sub.get();
        Integer grant = s.getPlan().getMonthlyCreditGrant();
        if (grant == null || grant <= 0) {
            log.debug("invoice.paid: plan {} has no monthly grant — skipping", s.getPlan().getCode());
            return;
        }
        subscriptionService.grantCredits(
                s, grant, LedgerKind.GRANT_MONTHLY,
                "Stripe invoice " + invoiceId + " (" + s.getPlan().getCode() + ")",
                invoiceId);
    }

    private void handleInvoiceFailed(Event event, String raw) throws Exception {
        JsonNode obj = readObject(raw);
        String subscriptionId = textOrNull(obj, "subscription");
        if (subscriptionId == null) return;
        subscriptionRepository.findByStripeSubscriptionId(subscriptionId)
                .ifPresent(subscriptionService::markPastDue);
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /**
     * The Stripe SDK's deserialized {@code event.getDataObjectDeserializer()}
     * is sensitive to SDK version drift. Parsing the raw payload's
     * {@code data.object} ourselves is cheaper and version-independent —
     * we only need a handful of fields.
     */
    private JsonNode readObject(String rawPayload) throws Exception {
        return objectMapper.readTree(rawPayload).path("data").path("object");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Instant parseEpoch(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.canConvertToLong()) return null;
        return Instant.ofEpochSecond(v.asLong());
    }

    /**
     * Reverse-lookup the {@link PlanCode} for a Stripe price id by matching
     * the {@code plans} table. Falls back to {@code fallback} when the
     * price id is unknown — e.g. an experimental price we haven't seeded
     * yet — so a stray webhook doesn't blow up the user's plan state.
     */
    private PlanCode planForPriceId(String priceId, PlanCode fallback) {
        // O(n) scan over ~5 rows — fine, and avoids adding a repository method
        // we'd only use here.
        return planRepository.findAll().stream()
                .filter(p -> priceId.equals(p.getStripePriceId()))
                .map(p -> p.getCode())
                .findFirst()
                .orElse(fallback);
    }
}