import { Link } from "react-router-dom";
import type {
  SocialConnectionDTO,
  SocialPlatform,
  VideoPublishResult,
} from "../types/api";

interface UploadPanelProps {
  connections: SocialConnectionDTO[];
  selected: Set<SocialPlatform>;
  onToggle: (p: SocialPlatform) => void;
  onPublish: () => void;
  publishing: boolean;
  error: string | null;
  results: VideoPublishResult[];
}

/**
 * Per-target platform picker + publish button. Used by both the rendered-
 * video list and the asset library's per-clip publish flow — the only
 * difference between callers is which API endpoint is hit, which is the
 * caller's job to wire into {@code onPublish}.
 */
export function UploadPanel({
  connections,
  selected,
  onToggle,
  onPublish,
  publishing,
  error,
  results,
}: UploadPanelProps) {
  if (connections.length === 0) {
    return (
      <div style={panel}>
        <div style={{ color: "#aaa" }}>
          No connected accounts.{" "}
          <Link to="/connections" style={{ color: "#60a5fa" }}>
            Connect a platform first
          </Link>
          .
        </div>
      </div>
    );
  }

  return (
    <div style={panel}>
      <div style={{ fontSize: 13, color: "#aaa", marginBottom: 8 }}>Upload to:</div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 8, marginBottom: 12 }}>
        {connections.map((c) => {
          const isSelected = selected.has(c.platform);
          return (
            <button
              key={c.id}
              type="button"
              onClick={() => onToggle(c.platform)}
              style={{
                ...chip,
                background: isSelected ? "#1d4ed8" : "#1f2937",
                borderColor: isSelected ? "#3b82f6" : "#2a2d33",
              }}
            >
              <span style={{ fontWeight: 600 }}>{platformLabel(c.platform)}</span>
              {c.accountHandle && (
                <span style={{ color: "#aaa", marginLeft: 6, fontSize: 11 }}>
                  {c.accountHandle}
                </span>
              )}
            </button>
          );
        })}
      </div>
      <button
        type="button"
        onClick={onPublish}
        disabled={publishing || selected.size === 0}
        style={{
          ...primaryBtn,
          opacity: publishing || selected.size === 0 ? 0.6 : 1,
        }}
      >
        {publishing
          ? "Publishing..."
          : `Publish to ${selected.size} ${selected.size === 1 ? "account" : "accounts"}`}
      </button>
      {error && <div style={{ color: "#ff6b6b", marginTop: 10 }}>{error}</div>}
      {results.length > 0 && (
        <div style={{ marginTop: 12, display: "grid", gap: 6 }}>
          {results.map((r) => (
            <div
              key={r.platform}
              style={{
                fontSize: 12,
                color: statusColor(r.status),
                background: "#0f1115",
                border: "1px solid #2a2d33",
                borderRadius: 6,
                padding: "6px 10px",
              }}
            >
              <strong>{platformLabel(r.platform)}</strong>: {r.status}
              {r.message ? ` — ${r.message}` : ""}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function platformLabel(p: SocialPlatform): string {
  switch (p) {
    case "YOUTUBE":
      return "YouTube";
    case "FACEBOOK":
      return "Facebook";
    case "TIKTOK":
      return "TikTok";
    case "TWITTER":
      return "Twitter / X";
    default:
      return p;
  }
}

function statusColor(status: string): string {
  switch (status) {
    case "QUEUED":
      return "#4ade80";
    case "ALREADY_UPLOADED":
      return "#60a5fa";
    case "NOT_CONNECTED":
    case "UNSUPPORTED":
      return "#fbbf24";
    default:
      return "#aaa";
  }
}

const panel: React.CSSProperties = {
  marginTop: 12,
  padding: 12,
  background: "#0f1115",
  border: "1px solid #1f2125",
  borderRadius: 6,
};

const chip: React.CSSProperties = {
  border: "1px solid",
  borderRadius: 999,
  padding: "6px 12px",
  cursor: "pointer",
  color: "#e6e6e6",
  fontSize: 13,
};

const primaryBtn: React.CSSProperties = {
  background: "#3b82f6",
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "8px 14px",
  cursor: "pointer",
  fontWeight: 600,
};