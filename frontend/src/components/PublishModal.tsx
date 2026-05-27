import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import type {
  PlatformPostOptions,
  SocialConnectionDTO,
  SocialPlatform,
  VideoPublishRequest,
  VideoPublishResult,
} from "../types/api";

/**
 * Cross-posting publish modal — the centerpiece of the platform redesign.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Show every supported platform as a row with its current connection
 *       status, format compatibility hint, and an expand toggle.</li>
 *   <li>Let the user check the platforms they want, then expand each one to
 *       override the title / caption / hashtags. Live character-count
 *       feedback against each platform's published limit.</li>
 *   <li>Toggle between "Post now" and "Schedule for later" with a local
 *       date/time picker; the time is converted to an ISO instant before
 *       hitting the backend so server-side scheduling sees the user's
 *       actual intent.</li>
 *   <li>Drive submission via {@code onSubmit}, which receives a fully
 *       built {@link VideoPublishRequest}. The modal renders the resulting
 *       per-platform statuses (QUEUED / SCHEDULED / NOT_CONNECTED / …)
 *       so partial successes are visible without closing the dialog.</li>
 * </ul>
 */
export interface PublishModalProps {
  open: boolean;
  /** Subhead — typically the video / clip title. */
  title: string;
  /** Pre-fills the title field for every platform that supports it. */
  defaultTitle?: string;
  /** Pre-fills the caption field for every platform. */
  defaultDescription?: string;
  connections: SocialConnectionDTO[];
  /** Format the underlying video was generated in — drives compatibility hints. */
  videoFormat?: "REELS" | "VIDEO" | null;
  onClose: () => void;
  onSubmit: (request: VideoPublishRequest) => Promise<VideoPublishResult[]>;
}

interface PlatformMeta {
  platform: SocialPlatform;
  label: string;
  color: string;
  /** Hard limit the platform enforces server-side. We warn before submit. */
  captionLimit: number;
  titleLimit?: number;
  /** Optimal aspect ratio. Drives the "format match" hint. */
  preferredFormat: "REELS" | "VIDEO";
  supportsTitle: boolean;
}

const PLATFORM_META: PlatformMeta[] = [
  { platform: "YOUTUBE", label: "YouTube", color: "#ff0033", captionLimit: 5000, titleLimit: 100, preferredFormat: "VIDEO", supportsTitle: true },
  { platform: "FACEBOOK", label: "Facebook", color: "#1877f2", captionLimit: 63206, titleLimit: 255, preferredFormat: "VIDEO", supportsTitle: true },
  { platform: "INSTAGRAM", label: "Instagram", color: "#e1306c", captionLimit: 2200, preferredFormat: "REELS", supportsTitle: false },
  { platform: "TIKTOK", label: "TikTok", color: "#000000", captionLimit: 2200, preferredFormat: "REELS", supportsTitle: false },
  { platform: "TWITTER", label: "Twitter / X", color: "#1d9bf0", captionLimit: 280, preferredFormat: "REELS", supportsTitle: false },
  { platform: "LINKEDIN", label: "LinkedIn", color: "#0a66c2", captionLimit: 3000, preferredFormat: "VIDEO", supportsTitle: false },
];

type PerPlatformState = Record<SocialPlatform, PlatformPostOptions>;

