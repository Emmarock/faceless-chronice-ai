import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { cancelScheduledUpload, listScheduledUploads } from "../api/scheduled";
import { card as uiCard, buttonStyle, PageHeader } from "../components/ui";
import type { ScheduledUploadDTO, SocialPlatform } from "../types/api";

/**
 * Lists the calling user's pending scheduled cross-posts so they can review
 * what will fire and when, and cancel anything they no longer want to ship.
 *
 * <p>The server filters to {@code status=SCHEDULED}; a completed or failed
 * upload disappears from this list (it shows up via the regular
 * VideoCard / Asset publish status surfaces instead).
 */
export function ScheduledPostsPage() {
  const [rows, setRows] = useState<ScheduledUploadDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    refresh();
  }, []);

  const refresh = async () => {
    setLoading(true);
    try {
      setRows(await listScheduledUploads());
      setError(null);
    } catch (err) {
      setError(extractError(err));
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = async (id: string) => {
    setBusy(id);
    try {
      await cancelScheduledUpload(id);
      await refresh();
    } catch (err) {
      setError(extractError(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div>
      <PageHeader
        title="Scheduled"
        subtitle="Cross-posts queued for the future. Cancel anything you no longer want to ship — the scheduler skips cancelled rows on its next tick."
      />

      {error && <div style={errorBanner}>{error}</div>}

      {loading ? (
        <div style={{ color: "#aaa" }}>Loading scheduled posts…</div>
      ) : rows.length === 0 ? (
        <div style={emptyCard}>
          <div style={{ fontSize: 15, marginBottom: 6 }}>No scheduled posts.</div>
          <div style={{ fontSize: 13, color: "#9ca3af" }}>
            Open the publish dialog on a{" "}
            <Link to="/videos" style={{ color: "#60a5fa" }}>video</Link> or{" "}
            <Link to="/assets" style={{ color: "#60a5fa" }}>clip</Link> and pick
            "Schedule for later" to queue one.
          </div>
        </div>
      ) : (
        <div style={list}>
          {rows.map((row) => (
            <ScheduledRow
              key={row.id}
              row={row}
              busy={busy === row.id}
              onCancel={() => handleCancel(row.id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

interface ScheduledRowProps {
  row: ScheduledUploadDTO;
  busy: boolean;
  onCancel: () => void;
}

function ScheduledRow({ row, busy, onCancel }: ScheduledRowProps) {
  const fires = row.scheduledAt ? new Date(row.scheduledAt) : null;
  const relative = fires ? formatRelative(fires) : "(no scheduled time)";
  const absolute = fires ? formatAbsolute(fires) : "";
  const sourceLink =
    row.sourceType === "ASSET"
      ? { to: `/assets`, label: "Open assets" }
      : { to: `/videos`, label: "Open videos" };

  return (
    <div style={card}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 8 }}>
        <span
          style={{
            width: 10,
            height: 10,
            borderRadius: "50%",
            background: platformColor(row.platform),
          }}
        />
        <strong>{platformLabel(row.platform)}</strong>
        <span style={{ color: "#888", fontSize: 12 }}>·</span>
        <span style={{ color: "#9ca3af", fontSize: 12 }}>
          {row.sourceType === "ASSET" ? "Rendered clip" : "Video"}
        </span>
        <span style={{ marginLeft: "auto", fontSize: 12, color: "#a78bfa" }}>{relative}</span>
      </div>
      <div style={{ fontSize: 12, color: "#9ca3af", marginBottom: 8 }}>{absolute}</div>

      {row.title && (
        <div style={metaLine}>
          <span style={metaLabel}>Title</span>
          <span>{row.title}</span>
        </div>
      )}
      {row.caption && (
        <div style={metaLine}>
          <span style={metaLabel}>Caption</span>
          <span style={{ whiteSpace: "pre-wrap" }}>{row.caption}</span>
        </div>
      )}
      {row.hashtags && (
        <div style={metaLine}>
          <span style={metaLabel}>Tags</span>
          <span>
            {row.hashtags
              .split(",")
              .map((t) => t.trim())
              .filter(Boolean)
              .map((t) => "#" + t)
              .join(" ")}
          </span>
        </div>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
        <Link to={sourceLink.to} style={btnSecondary}>
          {sourceLink.label}
        </Link>
        <button
          type="button"
          onClick={onCancel}
          disabled={busy}
          style={{ ...btnDanger, opacity: busy ? 0.6 : 1 }}
        >
          {busy ? "Cancelling…" : "Cancel"}
        </button>
      </div>
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

function platformColor(p: SocialPlatform): string {
  switch (p) {
    case "YOUTUBE": return "#ff0033";
    case "FACEBOOK": return "#1877f2";
    case "INSTAGRAM": return "#e1306c";
    case "TIKTOK": return "#000000";
    case "TWITTER": return "#1d9bf0";
    case "LINKEDIN": return "#0a66c2";
    default: return "#888";
  }
}

function formatRelative(when: Date): string {
  const diffMs = when.getTime() - Date.now();
  if (diffMs <= 0) return "any moment now";
  const minutes = Math.round(diffMs / 60_000);
  if (minutes < 60) return `in ${minutes} min`;
  const hours = Math.round(diffMs / 3_600_000);
  if (hours < 24) return `in ${hours} h`;
  const days = Math.round(diffMs / 86_400_000);
  return `in ${days} day${days === 1 ? "" : "s"}`;
}

function formatAbsolute(when: Date): string {
  return when.toLocaleString(undefined, {
    weekday: "short",
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

const list: React.CSSProperties = {
  display: "grid",
  gap: 12,
};

const card = uiCard;

const emptyCard: React.CSSProperties = {
  background: "#10131a",
  border: "1px dashed #2a2d33",
  borderRadius: 8,
  padding: 24,
  textAlign: "center",
};

const errorBanner: React.CSSProperties = {
  marginBottom: 12,
  padding: "8px 12px",
  border: "1px solid #7f1d1d",
  background: "#1a0e0e",
  color: "#fca5a5",
  borderRadius: 6,
  fontSize: 13,
};

const metaLine: React.CSSProperties = {
  display: "flex",
  fontSize: 13,
  color: "#cbd5e1",
  marginBottom: 4,
  gap: 8,
};

const metaLabel: React.CSSProperties = {
  color: "#666",
  fontSize: 12,
  textTransform: "uppercase",
  letterSpacing: 0.5,
  minWidth: 60,
};

const btnSecondary: React.CSSProperties = { ...buttonStyle("secondary"), fontSize: 13, textDecoration: "none" };

const btnDanger: React.CSSProperties = {
  background: "transparent",
  color: "#fca5a5",
  border: "1px solid #7f1d1d",
  borderRadius: 6,
  padding: "8px 14px",
  cursor: "pointer",
  fontSize: 13,
};
