import { apiClient } from "./client";
import type { ScheduledUploadDTO } from "../types/api";

/**
 * List every still-pending scheduled upload the calling user has queued.
 * The server filters to {@code status=SCHEDULED} so this is the worklist
 * of posts that have not fired yet.
 */
export async function listScheduledUploads(): Promise<ScheduledUploadDTO[]> {
  const { data } = await apiClient.get<ScheduledUploadDTO[]>("/api/scheduled-uploads");
  return data;
}

/**
 * Cancel a scheduled upload. The row is flipped to {@code CANCELLED} so
 * it stays in the DB as an audit trail of what the user almost posted —
 * the scheduler will skip it on its next tick.
 */
export async function cancelScheduledUpload(id: string): Promise<void> {
  await apiClient.delete(`/api/scheduled-uploads/${id}`);
}