export function PublishModal({
  open,
  title,
  defaultTitle,
  defaultDescription,
  connections,
  videoFormat,
  onClose,
  onSubmit,
}: PublishModalProps) {
  const connectedMap = useMemo(() => {
    const m = new Map<SocialPlatform, SocialConnectionDTO>();
    for (const c of connections) m.set(c.platform, c);
    return m;
  }, [connections]);

  const [selected, setSelected] = useState<Set<SocialPlatform>>(new Set());
  const [expanded, setExpanded] = useState<SocialPlatform | null>(null);
  const [perPlatform, setPerPlatform] = useState<PerPlatformState>(() => emptyState());
  const [mode, setMode] = useState<"now" | "schedule">("now");
  const [scheduledLocal, setScheduledLocal] = useState<string>(defaultScheduleSlot);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [results, setResults] = useState<VideoPublishResult[]>([]);

  if (!open) return null;

  const togglePlatform = (p: SocialPlatform) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(p)) next.delete(p);
      else next.add(p);
      return next;
    });
  };

  const setOptionField = (platform: SocialPlatform, field: keyof PlatformPostOptions, value: unknown) => {
    setPerPlatform((prev) => ({
      ...prev,
      [platform]: { ...prev[platform], [field]: value },
    }));
  };

  const handleSubmit = async () => {
    if (selected.size === 0) return;
    setError(null);
    if (mode === "schedule") {
      const ts = parseLocal(scheduledLocal);
      if (!ts) {
        setError("Pick a valid future date and time to schedule the post.");
        return;
      }
      if (ts.getTime() <= Date.now()) {
        setError("Scheduled time must be in the future. Pick at least a minute from now.");
        return;
      }
    }
    // Drop empty overrides so the backend persists null instead of "".
    const overrides: Partial<Record<SocialPlatform, PlatformPostOptions>> = {};
    for (const p of selected) {
      const o = perPlatform[p];
      const clean: PlatformPostOptions = {};
      if (o.title?.trim()) clean.title = o.title.trim();
      if (o.caption?.trim()) clean.caption = o.caption.trim();
      const tags = (o.hashtags ?? []).map((t) => t.trim()).filter(Boolean);
      if (tags.length > 0) clean.hashtags = tags;
      if (Object.keys(clean).length > 0) overrides[p] = clean;
    }
    const request: VideoPublishRequest = {
      platforms: Array.from(selected),
      scheduledAt: mode === "schedule" ? parseLocal(scheduledLocal)!.toISOString() : null,
      overrides: Object.keys(overrides).length > 0 ? overrides : undefined,
    };

    setSubmitting(true);
    try {
      const out = await onSubmit(request);
      setResults(out);
    } catch (err) {
      setError(extractError(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={backdrop} onClick={onClose}>
      <div style={card} onClick={(e) => e.stopPropagation()}>
        <header style={headerRow}>
          <div>
            <h3 style={{ margin: 0, fontSize: 18 }}>Cross-post</h3>
            <div style={{ fontSize: 12, color: "#9ca3af", marginTop: 2 }}>
              Publishing <strong style={{ color: "#e6e6e6" }}>{title}</strong>
            </div>
          </div>
          <button type="button" onClick={onClose} style={closeBtn} title="Close">✕</button>
        </header>

        <div style={platformList}>
          {PLATFORM_META.map((meta) => (
            <PlatformRow
              key={meta.platform}
              meta={meta}
              connection={connectedMap.get(meta.platform) ?? null}
              videoFormat={videoFormat ?? null}
              checked={selected.has(meta.platform)}
              expanded={expanded === meta.platform}
              onToggle={() => togglePlatform(meta.platform)}
              onExpand={() =>
                setExpanded((cur) => (cur === meta.platform ? null : meta.platform))
              }
              options={perPlatform[meta.platform]}
              defaultTitle={defaultTitle ?? ""}
              defaultDescription={defaultDescription ?? ""}
              onChangeField={(field, value) => setOptionField(meta.platform, field, value)}
            />
          ))}
        </div>

        <SchedulePicker
          mode={mode}
          onModeChange={setMode}
          scheduledLocal={scheduledLocal}
          onScheduledLocalChange={setScheduledLocal}
        />

        {error && <div style={errorBanner}>{error}</div>}

        {results.length > 0 && (
          <div style={{ marginTop: 12, display: "grid", gap: 6 }}>
            {results.map((r) => (
              <div key={r.platform} style={statusRow(r.status)}>
                <strong>{platformLabel(r.platform)}</strong>: {r.status}
                {r.message ? ` — ${r.message}` : ""}
              </div>
            ))}
          </div>
        )}

        <footer style={footer}>
          <button type="button" onClick={onClose} style={secondaryBtn}>Cancel</button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={submitting || selected.size === 0}
            style={{
              ...primaryBtn,
              opacity: submitting || selected.size === 0 ? 0.6 : 1,
              cursor: submitting || selected.size === 0 ? "not-allowed" : "pointer",
            }}
          >
            {submitting
              ? mode === "schedule" ? "Scheduling…" : "Publishing…"
              : mode === "schedule"
              ? `Schedule on ${selected.size} platform${selected.size === 1 ? "" : "s"}`
              : `Publish to ${selected.size} platform${selected.size === 1 ? "" : "s"}`}
          </button>
        </footer>
      </div>
    </div>
  );
}

interface PlatformRowProps {
  meta: PlatformMeta;
  connection: SocialConnectionDTO | null;
  videoFormat: "REELS" | "VIDEO" | null;
  checked: boolean;
  expanded: boolean;
  onToggle: () => void;
  onExpand: () => void;
  options: PlatformPostOptions;
  defaultTitle: string;
  defaultDescription: string;
  onChangeField: (field: keyof PlatformPostOptions, value: unknown) => void;
}

function PlatformRow({
  meta,
  connection,
  videoFormat,
  checked,
  expanded,
  onToggle,
  onExpand,
  options,
  defaultTitle,
  defaultDescription,
  onChangeField,
}: PlatformRowProps) {
  const connected = !!connection;
  const formatMismatch = videoFormat && videoFormat !== meta.preferredFormat;
  const captionLen = (options.caption ?? defaultDescription).length;
  const titleLen = (options.title ?? defaultTitle).length;
  const hashtagsInput = (options.hashtags ?? []).join(" ");

  return (
    <div style={{ ...rowWrap, borderColor: checked ? meta.color : "#262a31" }}>
      <button type="button" style={rowHeader} onClick={onExpand} aria-expanded={expanded}>
        <label style={{ display: "flex", alignItems: "center", gap: 10 }} onClick={(e) => e.stopPropagation()}>
          <input
            type="checkbox"
            checked={checked}
            onChange={onToggle}
            disabled={!connected}
            style={{ width: 16, height: 16 }}
          />
          <span style={{ width: 10, height: 10, borderRadius: "50%", background: meta.color }} />
          <span style={{ fontWeight: 600 }}>{meta.label}</span>
        </label>

        <div style={{ display: "flex", alignItems: "center", gap: 8, marginLeft: "auto" }}>
          {connected ? (
            <span style={chipConnected}>{connection?.accountHandle ?? "connected"}</span>
          ) : (
            <Link to="/connections" style={chipNotConnected} onClick={(e) => e.stopPropagation()}>
              Connect →
            </Link>
          )}
          {formatMismatch && (
            <span style={chipWarn} title={`Recommended format: ${meta.preferredFormat}`}>
              {videoFormat === "REELS" ? "9:16 in 16:9 feed" : "16:9 in Reels feed"}
            </span>
          )}
          <span style={chevron(expanded)}>▾</span>
        </div>
      </button>

      {expanded && connected && (
        <div style={rowBody}>
          {meta.supportsTitle && (
            <div style={fieldWrap}>
              <label style={fieldLabel}>
                <span>Title</span>
                <span style={charCount(titleLen, meta.titleLimit ?? 0)}>
                  {titleLen}{meta.titleLimit ? ` / ${meta.titleLimit}` : ""}
                </span>
              </label>
              <input
                type="text"
                value={options.title ?? ""}
                placeholder={defaultTitle || "Inherits video title"}
                onChange={(e) => onChangeField("title", e.target.value)}
                style={textInput}
                maxLength={meta.titleLimit}
              />
            </div>
          )}
          <div style={fieldWrap}>
            <label style={fieldLabel}>
              <span>Caption</span>
              <span style={charCount(captionLen, meta.captionLimit)}>
                {captionLen} / {meta.captionLimit}
              </span>
            </label>
            <textarea
              value={options.caption ?? ""}
              placeholder={defaultDescription || "Inherits video description"}
              onChange={(e) => onChangeField("caption", e.target.value)}
              rows={3}
              style={textArea}
              maxLength={meta.captionLimit}
            />
          </div>
          <div style={fieldWrap}>
            <label style={fieldLabel}>
              <span>Hashtags</span>
              <span style={{ fontSize: 11, color: "#666" }}>space-separated, # optional</span>
            </label>
            <input
              type="text"
              value={hashtagsInput}
              placeholder="#shorts #ai"
              onChange={(e) => {
                const tokens = e.target.value
                  .split(/[\s,]+/)
                  .map((t) => t.replace(/^#/, ""))
                  .filter(Boolean);
                onChangeField("hashtags", tokens);
              }}
              style={textInput}
            />
          </div>
        </div>
      )}

      {expanded && !connected && (
        <div style={{ ...rowBody, color: "#9ca3af", fontSize: 13 }}>
          Connect {meta.label} on the{" "}
          <Link to="/connections" style={{ color: "#60a5fa" }}>
            Connections page
          </Link>{" "}
          to enable this platform.
        </div>
      )}
    </div>
  );
}

interface SchedulePickerProps {
  mode: "now" | "schedule";
  onModeChange: (m: "now" | "schedule") => void;
  scheduledLocal: string;
  onScheduledLocalChange: (s: string) => void;
}

function SchedulePicker({
  mode,
  onModeChange,
  scheduledLocal,
  onScheduledLocalChange,
}: SchedulePickerProps) {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return (
    <div style={scheduleWrap}>
      <div style={{ fontSize: 13, color: "#cbd5e1", marginBottom: 8, fontWeight: 600 }}>When</div>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <label style={modeChip(mode === "now")}>
          <input
            type="radio"
            checked={mode === "now"}
            onChange={() => onModeChange("now")}
            style={{ display: "none" }}
          />
          Post now
        </label>
        <label style={modeChip(mode === "schedule")}>
          <input
            type="radio"
            checked={mode === "schedule"}
            onChange={() => onModeChange("schedule")}
            style={{ display: "none" }}
          />
          Schedule for later
        </label>
        {mode === "schedule" && (
          <input
            type="datetime-local"
            value={scheduledLocal}
            min={defaultScheduleSlot}
            onChange={(e) => onScheduledLocalChange(e.target.value)}
            style={{ ...textInput, maxWidth: 240 }}
          />
        )}
      </div>
      {mode === "schedule" && (
        <div style={{ fontSize: 11, color: "#888", marginTop: 6 }}>
          Local time ({tz}). The scheduler fires within 30s of the chosen instant.
        </div>
      )}
    </div>
  );
}

function platformLabel(p: SocialPlatform): string {
  switch (p) {
    case "YOUTUBE": return "YouTube";
    case "FACEBOOK": return "Facebook";
    case "INSTAGRAM": return "Instagram";
    case "TIKTOK": return "TikTok";
    case "TWITTER": return "Twitter / X";
    case "LINKEDIN": return "LinkedIn";
    default: return p;
  }
}

function emptyState(): PerPlatformState {
  return {
    YOUTUBE: {},
    FACEBOOK: {},
    INSTAGRAM: {},
    TIKTOK: {},
    TWITTER: {},
    LINKEDIN: {},
  };
}

/**
 * Default schedule slot: 1 hour from now, rounded down to the minute.
 * Used both as the {@code datetime-local} input default and its {@code min}
 * attribute so the calendar opens at a plausible time.
 */
const defaultScheduleSlot: string = (() => {
  const d = new Date(Date.now() + 60 * 60 * 1000);
  d.setSeconds(0, 0);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
})();

function parseLocal(local: string): Date | null {
  if (!local) return null;
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? null : d;
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

const backdrop: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "rgba(0,0,0,0.7)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  zIndex: 1000,
  padding: 24,
};

const card: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #2a2d33",
  borderRadius: 10,
  padding: 20,
  width: "min(640px, 100%)",
  maxHeight: "calc(100vh - 48px)",
  overflowY: "auto",
};

const headerRow: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  marginBottom: 14,
};

const closeBtn: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  width: 32,
  height: 32,
  cursor: "pointer",
};

const platformList: React.CSSProperties = {
  display: "grid",
  gap: 8,
};

const rowWrap: React.CSSProperties = {
  border: "1px solid #262a31",
  borderRadius: 8,
  background: "#10131a",
  overflow: "hidden",
  transition: "border-color 120ms ease",
};

const rowHeader: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  width: "100%",
  background: "transparent",
  color: "#e6e6e6",
  border: "none",
  padding: "10px 12px",
  cursor: "pointer",
  textAlign: "left",
};

