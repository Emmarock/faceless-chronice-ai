import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  activatePlan,
  getEnterpriseContactEmail,
  listPlans,
  startCheckout,
  type PlanCode,
  type PlanDTO,
} from "../api/billing";
import { useBilling } from "../context/BillingContext";

/**
 * Public(-ish) pricing page mirroring Runway's tier-card layout. Lists all
 * plans from the backend catalog, lets self-serve plans hit Stripe Checkout,
 * and routes Enterprise to a mailto.
 *
 * <p>The {@code selfServe} flag on each plan dictates the button: the
 * backend knows whether a Stripe price id is wired up, so the UI doesn't
 * have to second-guess what's checkout-able today.
 */
export function PricingPage() {
  const navigate = useNavigate();
  const { billing, refresh: refreshBilling } = useBilling();
  const [plans, setPlans] = useState<PlanDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyPlan, setBusyPlan] = useState<PlanCode | null>(null);
  const [enterpriseEmail, setEnterpriseEmail] = useState<string>("sales@example.com");
  // Default to true (Stripe-required mode) when /me hasn't loaded yet — fail
  // safe by treating a missing flag as "real payments expected". Once /me
  // arrives, this picks up the actual server-side setting.
  const paymentsRequired = billing?.paymentsRequired ?? true;
  // Brand-new sign-ups land here as their first stop. The "Continue with
  // Free" CTA appears only in this state — once they pick, the Free card
  // reverts to its normal "Current plan / Go to billing" behaviour.
  const isOnboarding = billing !== null && !billing.planSelected;

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([listPlans(), getEnterpriseContactEmail().catch(() => "sales@example.com")])
      .then(([p, email]) => {
        if (cancelled) return;
        setPlans(p);
        setEnterpriseEmail(email);
      })
      .catch((err) => {
        if (!cancelled) setError(extractError(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleUpgrade = async (plan: PlanDTO) => {
    if (plan.code === "ENTERPRISE") {
      window.location.href = `mailto:${enterpriseEmail}?subject=Enterprise%20plan%20inquiry`;
      return;
    }
    // Defence-in-depth: even if the button somehow renders enabled, never
    // re-run checkout / activation against the plan the user is already on.
    // Onboarding is the one exception — currentPlan==FREE during onboarding
    // still needs the "Continue with Free" confirmation click to flip the
    // plan_selected flag.
    if (billing?.planCode === plan.code && !isOnboarding) {
      navigate("/billing");
      return;
    }
    if (plan.code === "FREE") {
      if (isOnboarding) {
        // First-time user confirming Free. Flip plan_selected and let them
        // into the rest of the app.
        setBusyPlan("FREE");
        setError(null);
        try {
          await activatePlan("FREE");
          await refreshBilling();
          navigate("/");
        } catch (err) {
          setError(extractError(err));
          setBusyPlan(null);
        }
        return;
      }
      navigate("/billing");
      return;
    }
    // Defensive: the "Coming soon" button is rendered disabled (see
    // PlanCard.nonActionable), but if the user somehow clicks anyway,
    // refuse rather than create a Stripe session that will 400 on us.
    if (paymentsRequired && !plan.selfServe) {
      setError(
        `The ${plan.displayName} plan isn't checkout-enabled yet — set its Stripe price id in your backend config.`,
      );
      return;
    }
    setBusyPlan(plan.code);
    setError(null);
    try {
      if (paymentsRequired) {
        const url = await startCheckout(plan.code);
        // Full navigation (not router) so Stripe's hosted page loads with its
        // own scripts/styles, not our SPA shell.
        window.location.href = url;
      } else {
        await activatePlan(plan.code);
        await refreshBilling();
        navigate("/billing?checkout=success");
      }
    } catch (err) {
      setError(extractError(err));
      setBusyPlan(null);
    }
  };

  return (
    <div>
      <div style={{ textAlign: "center", marginBottom: 32 }}>
        <h2 style={{ margin: 0 }}>
          {isOnboarding ? "Welcome — pick a plan to get started" : "Pick the plan that fits your output"}
        </h2>
        <p style={{ color: "#aaa", marginTop: 8, maxWidth: 640, marginLeft: "auto", marginRight: "auto" }}>
          {isOnboarding
            ? "You can change plans anytime from the billing page. Free is fine to start — Reels-only, watermarked, with a small monthly credit pool."
            : "Every plan includes a monthly credit pool. Credits cover script generation, image generation, voice synthesis, stock-video fetches, and final assembly — the more you upgrade, the more you ship."}
        </p>
        {!paymentsRequired && (
          <div
            style={{
              marginTop: 16,
              display: "inline-block",
              background: "#1a1612",
              border: "1px solid #5a3a1f",
              borderRadius: 6,
              padding: "8px 14px",
              fontSize: 13,
              color: "#fbbf24",
            }}
          >
            Preview mode — paid plans activate immediately at no charge. Pricing shows what you'll pay once payments go live.
          </div>
        )}
      </div>

      {error && (
        <div style={{ ...card, color: "#ff6b6b", marginBottom: 16, textAlign: "center" }}>{error}</div>
      )}

      {loading ? (
        <div style={{ ...card, color: "#aaa", textAlign: "center" }}>Loading plans…</div>
      ) : (
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))",
            gap: 16,
            alignItems: "stretch",
          }}
        >
          {plans.map((plan) => (
            <PlanCard
              key={plan.code}
              plan={plan}
              busy={busyPlan === plan.code}
              paymentsRequired={paymentsRequired}
              currentPlan={billing?.planCode ?? null}
              isOnboarding={isOnboarding}
              onSelect={() => handleUpgrade(plan)}
            />
          ))}
        </div>
      )}

      <p style={{ marginTop: 32, color: "#666", fontSize: 12, textAlign: "center" }}>
        Prices in USD. Cancel anytime from the billing page — your plan stays active until the end of the
        current period.
      </p>
    </div>
  );
}

