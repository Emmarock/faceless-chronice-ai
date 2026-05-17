import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { openPortal } from "../api/billing";
import { useBilling } from "../context/BillingContext";

/**
 * Account/billing page: current plan, credit balance, manage-subscription
 * button. Picks up {@code ?checkout=success} from the Stripe Checkout return
 * URL to fire an immediate refresh — the webhook may have already arrived
 * but a forced refetch makes the new state visible without a page reload.
 */
export function BillingPage() {
  const { billing, loading, refresh } = useBilling();
  const location = useLocation();
  const [portalBusy, setPortalBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Detect post-checkout redirects.
  useEffect(() => {
    const params = new URLSearchParams(location.search);
    if (params.get("checkout") === "success") {
      refresh();
    }
  }, [location.search, refresh]);

  const handleManage = async () => {
    setPortalBusy(true);
    setError(null);
    try {
      const url = await openPortal();
      window.location.href = url;
    } catch (err) {
      setError(extractError(err));
      setPortalBusy(false);
    }
  };

  return (
    <div>
      <h2 style={{ margin: 0, marginBottom: 8 }}>Billing</h2>
      <p style={{ color: "#aaa", marginTop: 0, marginBottom: 24 }}>
        Your current plan and credit usage.
      </p>

      {error && <div style={{ ...card, color: "#ff6b6b", marginBottom: 16 }}>{error}</div>}

      {loading && !billing ? (
        <div style={{ ...card, color: "#aaa" }}>Loading…</div>
      ) : billing ? (
        <>
        <div style={{ display: "grid", gap: 16, gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))" }}>
          <div style={card}>
            <div style={labelStyle}>Plan</div>
            <div style={valueStyle}>{billing.planDisplayName}</div>
            <div style={{ fontSize: 12, color: "#888", marginTop: 6 }}>
              {billing.status === "ACTIVE" && !billing.cancelAtPeriodEnd && "Active"}
              {billing.status === "ACTIVE" && billing.cancelAtPeriodEnd && billing.currentPeriodEnd &&
                `Cancels on ${formatDate(billing.currentPeriodEnd)}`}
              {billing.status === "PAST_DUE" && "Past due — please update your card"}
              {billing.status === "CANCELED" && "Canceled"}
              {billing.status === "INCOMPLETE" && "Setup in progress"}
            </div>
          </div>

          <div style={card}>
            <div style={labelStyle}>Credits remaining</div>
            <div style={valueStyle}>{billing.creditBalance.toLocaleString()}</div>
            <div style={{ fontSize: 12, color: "#888", marginTop: 6 }}>
              {billing.monthlyCreditGrant != null
                ? `Resets on the next billing cycle (${billing.monthlyCreditGrant.toLocaleString()} credits / month)`
                : "Custom credit allowance"}
            </div>
          </div>

          {billing.currentPeriodEnd && (
            <div style={card}>
              <div style={labelStyle}>Next renewal</div>
              <div style={valueStyle}>{formatDate(billing.currentPeriodEnd)}</div>
            </div>
          )}
        </div>

        {billing.planCode === "FREE" && (
          <div
            style={{
              marginTop: 16,
              background: "#15171b",
              border: "1px solid #1f2125",
              borderLeft: "3px solid #fbbf24",
              borderRadius: 6,
              padding: "12px 16px",
              fontSize: 13,
              color: "#cbd5e1",
            }}
          >
            <div style={{ fontWeight: 600, color: "#fbbf24", marginBottom: 4 }}>
              Free plan limits
            </div>
            <ul style={{ margin: 0, paddingLeft: 18, lineHeight: 1.7 }}>
              <li>Only the <strong>Reels</strong> format is available — long-form Video unlocks on Creator and above.</li>
              <li>A watermark is stamped on every rendered video.</li>
            </ul>
          </div>
        )}
        </>
      ) : (
        <div style={{ ...card, color: "#aaa" }}>No billing data.</div>
      )}

      <div style={{ marginTop: 24, display: "flex", gap: 12, flexWrap: "wrap" }}>
        <Link to="/pricing" style={btnPrimary}>
          Change plan
        </Link>
        {billing?.hasStripeCustomer && (
          <button
            type="button"
            onClick={handleManage}
            disabled={portalBusy}
            style={{ ...btnSecondary, opacity: portalBusy ? 0.6 : 1, cursor: portalBusy ? "wait" : "pointer" }}
          >
            {portalBusy ? "Opening…" : "Manage subscription"}
          </button>
        )}
      </div>
    </div>
  );
}

function formatDate(value: string | null | undefined): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

const card: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #1f2125",
  borderRadius: 8,
  padding: 16,
};

const labelStyle: React.CSSProperties = {
  fontSize: 12,
  color: "#888",
  textTransform: "uppercase",
  letterSpacing: "0.04em",
};

const valueStyle: React.CSSProperties = {
  fontSize: 24,
  fontWeight: 700,
  marginTop: 4,
};

const btnPrimary: React.CSSProperties = {
  background: "#3b82f6",
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
  fontWeight: 600,
  textDecoration: "none",
  display: "inline-block",
};

const btnSecondary: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
  fontWeight: 600,
};