const rowBody: React.CSSProperties = {
  padding: 12,
  paddingTop: 4,
  borderTop: "1px solid #262a31",
  display: "grid",
  gap: 10,
  background: "#0c0f15",
};

const fieldWrap: React.CSSProperties = { display: "grid", gap: 4 };

const fieldLabel: React.CSSProperties = {
  display: "flex",
  justifyContent: "space-between",
  alignItems: "baseline",
  fontSize: 12,
  color: "#9ca3af",
};

const textInput: React.CSSProperties = {
  background: "#0a0d12",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "8px 10px",
  color: "#e6e6e6",
  fontSize: 13,
  width: "100%",
  boxSizing: "border-box",
};

const textArea: React.CSSProperties = {
  ...textInput,
  resize: "vertical",
  fontFamily: "inherit",
};

const chipConnected: React.CSSProperties = {
  fontSize: 11,
  color: "#86efac",
  border: "1px solid #14532d",
  background: "#0f2118",
  padding: "2px 8px",
  borderRadius: 999,
};

const chipNotConnected: React.CSSProperties = {
  fontSize: 11,
  color: "#60a5fa",
  border: "1px solid #1e3a8a",
  background: "#0c1224",
  padding: "2px 8px",
  borderRadius: 999,
  textDecoration: "none",
};

