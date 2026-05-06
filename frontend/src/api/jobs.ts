import { apiBaseUrl, apiClient } from "./client";
import type {
  GenerateJobRequest,
  JobFileDTO,
  JobProgressDTO,
  JobSummaryDTO,
  VideoScript,
} from "../types/api";

export async function generateJob(request: GenerateJobRequest): Promise<JobFileDTO> {
  const { data } = await apiClient.post<JobFileDTO>("/api/job-file/generate", request);
  return data;
}

export async function listJobs(): Promise<JobSummaryDTO[]> {
  const { data } = await apiClient.get<JobSummaryDTO[]>("/api/job-file");
  return data;
}

export async function getJob(jobId: string): Promise<JobFileDTO> {
  const { data } = await apiClient.get<JobFileDTO>(`/api/job-file/${jobId}`);
  return data;
}

export async function updateScript(jobId: string, videoScript: VideoScript): Promise<JobFileDTO> {
  const { data } = await apiClient.put<JobFileDTO>(`/api/job-file/${jobId}/script`, videoScript);
  return data;
}

export async function resumeJob(jobId: string): Promise<JobFileDTO> {
  const { data } = await apiClient.post<JobFileDTO>(`/api/job-file/${jobId}/resume`);
  return data;
}

/**
 * Asks the backend (and downstream the AI) to rewrite every regular scene's
 * text. Title / hook / closing are preserved; every regenerated scene's
 * voice + images + rendered clip are dropped so the next Resume rebuilds
 * them from the new lines.
 */
export async function regenerateScenes(jobId: string): Promise<JobFileDTO> {
  const { data } = await apiClient.post<JobFileDTO>(`/api/job-file/${jobId}/scenes/regenerate`);
  return data;
}

/**
 * Rewrites the text of a single scene only. Sibling scenes' artifacts are
 * left in place; only this scene's voice / images / clip and the final
 * concatenated video are invalidated.
 */
export async function regenerateScene(jobId: string, sceneId: number): Promise<JobFileDTO> {
  const { data } = await apiClient.post<JobFileDTO>(
    `/api/job-file/${jobId}/scenes/${sceneId}/regenerate`,
  );
  return data;
}

// ------------------------------------------------------------------ //
//  Progress polling
// ------------------------------------------------------------------ //

export async function getJobProgress(jobId: string): Promise<JobProgressDTO> {
  const { data } = await apiClient.get<JobProgressDTO>(`/api/job-file/${jobId}/progress`);
  return data;
}

export async function listJobProgress(): Promise<JobProgressDTO[]> {
  const { data } = await apiClient.get<JobProgressDTO[]>(`/api/job-file/progress`);
  return data;
}

// ------------------------------------------------------------------ //
//  Per-scene image editing
// ------------------------------------------------------------------ //

/**
 * Browser-loadable URL for the image at slot (sceneId, index). The backend
 * streams the bytes from S3 with no-cache headers, so a re-upload at the same
 * slot is reflected on the next render. Append a cache-buster to defeat the
 * disk cache after a mutation when reusing the same `<img>` element.
 *
 * Prefixed with {@link apiBaseUrl} so deployments where the frontend lives on
 * a different origin (Vercel + Railway, etc.) load the bytes from the API
 * host instead of 404-ing against the static-asset CDN.
 */
export function imageStreamUrl(jobId: string, sceneId: number, index: number, version?: number): string {
  const v = version != null ? `?v=${version}` : "";
  return `${apiBaseUrl}/api/job-file/${jobId}/scenes/${sceneId}/images/${index}/raw${v}`;
}

export async function replaceSceneImage(
  jobId: string,
  sceneId: number,
  index: number,
  file: File,
): Promise<JobFileDTO> {
  const form = new FormData();
  form.append("file", file);
  const { data } = await apiClient.put<JobFileDTO>(
    `/api/job-file/${jobId}/scenes/${sceneId}/images/${index}`,
    form,
    { headers: { "Content-Type": "multipart/form-data" } },
  );
  return data;
}

export async function appendSceneImage(
  jobId: string,
  sceneId: number,
  file: File,
): Promise<{ index: number; jobFile: JobFileDTO }> {
  const form = new FormData();
  form.append("file", file);
  const { data } = await apiClient.post<{ index: number; jobFile: JobFileDTO }>(
    `/api/job-file/${jobId}/scenes/${sceneId}/images`,
    form,
    { headers: { "Content-Type": "multipart/form-data" } },
  );
  return data;
}

export async function deleteSceneImage(
  jobId: string,
  sceneId: number,
  index: number,
): Promise<JobFileDTO> {
  const { data } = await apiClient.delete<JobFileDTO>(
    `/api/job-file/${jobId}/scenes/${sceneId}/images/${index}`,
  );
  return data;
}

// ------------------------------------------------------------------ //
//  Per-scene source video editing (VIDEO_CLIP mode)
//
//  One source clip per scene — no index, no append. Mutations invalidate
//  the rendered per-scene clip + final video, same as image edits do.
// ------------------------------------------------------------------ //

export function sourceVideoStreamUrl(jobId: string, sceneId: number, version?: number): string {
  const v = version != null ? `?v=${version}` : "";
  return `${apiBaseUrl}/api/job-file/${jobId}/scenes/${sceneId}/source-video/raw${v}`;
}

export async function replaceSourceVideo(
  jobId: string,
  sceneId: number,
  file: File,
): Promise<JobFileDTO> {
  const form = new FormData();
  form.append("file", file);
  const { data } = await apiClient.put<JobFileDTO>(
    `/api/job-file/${jobId}/scenes/${sceneId}/source-video`,
    form,
    { headers: { "Content-Type": "multipart/form-data" } },
  );
  return data;
}

export async function deleteSourceVideo(jobId: string, sceneId: number): Promise<JobFileDTO> {
  const { data } = await apiClient.delete<JobFileDTO>(
    `/api/job-file/${jobId}/scenes/${sceneId}/source-video`,
  );
  return data;
}