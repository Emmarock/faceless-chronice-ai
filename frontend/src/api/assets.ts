import { apiBaseUrl, apiClient } from "./client";
import type { AssetSummaryDTO, AssetType, PagedAssetsDTO } from "../types/api";

/**
 * Query parameters for the paginated asset library listing. All fields are
 * optional; the server applies sensible defaults (page=0, size=24) and
 * clamps {@code size} to a sane range. {@code type} is the asset-type filter
 * — omit it for the "All" view (which the server further denylists to skip
 * voice / music / thumbnail rows).
 */
export interface ListAssetsParams {
  type?: AssetType;
  page?: number;
  size?: number;
}

export async function listAssets(params: ListAssetsParams = {}): Promise<PagedAssetsDTO> {
  const query: Record<string, string | number> = {};
  if (params.type) query.type = params.type;
  if (params.page != null) query.page = params.page;
  if (params.size != null) query.size = params.size;
  const { data } = await apiClient.get<PagedAssetsDTO>("/api/assets", { params: query });
  return data;
}

export async function uploadAsset(type: AssetType, file: File): Promise<AssetSummaryDTO> {
  const form = new FormData();
  form.append("file", file);
  const { data } = await apiClient.post<AssetSummaryDTO>(
    `/api/assets/upload?type=${encodeURIComponent(type)}`,
    form,
    { headers: { "Content-Type": "multipart/form-data" } },
  );
  return data;
}

export async function deleteAsset(assetId: string): Promise<void> {
  await apiClient.delete(`/api/assets/${assetId}`);
}

/**
 * Server-side copy this asset into the given (jobId, sceneId) slot.
 * Images are appended at the end of the scene's image list; source videos
 * replace whatever clip the scene currently has. Triggers the same per-scene
 * cache invalidation as a manual upload, so the next /resume re-renders only
 * what changed.
 */
export async function reuseAssetInScene(
  assetId: string,
  jobId: string,
  sceneId: number,
): Promise<void> {
  await apiClient.post(`/api/assets/${assetId}/reuse`, null, {
    params: { jobId, sceneId },
  });
}

/**
 * Browser-loadable URL for an asset's bytes. Prefixed with {@link apiBaseUrl}
 * for cross-origin deployments (frontend on Vercel + backend on Railway, etc).
 * Append a cache buster when reusing the same {@code <img>} element after a
 * mutation.
 */
export function assetStreamUrl(assetId: string, version?: number): string {
  const v = version != null ? `?v=${version}` : "";
  return `${apiBaseUrl}/api/assets/${assetId}/raw${v}`;
}