interface PlanCardProps {
  plan: PlanDTO;
  busy: boolean;
  paymentsRequired: boolean;
  currentPlan: PlanCode | null;
  isOnboarding: boolean;
  onSelect: () => void;
}

function PlanCard({ plan, busy, paymentsRequired, currentPlan, isOnboarding, onSelect }: PlanCardProps) {
  const priceLabel = formatPrice(plan, paymentsRequired);
  const cta = ctaLabel(plan, paymentsRequired, currentPlan, isOnboarding);
  // A CTA is non-actionable when:
  //  - it's the plan the user is already on (paid or post-onboarding Free)
  //  - the plan is a paid one with no Stripe price id wired up
  // The Enterprise / Free / paid-upgrade flows all stay actionable.
  const isCurrent = currentPlan === plan.code && !(plan.code === "FREE" && isOnboarding);
  const isComingSoon = paymentsRequired
    && !plan.selfServe
    && plan.code !== "FREE"
    && plan.code !== "ENTERPRISE";
  const nonActionable = isCurrent || isComingSoon;
  const disabled = busy || nonActionable;
  return (
    <div
      style={{
        ...card,
        position: "relative",
        display: "flex",
        flexDirection: "column",
        gap: 12,
        borderColor: plan.highlighted ? "#3b82f6" : "#1f2125",
        boxShadow: plan.highlighted ? "0 0 0 2px rgba(59,130,246,0.25)" : "none",
      }}
    >
      {plan.highlighted && (
        <div
          style={{
            position: "absolute",
            top: -10,
            left: 16,
            background: "#3b82f6",
            color: "#fff",
            fontSize: 11,
            fontWeight: 700,
            padding: "2px 8px",
            borderRadius: 999,
          }}
        >
          MOST POPULAR
        </div>
      )}
      <div style={{ fontSize: 18, fontWeight: 700 }}>{plan.displayName}</div>
      {plan.tagline && (
        <div style={{ fontSize: 12, color: "#aaa", minHeight: 32 }}>{plan.tagline}</div>
      )}
      <div style={{ fontSize: 24, fontWeight: 700, color: "#e6e6e6" }}>{priceLabel}</div>
      <div style={{ fontSize: 13, color: "#cbd5e1" }}>
        {plan.monthlyCreditGrant != null
          ? `${plan.monthlyCreditGrant.toLocaleString()} credits / month`
          : "Custom credit allowance"}
      </div>
      <button
        type="button"
        onClick={onSelect}
        disabled={disabled}
        style={{
          ...(plan.highlighted && !nonActionable ? btnPrimary : btnSecondary),
          marginTop: "auto",
          opacity: busy ? 0.6 : nonActionable ? 0.55 : 1,
          cursor: busy ? "wait" : nonActionable ? "not-allowed" : "pointer",
        }}
      >
        {busy ? "Loading…" : cta}
      </button>
    </div>
  );
}

function formatPrice(plan: PlanDTO, paymentsRequired: boolean): string {
  if (plan.code === "ENTERPRISE") return "Custom";
  if (plan.monthlyPriceCents == null) return "—";
  if (plan.monthlyPriceCents === 0) return "Free";
  // Show the headline price even in demo mode so the pricing copy stays
  // honest about what users will pay once payments are enabled.
  const price = `$${(plan.monthlyPriceCents / 100).toFixed(0)}/mo`;
  return paymentsRequired ? price : `${price} (free in preview)`;
}

function ctaLabel(
  plan: PlanDTO,
  paymentsRequired: boolean,
  currentPlan: PlanCode | null,
  isOnboarding: boolean,
): string {
  if (plan.code === "ENTERPRISE") return "Contact sales";
  if (plan.code === "FREE") {
    if (isOnboarding) return "Continue with Free";
    if (currentPlan === "FREE") return "Current plan";
    return "Go to billing";
  }
  if (currentPlan === plan.code) return "Current plan";
  if (paymentsRequired && !plan.selfServe) return "Coming soon";
  // No payments mode → activate-in-place; payments mode → Stripe Checkout.
  return paymentsRequired ? `Upgrade to ${plan.displayName}` : `Switch to ${plan.displayName}`;
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Could not load plans.";
}

const card: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #1f2125",
  borderRadius: 8,
  padding: 16,
};

const btnPrimary: React.CSSProperties = {
  background: "#3b82f6",
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
  fontWeight: 600,
  fontSize: 14,
};

const btnSecondary: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
  fontWeight: 600,
  fontSize: 14,
};