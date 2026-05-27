import { apiClient } from "./client";
import type {
  VideoPublishRequest,
  VideoPublishResponse,
  VideoSummaryDTO,
} from "../types/api";

export async function listVideos(): Promise<VideoSummaryDTO[]> {
  const { data } = await apiClient.get<VideoSummaryDTO[]>("/api/videos");
  return data;
}

export async function getVideoByJobId(jobId: string): Promise<VideoSummaryDTO> {
  const { data } = await apiClient.get<VideoSummaryDTO>(`/api/videos/by-job/${jobId}`);
  return data;
}

/**
 * Queue (or schedule) a video for cross-posting. Accepts the full
 * VideoPublishRequest shape so the redesigned PublishModal can pass
 * per-platform overrides and a future {@code scheduledAt} instant in one
 * call.
 */
export async function publishVideo(
  videoId: string,
  request: VideoPublishRequest,
): Promise<VideoPublishResponse> {
  const { data } = await apiClient.post<VideoPublishResponse>(
    `/api/videos/${videoId}/publish`,
    request,
  );
  return data;
}

/**
 * Resolve a backend-relative streamUrl to an absolute URL the browser
 * `<video>` tag can load directly. The streaming endpoint is unauthenticated
 * (same posture as the rest of the API), so no header juggling is needed.
 */
export function resolveStreamUrl(streamUrl: string): string {
  const base = apiClient.defaults.baseURL ?? "";
  if (streamUrl.startsWith("http")) return streamUrl;
  return `${base.replace(/\/$/, "")}${streamUrl}`;
}

/**
 * Absolute URL for the per-video download endpoint. Hitting this triggers a
 * file download (Content-Disposition: attachment) so the user can save the
 * .mp4 and upload it manually to platforms we don't integrate with yet.
 */
export function videoDownloadUrl(videoId: string): string {
  const base = apiClient.defaults.baseURL ?? "";
  return `${base.replace(/\/$/, "")}/api/videos/${videoId}/download`;
}