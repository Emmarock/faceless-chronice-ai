import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { generateJob } from "../api/jobs";
import type { VideoFormat } from "../types/api";
import { useBilling } from "../context/BillingContext";
import { Button, Card, Field, OptionCard, PageHeader, Stepper, inputStyle } from "../components/ui";
import {
  activatePlan,
  getEnterpriseContactEmail,
  listPlans,
  startCheckout,
  type PlanCode,
  type PlanDTO,
} from "../api/billing";

const STYLE_OPTIONS = [
  "documentary",
  "storytelling",
  "educational",
  "motivational",
  "horror",
  "comedy",
];

// Per-format duration constraints. REELS caps at 30s to match Shorts /
// TikTok / Reels norms; long-form keeps the legacy 15–600s window.
const REELS_MAX_SECONDS = 30;
const REELS_DEFAULT_SECONDS = 20;
const VIDEO_MIN_SECONDS = 15;
const VIDEO_MAX_SECONDS = 600;
const VIDEO_DEFAULT_SECONDS = 60;

// Mirrors backend BillingProperties.JobBudgets — kept in sync so the UI can
// pre-warn before submission instead of waiting on a 402.
const REELS_CREDIT_COST = 50;
const VIDEO_CREDIT_COST = 300;

// Plan hierarchy from lowest to highest. Used to find the cheapest plan that
// covers the selected format's job cost.
const PLAN_ORDER: PlanCode[] = ["FREE", "CREATOR", "PRO", "UNLIMITED", "ENTERPRISE"];

const STEPS = ["Topic", "Format", "Review"];