const chipWarn: React.CSSProperties = {
  fontSize: 11,
  color: "#fbbf24",
  border: "1px solid #78350f",
  background: "#1f1809",
  padding: "2px 8px",
  borderRadius: 999,
};

const chevron = (open: boolean): React.CSSProperties => ({
  display: "inline-block",
  fontSize: 12,
  color: "#888",
  transform: open ? "rotate(180deg)" : "rotate(0deg)",
  transition: "transform 120ms ease",
});

const charCount = (current: number, limit: number): React.CSSProperties => ({
  fontSize: 11,
  color: limit && current > limit * 0.9 ? "#fbbf24" : "#666",
});

const scheduleWrap: React.CSSProperties = {
  marginTop: 14,
  padding: 12,
  border: "1px solid #262a31",
  borderRadius: 8,
  background: "#10131a",
};

const modeChip = (active: boolean): React.CSSProperties => ({
  padding: "6px 12px",
  borderRadius: 999,
  fontSize: 13,
  border: `1px solid ${active ? "#3b82f6" : "#2a2d33"}`,
  background: active ? "#1e3a8a" : "transparent",
  color: active ? "#fff" : "#cbd5e1",
  cursor: "pointer",
});

const errorBanner: React.CSSProperties = {
  marginTop: 12,
  padding: "8px 12px",
  border: "1px solid #7f1d1d",
  background: "#1a0e0e",
  color: "#fca5a5",
  borderRadius: 6,
  fontSize: 13,
};

const statusRow = (status: string): React.CSSProperties => ({
  fontSize: 12,
  background: "#0f1115",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "6px 10px",
  color:
    status === "QUEUED" ? "#4ade80"
    : status === "SCHEDULED" ? "#a78bfa"
    : status === "ALREADY_UPLOADED" ? "#60a5fa"
    : status === "NOT_CONNECTED" || status === "UNSUPPORTED" ? "#fbbf24"
    : "#aaa",
});

const footer: React.CSSProperties = {
  marginTop: 14,
  display: "flex",
  justifyContent: "flex-end",
  gap: 8,
};

const primaryBtn: React.CSSProperties = {
  background: "#3b82f6",
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "10px 16px",
  fontWeight: 600,
  fontSize: 14,
};

const secondaryBtn: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
  fontSize: 14,
};
