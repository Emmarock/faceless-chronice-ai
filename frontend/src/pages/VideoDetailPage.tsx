import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { listConnections } from "../api/social";
import { getVideoByJobId } from "../api/videos";
import { VideoCard } from "../components/VideoCard";
import { card as uiCard } from "../components/ui";
import type { SocialConnectionDTO, VideoSummaryDTO } from "../types/api";

/**
 * Single-video page reached from the jobs list when the user clicks a
 * completed job. Fetches just the one video by job id so the page renders
 * without loading the user's entire library.
 */
export function VideoDetailPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const [video, setVideo] = useState<VideoSummaryDTO | null>(null);
  const [connections, setConnections] = useState<SocialConnectionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!jobId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([getVideoByJobId(jobId), listConnections()])
      .then(([v, c]) => {
        if (cancelled) return;
        setVideo(v);
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
  }, [jobId]);

  return (
    <div>
      <div style={{ marginBottom: 24, display: "flex", justifyContent: "space-between", gap: 12, flexWrap: "wrap", alignItems: "center" }}>
        <h2 style={{ margin: 0 }}>Your video</h2>
        <Link to="/videos" style={backLink}>← All videos</Link>
      </div>

      {loading ? (
        <div style={{ ...card, color: "#aaa" }}>Loading...</div>
      ) : error ? (
        <div style={{ ...card, color: "#ff6b6b" }}>{error}</div>
      ) : !video ? (
        <div style={{ ...card, color: "#aaa" }}>Video not found.</div>
      ) : (
        <VideoCard video={video} connections={connections} />
      )}
    </div>
  );
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Could not load video.";
}

const card = uiCard;

const backLink: React.CSSProperties = {
  color: "#60a5fa",
  textDecoration: "none",
  fontSize: 14,
};