import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { listConnections } from "../api/social";
import { listVideos, publishVideo, resolveStreamUrl } from "../api/videos";
import { UploadPanel } from "../components/UploadPanel";
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