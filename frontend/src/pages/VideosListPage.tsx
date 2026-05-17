import { useEffect, useState } from "react";
import { useLocation } from "react-router-dom";
import { listConnections } from "../api/social";
import { listVideos } from "../api/videos";
import { VideoCard } from "../components/VideoCard";
import type { SocialConnectionDTO, VideoSummaryDTO } from "../types/api";

export function VideosListPage() {
  const [videos, setVideos] = useState<VideoSummaryDTO[]>([]);
  const [connections, setConnections] = useState<SocialConnectionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const location = useLocation();

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

  // Deep-link target — when arriving with a `#job-<id>` hash, scroll the
  // matching card into view once the videos render. Native hash scroll fires
  // before our async data loads, so we re-run it ourselves.
  useEffect(() => {
    if (loading || videos.length === 0) return;
    const hash = location.hash;
    if (!hash) return;
    const el = document.getElementById(hash.slice(1));
    if (!el) return;
    el.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [loading, videos, location.hash]);

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
            <VideoCard
              key={v.videoId}
              video={v}
              connections={connections}
              anchorId={`job-${v.jobId}`}
            />
          ))}
        </div>
      )}
    </div>
  );
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