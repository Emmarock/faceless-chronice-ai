import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  publishVideo,
  resolveStreamUrl,
  videoDownloadUrl,
} from "../api/videos";
import { PublishModal } from "./PublishModal";
import type {
  SocialConnectionDTO,
  VideoPublishRequest,
  VideoSummaryDTO,
} from "../types/api";

interface VideoCardProps {
  video: VideoSummaryDTO;
  connections: SocialConnectionDTO[];
  /** Optional DOM id forwarded to the wrapper so deep-links can scroll to it. */
  anchorId?: string;
}

export function VideoCard({ video, connections, anchorId }: VideoCardProps) {
  const src = useMemo(() => resolveStreamUrl(video.streamUrl), [video.streamUrl]);
  const [pickerOpen, setPickerOpen] = useState(false);

  return (
    <div id={anchorId} style={card}>
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
        <div style={overlayActions}>
          <a
            href={videoDownloadUrl(video.videoId)}
            download
            style={downloadBtn}
            title="Download the rendered .mp4 to your computer"
          >
            ⬇ Download
          </a>
          <button
            onClick={() => setPickerOpen((o) => !o)}
            style={uploadBtn}
            title="Upload to connected social accounts"
          >
            ⬆ Upload
          </button>
        </div>
      </div>

      <PublishModal
        open={pickerOpen}
        title={video.title?.trim() ? video.title : "(untitled)"}
        defaultTitle={video.title ?? ""}
        defaultDescription={video.description ?? ""}
        connections={connections}
        videoFormat={null}
        onClose={() => setPickerOpen(false)}
        onSubmit={async (request: VideoPublishRequest) => {
          const response = await publishVideo(video.videoId, request);
          return response.results;
        }}
      />
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

const card: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #1f2125",
  borderRadius: 8,
  padding: 16,
};

const overlayActions: React.CSSProperties = {
  position: "absolute",
  top: 10,
  right: 10,
  display: "flex",
  gap: 8,
};

const overlayBtnBase: React.CSSProperties = {
  background: "rgba(15, 17, 21, 0.85)",
  color: "#fff",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "6px 12px",
  cursor: "pointer",
  fontWeight: 600,
  fontSize: 13,
  backdropFilter: "blur(4px)",
  textDecoration: "none",
  lineHeight: 1.4,
};

const uploadBtn: React.CSSProperties = overlayBtnBase;
const downloadBtn: React.CSSProperties = overlayBtnBase;