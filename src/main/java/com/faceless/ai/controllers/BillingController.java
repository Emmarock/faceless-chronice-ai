package com.faceless.ai.controllers;

import com.faceless.ai.config.BillingProperties;
import com.faceless.ai.entity.AppUser;
import com.faceless.ai.entity.PlanCode;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.model.BillingMeDTO;
import com.faceless.ai.model.CheckoutRequest;
import com.faceless.ai.model.PlanDTO;
import com.faceless.ai.model.RedirectUrlDTO;
import com.faceless.ai.repository.AppUserRepository;
import com.faceless.ai.repository.PlanRepository;
import com.faceless.ai.service.StripeBillingService;
import com.faceless.ai.service.SubscriptionService;
import com.stripe.exception.StripeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Self-serve billing endpoints. Mirrors the structure of the other
 * controllers: identifies the caller via the {@code X-USER} header, hands
 * off to a service.
 *
 * <p>{@code GET /plans} is intentionally public (no {@code X-USER}) so the
 * /pricing page works for logged-out visitors — it just lists the catalog.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private final PlanRepository planRepository;
    private final SubscriptionService subscriptionService;
    private final StripeBillingService stripeBillingService;
    private final AppUserRepository appUserRepository;
    private final BillingProperties billingProperties;

    @GetMapping("/plans")
    public ResponseEntity<List<PlanDTO>> listPlans() {
        return ResponseEntity.ok(
                planRepository.findAllByOrderByMonthlyPriceCentsAsc().stream()
                        .map(PlanDTO::from)
                        .toList());
    }

    /**
     * Returns the caller's current subscription. Auto-provisions a FREE
     * subscription (and an AppUser when missing) on first call so the
     * /pricing → /billing redirect flow works for legacy users who predate
     * the AppUser table.
     */
    @GetMapping("/me")
    public ResponseEntity<BillingMeDTO> me(@RequestHeader("X-USER") String externalId) {
        AppUser user = appUserRepository.findByExternalId(externalId)
                .orElseGet(() -> appUserRepository.save(
                        AppUser.builder()
                                .externalId(externalId)
                                .createdBy(externalId)
                                .lastModifiedBy(externalId)
                                .createdOn(Instant.now())
                                .lastModifiedOn(Instant.now())
                                .build()));
        Subscription sub = subscriptionService.getOrCreateForUser(user);
        return ResponseEntity.ok(BillingMeDTO.from(sub, billingProperties.isPaymentsRequired()));
    }

    @PostMapping("/checkout")
    public ResponseEntity<RedirectUrlDTO> checkout(@RequestHeader("X-USER") String externalId,
                                                   @RequestBody CheckoutRequest request) {
        if (!billingProperties.isPaymentsRequired()) {
            // Loud refusal so misrouted clients get a clear signal — silently
            // delegating to /activate-plan would mask bugs in the frontend
            // mode-detection code.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payments are not enabled on this deployment — use /api/billing/activate-plan instead.");
        }
        if (request.plan() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "plan is required.");
        }
        Subscription sub = subscriptionService.getOrCreateForExternalId(externalId);
        try {
            String url = stripeBillingService.createCheckoutSession(sub, request.plan());
            return ResponseEntity.ok(new RedirectUrlDTO(url));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (StripeException e) {
            log.warn("Stripe checkout creation failed for {}: {}", externalId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe rejected the checkout request.", e);
        }
    }

    /**
     * No-payment plan activation. Available only when
     * {@code billing.payments-required=false} — i.e. while the product is
     * running without a Stripe account, or for demos. Activates the
     * requested plan and grants its monthly credit allowance immediately.
     *
     * <p>Special-cases {@link PlanCode#FREE}: rather than calling the
     * no-payment activation path (which would grant credits a second time
     * on top of the welcome grant the user already has), it just stamps
     * {@code planSelected=true} on the subscription. That handles the
     * "Continue with Free" CTA on the onboarding pricing page.
     */
    @PostMapping("/activate-plan")
    public ResponseEntity<BillingMeDTO> activatePlan(@RequestHeader("X-USER") String externalId,
                                                     @RequestBody CheckoutRequest request) {
        if (billingProperties.isPaymentsRequired() && request.plan() != PlanCode.FREE) {
            // Payments-on deploys still need a way for new users to choose to
            // stay on Free without being redirected to Stripe, so we keep the
            // FREE branch open even when the flag is on.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payments are enabled on this deployment — use /api/billing/checkout instead.");
        }
        if (request.plan() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "plan is required.");
        }
        Subscription sub = subscriptionService.getOrCreateForExternalId(externalId);
        try {
            if (request.plan() == PlanCode.FREE) {
                sub = subscriptionService.confirmPlan(sub);
            } else {
                sub = subscriptionService.applyPlanWithoutPayment(sub, request.plan());
            }
            return ResponseEntity.ok(BillingMeDTO.from(sub, billingProperties.isPaymentsRequired()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/portal")
    public ResponseEntity<RedirectUrlDTO> portal(@RequestHeader("X-USER") String externalId) {
        Subscription sub = subscriptionService.getOrCreateForExternalId(externalId);
        try {
            return ResponseEntity.ok(new RedirectUrlDTO(stripeBillingService.createPortalSession(sub)));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (StripeException e) {
            log.warn("Stripe portal session failed for {}: {}", externalId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe rejected the portal request.", e);
        }
    }

    /**
     * Public sales contact info for the Enterprise card. Avoids hard-coding
     * an email in the frontend repo when it might change per-deploy.
     */
    @GetMapping("/enterprise-contact")
    public ResponseEntity<Map<String, String>> enterpriseContact() {
        return ResponseEntity.ok(Map.of("email", billingProperties.getEnterpriseContactEmail()));
    }

    // Helper so the Plan rebind logic below stays terse.
    @SuppressWarnings("unused")
    private boolean isPaidSelfServe(PlanCode plan) {
        return plan != PlanCode.FREE && plan != PlanCode.ENTERPRISE;
    }

    @SuppressWarnings("unused")
    private static String safeUuid(UUID id) {
        return id == null ? null : id.toString();
    }
}