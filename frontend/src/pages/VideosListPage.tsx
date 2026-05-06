import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { listConnections } from "../api/social";
import { listVideos, publishVideo, resolveStreamUrl } from "../api/videos";
import type {
  SocialConnectionDTO,
  SocialPlatform,
  VideoPublishResult,
  VideoSummaryDTO,
} from "../types/api";

export function VideosListPage() {
  const [videos, setVideos] = useState<VideoSummaryDTO[]>([]);
  const [connections, setConnections] = useState<SocialConnectionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([listVideos(), listConnections()])
      .then(([v, c]) => {
        if (cancelled) return;
        setVideos(v);
        setConnections(c);
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

  return (
    <div>
      <div style={{ marginBottom: 24 }}>
        <h2 style={{ margin: 0 }}>Your videos</h2>
        <p style={{ color: "#888", marginTop: 6 }}>
          Rendered videos for every job you've completed. Streams from the backend so they
          play directly here.
        </p>
      </div>

      {loading ? (
        <div style={{ ...card, color: "#aaa" }}>Loading...</div>
      ) : error ? (
        <div style={{ ...card, color: "#ff6b6b" }}>{error}</div>
      ) : videos.length === 0 ? (
        <div style={{ ...card, color: "#aaa" }}>
          No videos yet. Completed jobs show up here once the pipeline has rendered them.
        </div>
      ) : (
        <div style={{ display: "grid", gap: 24 }}>
          {videos.map((v) => (
            <VideoCard key={v.videoId} video={v} connections={connections} />
          ))}
        </div>
      )}
    </div>
  );
}

interface VideoCardProps {
  video: VideoSummaryDTO;
  connections: SocialConnectionDTO[];
}

function VideoCard({ video, connections }: VideoCardProps) {
  const src = useMemo(() => resolveStreamUrl(video.streamUrl), [video.streamUrl]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [selected, setSelected] = useState<Set<SocialPlatform>>(new Set());
  const [publishing, setPublishing] = useState(false);
  const [results, setResults] = useState<VideoPublishResult[]>([]);
  const [error, setError] = useState<string | null>(null);

  const togglePlatform = (p: SocialPlatform) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(p)) next.delete(p);
      else next.add(p);
      return next;
    });
  };

  const handlePublish = async () => {
    if (selected.size === 0) return;
    setPublishing(true);
    setError(null);
    try {
      const response = await publishVideo(video.videoId, Array.from(selected));
      setResults(response.results);
    } catch (err) {
      setError(extractError(err));
    } finally {
      setPublishing(false);
    }
  };

  return (
    <div style={card}>
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "baseline",
          marginBottom: 8,
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <div style={{ fontWeight: 600, fontSize: 16 }}>
          {video.title?.trim() ? video.title : "(untitled)"}
        </div>
        <div style={{ fontSize: 12, color: "#888" }}>
          {formatDuration(video.durationSeconds)} · {formatDate(video.createdAt)}
        </div>
      </div>
      <div style={{ fontSize: 12, color: "#aaa", fontFamily: "monospace", marginBottom: 12 }}>
        Job:{" "}
        <Link to={`/jobs/${video.jobId}`} style={{ color: "#60a5fa" }}>
          {video.jobId}
        </Link>
      </div>

      <div style={{ position: "relative" }}>
        <video
          src={src}
          controls
          preload="metadata"
          style={{ width: "100%", borderRadius: 6, background: "#000", display: "block" }}
        />
        <button
          onClick={() => setPickerOpen((o) => !o)}
          style={uploadBtn}
          title="Upload to connected social accounts"
        >
          ⬆ Upload
        </button>
      </div>

      {pickerOpen && (
        <UploadPanel
          connections={connections}
          selected={selected}
          onToggle={togglePlatform}
          onPublish={handlePublish}
          publishing={publishing}
          error={error}
          results={results}
        />
      )}
    </div>
  );
}

interface UploadPanelProps {
  connections: SocialConnectionDTO[];
  selected: Set<SocialPlatform>;
  onToggle: (p: SocialPlatform) => void;
  onPublish: () => void;
  publishing: boolean;
  error: string | null;
  results: VideoPublishResult[];
}

function UploadPanel({
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
      <div style={{ fontSize: 13, color: "#aaa", marginBottom: 8 }}>
        Upload to:
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 8, marginBottom: 12 }}>
        {connections.map((c) => {
          const isSelected = selected.has(c.platform);
          return (
            <button
              key={c.id}
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

function platformLabel(p: SocialPlatform): string {
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

function formatDuration(seconds: number): string {
  if (!seconds || seconds < 0) return "—";
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

function formatDate(value: string | null | undefined): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  const dd = String(d.getDate()).padStart(2, "0");
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const yyyy = d.getFullYear();
  return `${dd}-${mm}-${yyyy}`;
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Could not load videos.";
}

const card: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #1f2125",
  borderRadius: 8,
  padding: 16,
};

const panel: React.CSSProperties = {
  marginTop: 12,
  padding: 12,
  background: "#0f1115",
  border: "1px solid #1f2125",
  borderRadius: 6,
};

const uploadBtn: React.CSSProperties = {
  position: "absolute",
  top: 10,
  right: 10,
  background: "rgba(15, 17, 21, 0.85)",
  color: "#fff",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "6px 12px",
  cursor: "pointer",
  fontWeight: 600,
  fontSize: 13,
  backdropFilter: "blur(4px)",
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