export function CreateJobPage() {
  const navigate = useNavigate();
  const { billing, refresh: refreshBilling } = useBilling();
  const [step, setStep] = useState(0);
  const [question, setQuestion] = useState("");
  const [style, setStyle] = useState(STYLE_OPTIONS[0]);
  const [videoFormat, setVideoFormat] = useState<VideoFormat>("REELS");
  const [durationSeconds, setDurationSeconds] = useState<number>(REELS_DEFAULT_SECONDS);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Set to true when the last submit failed with a 402 — surface the upgrade banner. */
  const [outOfCredits, setOutOfCredits] = useState(false);

  const [plans, setPlans] = useState<PlanDTO[]>([]);
  const [enterpriseEmail, setEnterpriseEmail] = useState<string>("sales@example.com");
  const [busyPlan, setBusyPlan] = useState<PlanCode | null>(null);
  const [upgradeError, setUpgradeError] = useState<string | null>(null);

  const isReels = videoFormat === "REELS";
  const durationMin = isReels ? 5 : VIDEO_MIN_SECONDS;
  const durationMax = isReels ? REELS_MAX_SECONDS : VIDEO_MAX_SECONDS;
  // Long-form Video is a paid feature. Free users get the option rendered in a
  // locked state so the gate is visible up front, not surprise-rejected on
  // submit. The backend enforces the same rule regardless of UI state.
  const isFreePlan = billing?.planCode === "FREE";
  const videoLocked = isFreePlan;
  const formatCost = isReels ? REELS_CREDIT_COST : VIDEO_CREDIT_COST;
  const balance = billing?.creditBalance ?? null;
  const balanceBelowCost = balance !== null && balance < formatCost;
  const clampedDuration = Math.min(durationMax, Math.max(durationMin, durationSeconds));

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      listPlans(),
      getEnterpriseContactEmail().catch(() => "sales@example.com"),
    ])
      .then(([p, email]) => {
        if (cancelled) return;
        setPlans(p);
        setEnterpriseEmail(email);
      })
      .catch(() => {
        // Plan catalog is purely advisory here — if it fails to load, fall
        // back to a generic "Upgrade plan" CTA without naming a tier.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const upgradeOptions = useMemo<PlanDTO[]>(() => {
    if (!billing || plans.length === 0) return [];
    const currentIdx = PLAN_ORDER.indexOf(billing.planCode);
    return PLAN_ORDER.slice(currentIdx + 1)
      .map((code) => plans.find((p) => p.code === code))
      .filter((p): p is PlanDTO => !!p);
  }, [billing, plans]);

  const paymentsRequired = billing?.paymentsRequired ?? true;

  const handleUpgrade = async (plan: PlanDTO) => {
    if (plan.code === "ENTERPRISE") {
      window.location.href = `mailto:${enterpriseEmail}?subject=Enterprise%20plan%20inquiry`;
      return;
    }
    if (paymentsRequired && !plan.selfServe) {
      setUpgradeError(
        `The ${plan.displayName} plan isn't checkout-enabled yet — please pick another tier.`,
      );
      return;
    }
    setBusyPlan(plan.code);
    setUpgradeError(null);
    try {
      if (paymentsRequired) {
        const url = await startCheckout(plan.code);
        window.location.href = url;
      } else {
        await activatePlan(plan.code);
        await refreshBilling();
        setOutOfCredits(false);
        setError(null);
        setBusyPlan(null);
      }
    } catch (err) {
      setUpgradeError(extractError(err));
      setBusyPlan(null);
    }
  };

  const nextPlan = useMemo<PlanDTO | null>(() => {
    if (!billing || plans.length === 0) return null;
    const currentIdx = PLAN_ORDER.indexOf(billing.planCode);
    const ordered = PLAN_ORDER.slice(currentIdx + 1)
      .map((code) => plans.find((p) => p.code === code))
      .filter((p): p is PlanDTO => !!p);
    const covers = ordered.find(
      (p) => p.code === "ENTERPRISE" || (p.monthlyCreditGrant ?? 0) >= formatCost,
    );
    return covers ?? ordered[ordered.length - 1] ?? null;
  }, [billing, plans, formatCost]);

  const handleFormatChange = (next: VideoFormat) => {
    if (next === videoFormat) return;
    if (next === "VIDEO" && videoLocked) {
      // Bounce to /pricing instead of silently no-op'ing — the upgrade prompt
      // is the point of the lock.
      navigate("/pricing");
      return;
    }
    setVideoFormat(next);
    setDurationSeconds(next === "REELS" ? REELS_DEFAULT_SECONDS : VIDEO_DEFAULT_SECONDS);
  };

  const handleSubmit = async () => {
    if (!question.trim()) return;
    setSubmitting(true);
    setError(null);
    setOutOfCredits(false);
    try {
      const result = await generateJob({
        question: question.trim(),
        style,
        durationSeconds: clampedDuration,
        videoFormat,
      });
      refreshBilling();
      navigate(`/jobs/${result.jobId}`, { state: { jobFile: result } });
    } catch (err) {
      if (isPaymentRequired(err)) {
        setOutOfCredits(true);
        setError(extractError(err) || "You're out of credits.");
        refreshBilling();
      } else {
        setError(extractError(err));
      }
    } finally {
      setSubmitting(false);
    }
  };

  function isPaymentRequired(err: unknown): boolean {
    if (typeof err !== "object" || err === null || !("response" in err)) return false;
    const status = (err as { response?: { status?: number } }).response?.status;
    return status === 402;
  }

  const canContinue = step !== 0 || question.trim().length > 0;

  return (
    <div style={{ maxWidth: 680, width: "100%" }}>
      <PageHeader
        title="Create a video"
        subtitle="Type a topic — we write the script, generate visuals and voice, and render it for you."
      />
      <Stepper steps={STEPS} current={step} />

      {step === 0 && (
        <Card>
          <h3 style={stepHeading}>What's your video about?</h3>
          <p style={stepSub}>Describe the topic or ask a question — the more specific, the sharper the script.</p>
          <div style={{ display: "grid", gap: 16, marginTop: 18 }}>
            <Field label="Topic or question">
              <textarea
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                placeholder="e.g. What are the strangest unsolved mysteries of the deep ocean?"
                rows={4}
                style={{ ...inputStyle, resize: "vertical" }}
                autoFocus
              />
            </Field>
            <Field label="Style">
              <select value={style} onChange={(e) => setStyle(e.target.value)} style={inputStyle}>
                {STYLE_OPTIONS.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </Field>
          </div>
        </Card>
      )}

      {step === 1 && (
        <Card>
          <h3 style={stepHeading}>Pick a format</h3>
          <p style={stepSub}>Short vertical clips for Reels/Shorts/TikTok, or long-form for YouTube.</p>
          <div style={{ display: "flex", gap: 12, marginTop: 18, flexWrap: "wrap" }}>
            <OptionCard
              active={isReels}
              onClick={() => handleFormatChange("REELS")}
              icon="📱"
              title="Reels"
              badge="≤30s · 1 scene"
              desc="Single-scene short video for Shorts, Reels and TikTok."
            />
            <OptionCard
              active={videoFormat === "VIDEO"}
              onClick={() => handleFormatChange("VIDEO")}
              icon="🎬"
              title="Video"
              locked={videoLocked}
              badge={videoLocked ? "Upgrade" : "long-form"}
              desc={videoLocked ? "Multi-scene long-form — upgrade to unlock." : "Multi-scene, multi-minute long-form video."}
            />
          </div>
          <div style={{ marginTop: 18 }}>
            <Field label={`Duration — ${durationMin}–${durationMax}s`}>
              <input
                type="number"
                min={durationMin}
                max={durationMax}
                value={durationSeconds}
                onChange={(e) => setDurationSeconds(Number(e.target.value))}
                style={{ ...inputStyle, maxWidth: 200 }}
              />
            </Field>
          </div>
        </Card>
      )}

      {step === 2 && (
        <Card>
          <h3 style={stepHeading}>Review &amp; generate</h3>
          <div style={{ display: "grid", gap: 1, marginTop: 14, borderRadius: 8, overflow: "hidden", border: "1px solid var(--border-strong)" }}>
            <SummaryRow label="Topic" value={question.trim() || "—"} />
            <SummaryRow label="Style" value={style} />
            <SummaryRow label="Format" value={isReels ? "Reels (≤30s)" : "Video (long-form)"} />
            <SummaryRow label="Duration" value={`${clampedDuration}s`} />
            <SummaryRow
              label="Cost"
              value={`${formatCost} credits${balance != null ? ` · ${balance.toLocaleString()} available` : ""}`}
            />
          </div>

          <div style={pipelineNote}>
            After you generate, we'll <strong style={{ color: "var(--text)" }}>write the script → generate visuals → voice it → render the video</strong>. You can watch each step on the next screen.
          </div>

          {outOfCredits ? (
            <div style={{ marginTop: 14 }}>
              <InlinePlanPicker
                title="You're out of credits"
                message={error ?? "Upgrade your plan to keep generating content."}
                plans={upgradeOptions}
                paymentsRequired={paymentsRequired}
                busyPlan={busyPlan}
                upgradeError={upgradeError}
                onUpgrade={handleUpgrade}
              />
            </div>
          ) : balanceBelowCost ? (
            <div style={{ marginTop: 14 }}>
              <UpgradeBanner
                title={`Not enough credits to generate a ${isReels ? "Reel" : "Video"}`}
                message={`A ${isReels ? "Reel" : "Video"} costs ${formatCost} credits${
                  balance !== null ? ` — you currently have ${balance.toLocaleString()}` : ""
                }.${
                  nextPlan
                    ? ` Upgrade to ${nextPlan.displayName}${
                        nextPlan.monthlyCreditGrant != null
                          ? ` for ${nextPlan.monthlyCreditGrant.toLocaleString()} credits / month`
                          : ""
                      }.`
                    : ""
                }`}
                cta={nextPlan ? `Upgrade to ${nextPlan.displayName}` : "Upgrade plan"}
              />
            </div>
          ) : error ? (
            <div style={{ marginTop: 14, color: "var(--danger)" }}>{error}</div>
          ) : null}
        </Card>
      )}

      {/* Wizard navigation */}
      <div style={{ display: "flex", justifyContent: "space-between", gap: 12, marginTop: 20, flexWrap: "wrap" }}>
        <Button
          variant="secondary"
          type="button"
          onClick={() => (step === 0 ? navigate("/") : setStep(step - 1))}
          disabled={submitting}
        >
          {step === 0 ? "Cancel" : "← Back"}
        </Button>
        {step < STEPS.length - 1 ? (
          <Button type="button" onClick={() => setStep(step + 1)} disabled={!canContinue}>
            Continue →
          </Button>
        ) : (
          <Button
            type="button"
            onClick={handleSubmit}
            disabled={submitting || balanceBelowCost}
            style={{ opacity: balanceBelowCost ? 0.6 : 1, cursor: balanceBelowCost ? "not-allowed" : "pointer" }}
            title={balanceBelowCost ? "Upgrade your plan to generate this content" : undefined}
          >
            {submitting ? "Generating…" : "✨ Generate video"}
          </Button>
        )}
      </div>
    </div>
  );
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: "flex", gap: 16, padding: "11px 14px", background: "var(--surface-2)", alignItems: "baseline" }}>
      <span style={{ width: 90, flexShrink: 0, color: "var(--text-dim)", fontSize: 13 }}>{label}</span>
      <span style={{ color: "var(--text)", fontSize: 14, overflow: "hidden", textOverflow: "ellipsis" }}>{value}</span>
    </div>
  );
}

