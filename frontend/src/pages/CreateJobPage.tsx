import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { generateJob } from "../api/jobs";
import type { VideoFormat } from "../types/api";
import { useBilling } from "../context/BillingContext";
import { listPlans, type PlanCode, type PlanDTO } from "../api/billing";

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

export function CreateJobPage() {
  const navigate = useNavigate();
  const { billing, refresh: refreshBilling } = useBilling();
  const [question, setQuestion] = useState("");
  const [style, setStyle] = useState(STYLE_OPTIONS[0]);
  const [videoFormat, setVideoFormat] = useState<VideoFormat>("REELS");
  const [durationSeconds, setDurationSeconds] = useState<number>(REELS_DEFAULT_SECONDS);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Set to true when the last submit failed with a 402 — surface the upgrade banner. */
  const [outOfCredits, setOutOfCredits] = useState(false);

  const [plans, setPlans] = useState<PlanDTO[]>([]);

  const isReels = videoFormat === "REELS";
  const durationMin = isReels ? 5 : VIDEO_MIN_SECONDS;
  const durationMax = isReels ? REELS_MAX_SECONDS : VIDEO_MAX_SECONDS;
  // Long-form Video is a paid feature. Free users get the button rendered
  // in a locked state so the gate is visible up front, not surprise-rejected
  // on submit. The backend enforces the same rule regardless of UI state.
  const isFreePlan = billing?.planCode === "FREE";
  const videoLocked = isFreePlan;
  const formatCost = isReels ? REELS_CREDIT_COST : VIDEO_CREDIT_COST;
  // Pre-warn when balance is below the configured job cost so the user sees
  // the upgrade prompt before clicking Generate. We still rely on the 402
  // path below as the authoritative gate in case the backend cost changes.
  const balance = billing?.creditBalance ?? null;
  const balanceBelowCost = balance !== null && balance < formatCost;

  useEffect(() => {
    let cancelled = false;
    listPlans()
      .then((p) => {
        if (!cancelled) setPlans(p);
      })
      .catch(() => {
        // Plan catalog is purely advisory here — if it fails to load, fall
        // back to a generic "Upgrade plan" CTA without naming a tier.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Cheapest plan above the user's current tier whose monthly credit grant
  // can cover the selected format's cost. Falls back to ENTERPRISE when
  // every paid tier still wouldn't be enough.
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
    // Snap duration into the new format's window so the input doesn't show a
    // value the backend would reject.
    setDurationSeconds(next === "REELS" ? REELS_DEFAULT_SECONDS : VIDEO_DEFAULT_SECONDS);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!question.trim()) return;
    const clamped = Math.min(durationMax, Math.max(durationMin, durationSeconds));
    setSubmitting(true);
    setError(null);
    setOutOfCredits(false);
    try {
      const result = await generateJob({
        question: question.trim(),
        style,
        durationSeconds: clamped,
        videoFormat,
      });
      // Backend debited credits on success — refresh the chip without waiting
      // for the next polling tick.
      refreshBilling();
      navigate(`/jobs/${result.jobId}`, { state: { jobFile: result } });
    } catch (err) {
      if (isPaymentRequired(err)) {
        setOutOfCredits(true);
        setError(extractError(err) || "You're out of credits.");
        // Balance is unchanged on 402 (we throw before debiting), but refresh
        // anyway so the chip is authoritative.
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

  return (
    <div style={{ maxWidth: 640, width: "100%" }}>
      <h2>Create a new content</h2>
      <p style={{ color: "#aaa", marginBottom: 24 }}>
        Generate a video script and kick off the production pipeline.
      </p>

      <form onSubmit={handleSubmit} style={{ display: "grid", gap: 16 }}>
        <Field label="Question / Topic">
          <textarea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="e.g. What are the strangest unsolved mysteries of the deep ocean?"
            rows={4}
            style={input}
            required
          />
        </Field>

        <Field label="Style">
          <select value={style} onChange={(e) => setStyle(e.target.value)} style={input}>
            {STYLE_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Format">
          <div style={{ display: "flex", gap: 8 }} role="group" aria-label="Video format">
            <FormatButton
                active={videoFormat === "REELS"}
                onClick={() => handleFormatChange("REELS")}
                title="Single-scene short video, ≤30s — for Shorts / Reels / TikTok"
                label="Reels"
                sub="≤30s, 1 scene"
            />
            <FormatButton
              active={videoFormat === "VIDEO"}
              onClick={() => handleFormatChange("VIDEO")}
              title={videoLocked
                ? "Long-form video is a paid feature — click to upgrade"
                : "Multi-scene, multi-minute long-form video"}
              label="Video"
              sub={videoLocked ? "Upgrade to unlock" : "long-form, multi-scene"}
              locked={videoLocked}
            />
          </div>
        </Field>

        <Field label={`Duration (seconds) — max ${durationMax}`}>
          <input
            type="number"
            min={durationMin}
            max={durationMax}
            value={durationSeconds}
            onChange={(e) => setDurationSeconds(Number(e.target.value))}
            style={input}
          />
        </Field>

        {outOfCredits || balanceBelowCost ? (
          <UpgradeBanner
            title={
              outOfCredits
                ? "You're out of credits"
                : `Not enough credits to generate a ${isReels ? "Reel" : "Video"}`
            }
            message={
              outOfCredits
                ? error ?? "Upgrade your plan to keep generating content."
                : `A ${isReels ? "Reel" : "Video"} costs ${formatCost} credits${
                    balance !== null ? ` — you currently have ${balance.toLocaleString()}` : ""
                  }.${
                    nextPlan
                      ? ` Upgrade to ${nextPlan.displayName}${
                          nextPlan.monthlyCreditGrant != null
                            ? ` for ${nextPlan.monthlyCreditGrant.toLocaleString()} credits / month`
                            : ""
                        }.`
                      : ""
                  }`
            }
            cta={nextPlan ? `Upgrade to ${nextPlan.displayName}` : "Upgrade plan"}
          />
        ) : error ? (
          <div style={{ color: "#ff6b6b" }}>{error}</div>
        ) : null}

        <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          <button
            type="submit"
            disabled={submitting || balanceBelowCost}
            style={{ ...btnPrimary, opacity: balanceBelowCost ? 0.6 : 1, cursor: balanceBelowCost ? "not-allowed" : "pointer" }}
            title={balanceBelowCost ? "Upgrade your plan to generate this content" : undefined}
          >
            {submitting ? "Generating..." : "Generate Content"}
          </button>
          <button
            type="button"
            onClick={() => navigate("/")}
            disabled={submitting}
            style={btnSecondary}
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
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

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: "grid", gap: 6 }}>
      <span style={{ fontSize: 13, color: "#aaa" }}>{label}</span>
      {children}
    </label>
  );
}

function FormatButton({
  active,
  onClick,
  label,
  sub,
  title,
  locked = false,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
  sub: string;
  title: string;
  /** Renders the button in a "paid feature" state. Click still fires so the
   *  parent can route to /pricing — we don't disable, we redirect. */
  locked?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      style={{
        flex: 1,
        background: active ? "#1d4ed8" : locked ? "#181a1f" : "transparent",
        color: active ? "#fff" : locked ? "#7a8db3" : "#cbd5e1",
        border: "1px solid " + (active ? "#3b82f6" : locked ? "#2a2d33" : "#2a2d33"),
        borderRadius: 6,
        padding: "10px 12px",
        cursor: active ? "default" : "pointer",
        fontWeight: 600,
        display: "flex",
        flexDirection: "column",
        alignItems: "flex-start",
        gap: 2,
        position: "relative",
      }}
    >
      <span style={{ fontSize: 14, display: "inline-flex", alignItems: "center", gap: 6 }}>
        {locked && <span aria-hidden style={{ fontSize: 12 }}>🔒</span>}
        {label}
      </span>
      <span style={{ fontSize: 11, color: active ? "#d6e3ff" : locked ? "#fbbf24" : "#7a8db3", fontWeight: 400 }}>
        {sub}
      </span>
    </button>
  );
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

const input: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "10px 12px",
  color: "#e6e6e6",
  fontFamily: "inherit",
  fontSize: 16,
  width: "100%",
};

const btnPrimary: React.CSSProperties = {
  background: "#3b82f6",
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
  fontWeight: 600,
};

const btnSecondary: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
};