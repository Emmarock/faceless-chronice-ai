interface ProgressBarProps {
  progress: number;
  status?: string | null;
  stage?: string | null;
  /** Compact variant for list rows; default is the full bar with stage label. */
  compact?: boolean;
}

const STAGE_LABEL: Record<string, string> = {
  QUEUED: "Queued",
  SCRIPT_GENERATION: "Generating script",
  ASSET_GENERATION: "Generating images & voice",
  VIDEO_RENDERING: "Rendering video",
  COMPLETED: "Completed",
  FAILED: "Failed",
};

export function ProgressBar({ progress, status, stage, compact = false }: ProgressBarProps) {
  const pct = Math.max(0, Math.min(100, Math.round(progress ?? 0)));
  const isFailed = status === "FAILED" || stage === "FAILED";
  const isDone = pct >= 100 || status === "COMPLETED" || stage === "COMPLETED";
  const isIdle = pct <= 0 && (status === "QUEUED" || !status);

  const fillColor = isFailed
    ? "#ef4444"
    : isDone
    ? "#22c55e"
    : "#3b82f6";

  const label = isFailed
    ? STAGE_LABEL.FAILED
    : isDone
    ? STAGE_LABEL.COMPLETED
    : STAGE_LABEL[stage ?? ""] ?? STAGE_LABEL[status ?? ""] ?? (status ?? "");

  return (
    <div
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={pct}
      aria-label={label || `${pct}%`}
      style={{ width: "100%" }}
    >
      {!compact && (
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "baseline",
            marginBottom: 6,
            gap: 8,
            flexWrap: "wrap",
          }}
        >
          <span style={{ fontSize: 12, color: "#aaa" }}>{label}</span>
          <span
            style={{
              fontSize: 12,
              color: isFailed ? "#ff8b8b" : isDone ? "#4ade80" : "#cbd5e1",
              fontVariantNumeric: "tabular-nums",
              fontWeight: 600,
            }}
          >
            {pct}%
          </span>
        </div>
      )}
      <div
        style={{
          position: "relative",
          height: compact ? 6 : 8,
          background: "#1f2937",
          borderRadius: 999,
          overflow: "hidden",
        }}
      >
        <div
          style={{
            width: `${pct}%`,
            height: "100%",
            background: isIdle && !isFailed ? "transparent" : fillColor,
            borderRadius: 999,
            transition: "width 320ms ease-out, background 200ms",
            // Subtle stripe animation while in-progress to make it obvious the
            // backend is still working between progress jumps.
            backgroundImage:
              !isIdle && !isDone && !isFailed
                ? "linear-gradient(45deg, rgba(255,255,255,0.18) 25%, transparent 25%, transparent 50%, rgba(255,255,255,0.18) 50%, rgba(255,255,255,0.18) 75%, transparent 75%, transparent)"
                : undefined,
            backgroundSize: "16px 16px",
            animation:
              !isIdle && !isDone && !isFailed
                ? "fc-progress-stripe 1.2s linear infinite"
                : undefined,
          }}
        />
      </div>
      {compact && (
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            marginTop: 4,
            fontSize: 11,
            color: isFailed ? "#ff8b8b" : "#888",
          }}
        >
          <span>{label}</span>
          <span style={{ fontVariantNumeric: "tabular-nums" }}>{pct}%</span>
        </div>
      )}
    </div>
  );
}