function InlinePlanPicker({
  title,
  message,
  plans,
  paymentsRequired,
  busyPlan,
  upgradeError,
  onUpgrade,
}: {
  title: string;
  message: string;
  plans: PlanDTO[];
  paymentsRequired: boolean;
  busyPlan: PlanCode | null;
  upgradeError: string | null;
  onUpgrade: (plan: PlanDTO) => void;
}) {
  return (
    <div
      style={{
        background: "#1a1612",
        border: "1px solid #5a3a1f",
        borderRadius: 8,
        padding: 16,
      }}
    >
      <div style={{ fontWeight: 600, color: "#fbbf24", marginBottom: 4 }}>{title}</div>
      <div style={{ fontSize: 13, color: "#cbd5e1", marginBottom: 12 }}>{message}</div>

      {plans.length === 0 ? (
        <Link
          to="/pricing"
          style={{
            display: "inline-block",
            background: "#3b82f6",
            color: "#fff",
            border: "none",
            borderRadius: 6,
            padding: "8px 14px",
            fontWeight: 600,
            fontSize: 13,
            textDecoration: "none",
          }}
        >
          See plans
        </Link>
      ) : (
        <>
          <div style={{ fontSize: 12, color: "#aab2c0", marginBottom: 8 }}>
            Pick a plan to upgrade to:
          </div>
          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
              gap: 8,
            }}
          >
            {plans.map((p) => {
              const isComingSoon =
                paymentsRequired && !p.selfServe && p.code !== "ENTERPRISE";
              const busy = busyPlan === p.code;
              const disabled = busy || isComingSoon;
              return (
                <button
                  key={p.code}
                  type="button"
                  onClick={() => onUpgrade(p)}
                  disabled={disabled}
                  style={{
                    background: p.highlighted && !disabled ? "#1d4ed8" : "#15171b",
                    color: p.highlighted && !disabled ? "#fff" : "#e6e6e6",
                    border: "1px solid " + (p.highlighted && !disabled ? "#3b82f6" : "#2a2d33"),
                    borderRadius: 6,
                    padding: 10,
                    cursor: disabled ? "not-allowed" : "pointer",
                    fontWeight: 600,
                    display: "flex",
                    flexDirection: "column",
                    alignItems: "flex-start",
                    gap: 2,
                    opacity: disabled ? 0.6 : 1,
                    textAlign: "left",
                  }}
                  title={
                    isComingSoon
                      ? `${p.displayName} isn't checkout-enabled yet`
                      : `Upgrade to ${p.displayName}`
                  }
                >
                  <span style={{ fontSize: 13 }}>{p.displayName}</span>
                  <span style={{ fontSize: 11, fontWeight: 400, color: "#9aa3b2" }}>
                    {formatPlanPrice(p)}
                  </span>
                  <span style={{ fontSize: 11, fontWeight: 400, color: "#9aa3b2" }}>
                    {p.monthlyCreditGrant != null
                      ? `${p.monthlyCreditGrant.toLocaleString()} credits / mo`
                      : "Custom credits"}
                  </span>
                  <span
                    style={{
                      marginTop: 4,
                      fontSize: 11,
                      color: p.highlighted && !disabled ? "#d6e3ff" : "#9bb3ff",
                    }}
                  >
                    {busy
                      ? "Loading…"
                      : isComingSoon
                      ? "Coming soon"
                      : p.code === "ENTERPRISE"
                      ? "Contact sales →"
                      : paymentsRequired
                      ? "Checkout →"
                      : "Activate →"}
                  </span>
                </button>
              );
            })}
          </div>
          {upgradeError && (
            <div style={{ marginTop: 10, fontSize: 12, color: "#ff8b8b" }}>{upgradeError}</div>
          )}
          <div style={{ marginTop: 10, fontSize: 11, color: "#7a8db3" }}>
            Or <Link to="/pricing" style={{ color: "#9bb3ff" }}>see the full pricing page</Link>.
          </div>
        </>
      )}
    </div>
  );
}

