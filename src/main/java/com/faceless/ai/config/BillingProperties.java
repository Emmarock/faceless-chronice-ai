package com.faceless.ai.config;

import com.faceless.ai.entity.LedgerKind;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Type-safe binding for {@code chronicleai.billing.*}.
 *
 * <p>Keeping these in one place (instead of {@code @Value} scattered across
 * services) means a missing Stripe key blocks startup with a single clear
 * error, and tests can substitute a mock with a couple of lines.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "chronicleai.billing")
public class BillingProperties {

    private Stripe stripe = new Stripe();
    private Plans plans = new Plans();
    private CreditCosts creditCosts = new CreditCosts();
    private JobBudgets jobBudgets = new JobBudgets();
    private int welcomeGrant = 0;
    private String enterpriseContactEmail = "";
    /**
     * Master feature flag for the payment layer.
     *
     * <p>When {@code false} (the default — safe to ship without a Stripe
     * account), paid plans are <em>activated without payment</em>: users hit
     * {@code POST /api/billing/activate-plan} and get the new plan plus its
     * monthly credit grant immediately. The Stripe Checkout endpoint
     * refuses to serve traffic in this mode.
     *
     * <p>When {@code true}, paid plans go through Stripe Checkout via
     * {@code POST /api/billing/checkout}. The {@code /activate-plan}
     * endpoint refuses to serve traffic in this mode.
     *
     * <p>Flip via {@code BILLING_PAYMENTS_REQUIRED=true} once a Stripe
     * account + price IDs are wired up.
     */
    private boolean paymentsRequired = false;
    /**
     * Text overlaid on the bottom-right of every scene when the Job's
     * {@code watermarked} flag is true. Single-line; FFmpeg drawtext does
     * not wrap. Special characters that would need escaping in a drawtext
     * filter (colon, percent, backslash) are intentionally avoided in the
     * default.
     */
    private String watermarkText = "Made with Faceless Chronicle AI";

    /**
     * Flat upfront charge for a given video format. Sized to roughly match
     * the granular per-action costs (script + ~3 images + 1 voice + 1 clip
     * + assembly for Reels; the multi-scene equivalent for Video).
     */
    public int budgetFor(com.faceless.ai.model.VideoFormat format) {
        return format == com.faceless.ai.model.VideoFormat.REELS
                ? jobBudgets.reels
                : jobBudgets.video;
    }

    /**
     * @return the credit cost for a given metered action, or {@code 0} when
     *         the kind isn't a debit (grants / refunds / adjustments).
     */
    public int costFor(LedgerKind kind) {
        return switch (kind) {
            case DEBIT_SCRIPT         -> creditCosts.script;
            case DEBIT_IMAGE          -> creditCosts.image;
            case DEBIT_VOICE          -> creditCosts.voice;
            case DEBIT_VIDEO_CLIP     -> creditCosts.videoClip;
            case DEBIT_VIDEO_ASSEMBLY -> creditCosts.videoAssembly;
            default -> 0;
        };
    }

    @Getter
    @Setter
    public static class Stripe {
        private String secretKey = "";
        private String webhookSecret = "";
        private String successUrl = "";
        private String cancelUrl = "";
        private String portalReturnUrl = "";

        public boolean isConfigured() {
            return secretKey != null && !secretKey.isBlank();
        }
    }

    @Getter
    @Setter
    public static class Plans {
        private String creatorPriceId = "";
        private String proPriceId = "";
        private String unlimitedPriceId = "";

        /** All non-blank price IDs keyed by their plan code. */
        public Map<String, String> nonBlankPriceIds() {
            Map<String, String> out = new java.util.HashMap<>();
            if (!creatorPriceId.isBlank())   out.put("CREATOR",   creatorPriceId);
            if (!proPriceId.isBlank())       out.put("PRO",       proPriceId);
            if (!unlimitedPriceId.isBlank()) out.put("UNLIMITED", unlimitedPriceId);
            return out;
        }
    }

    @Getter
    @Setter
    public static class CreditCosts {
        private int script = 5;
        private int image = 10;
        private int voice = 5;
        private int videoClip = 3;
        private int videoAssembly = 5;
    }

    @Getter
    @Setter
    public static class JobBudgets {
        private int reels = 50;
        private int video = 300;
    }
}