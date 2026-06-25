import { apiClient } from "./client";
import type { CreateLessonRequest, LessonDTO, TwinDTO } from "../types/api";

// ---------------------------------------------------------------------------
// Twins
// ---------------------------------------------------------------------------

/**
 * Upload/record a short clip to start training an AI tutor twin (HeyGen
 * avatar + cloned voice). Returns the QUEUED/PROCESSING twin — poll
 * {@link getTwin} until {@code ready}.
 */
export async function createTwin(name: string, video: Blob): Promise<TwinDTO> {
  const form = new FormData();
  // Give the recorded blob a filename so the backend infers the right
  // content-type extension when uploading to HeyGen.
  const file = video instanceof File ? video : new File([video], "twin.webm", { type: video.type || "video/webm" });
  form.append("video", file);
  if (name) form.append("name", name);
  const { data } = await apiClient.post<TwinDTO>("/api/twins", form);
  return data;
}

export async function listTwins(): Promise<TwinDTO[]> {
  const { data } = await apiClient.get<TwinDTO[]>("/api/twins");
  return data;
}

export async function getTwin(id: string): Promise<TwinDTO> {
  const { data } = await apiClient.get<TwinDTO>(`/api/twins/${id}`);
  return data;
}

export async function deleteTwin(id: string): Promise<void> {
  await apiClient.delete(`/api/twins/${id}`);
}

// ---------------------------------------------------------------------------
// Lessons
// ---------------------------------------------------------------------------

export async function createLesson(request: CreateLessonRequest): Promise<LessonDTO> {
  const { data } = await apiClient.post<LessonDTO>("/api/lessons", request);
  return data;
}

export async function listLessons(): Promise<LessonDTO[]> {
  const { data } = await apiClient.get<LessonDTO[]>("/api/lessons");
  return data;
}

export async function getLesson(id: string): Promise<LessonDTO> {
  const { data } = await apiClient.get<LessonDTO>(`/api/lessons/${id}`);
  return data;
}

/**
 * Absolute URL the `<video>` tag can load directly. The lesson stream endpoint
 * is unauthenticated (same posture as the video stream) so no header juggling
 * is needed — the lesson UUID is the bearer.
 */
export function lessonStreamUrl(id: string): string {
  const base = apiClient.defaults.baseURL ?? "";
  return `${base.replace(/\/$/, "")}/api/lessons/${id}/stream`;
}