function formatPlanPrice(plan: PlanDTO): string {
  if (plan.code === "ENTERPRISE") return "Custom";
  if (plan.monthlyPriceCents == null) return "—";
  if (plan.monthlyPriceCents === 0) return "Free";
  return `$${(plan.monthlyPriceCents / 100).toFixed(0)}/mo`;
}

function UpgradeBanner({
  title,
  message,
  cta,
}: {
  title: string;
  message: string;
  cta: string;
}) {
  return (
    <div
      style={{
        background: "#1a1612",
        border: "1px solid #5a3a1f",
        borderRadius: 8,
        padding: 16,
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        gap: 12,
        flexWrap: "wrap",
      }}
    >
      <div>
        <div style={{ fontWeight: 600, color: "#fbbf24", marginBottom: 4 }}>{title}</div>
        <div style={{ fontSize: 13, color: "#cbd5e1" }}>{message}</div>
      </div>
      <Link
        to="/pricing"
        style={{
          background: "#3b82f6",
          color: "#fff",
          border: "none",
          borderRadius: 6,
          padding: "8px 14px",
          fontWeight: 600,
          fontSize: 13,
          textDecoration: "none",
          whiteSpace: "nowrap",
        }}
      >
        {cta}
      </Link>
    </div>
  );
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

const stepHeading: React.CSSProperties = { margin: 0, fontSize: 18 };
const stepSub: React.CSSProperties = { color: "var(--text-muted)", margin: "6px 0 0", fontSize: 14 };
const pipelineNote: React.CSSProperties = {
  marginTop: 16,
  padding: "12px 14px",
  background: "var(--surface-2)",
  border: "1px solid var(--border-strong)",
  borderRadius: 8,
  fontSize: 13,
  color: "var(--text-muted)",
  lineHeight: 1.6,
};
