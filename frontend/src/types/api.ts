export type VideoFormat = "REELS" | "VIDEO";

export interface GenerateJobRequest {
  question: string;
  style: string;
  durationSeconds?: number;
  /** Short-form (REELS, ≤30s, 1 scene) vs. long-form (VIDEO, multi-scene). */
  videoFormat?: VideoFormat;
}

export type MediaMode = "IMAGES" | "VIDEO_CLIP";

export interface Scene {
  scene: number;
  text: string;
  imageFiles?: string[];
  /**
   * Source clip URL(s) used when {@link mediaMode} is "VIDEO_CLIP". Parallel
   * to imageFiles kept separately so a user can flip a scene's mode back
   * and forth without losing the other side's work.
   */
  sourceVideoFiles?: string[];
  voiceFile?: string;
  videoFile?: string;
  durationSeconds?: number;
  /** Defaults to "IMAGES" when missing — older jobs predate this field. */
  mediaMode?: MediaMode;
}

export interface VideoScript {
  title: string;
  hook: string;
  scenes: Scene[];
  closing: string;
  titleScene?: Scene;
  hookScene?: Scene;
  closingScene?: Scene;
}

export interface JobFileDTO {
  jobId: string;
  createdBy: string;
  jobFilePath: string;
  videoScript: VideoScript;
}

export type SocialPlatform =
  | "YOUTUBE"
  | "FACEBOOK"
  | "INSTAGRAM"
  | "TIKTOK"
  | "TWITTER"
  | "LINKEDIN";

export interface SocialConnectionDTO {
  id: string;
  platform: SocialPlatform;
  accountHandle: string | null;
  connectedAt: string | null;
  expiresAt: string | null;
}

export interface SocialConnectionRequest {
  platform: SocialPlatform;
  accessToken: string;
  refreshToken?: string;
  accountHandle?: string;
  providerAccountId?: string;
  expiresAt?: string;
}

/**
 * Per-platform caption / title / hashtag overrides the redesigned
 * PublishModal sends back. The backend persists these on the matching
 * SocialUpload row so scheduled publishes fire with the user's chosen
 * text even when the actual upload happens hours later.
 */
export interface PlatformPostOptions {
  title?: string;
  caption?: string;
  hashtags?: string[];
}

/**
 * Body shape for POST /api/videos/{id}/publish and the asset variant.
 * Extends the legacy {@code platforms}-only request with optional
 * {@code scheduledAt} (ISO instant) and per-platform overrides.
 */
export interface VideoPublishRequest {
  platforms: SocialPlatform[];
  scheduledAt?: string | null;
  overrides?: Partial<Record<SocialPlatform, PlatformPostOptions>>;
}

export interface JobHistoryEntry {
  jobId: string;
  question: string;
  style: string;
  createdAt: string;
}

export interface JobSummaryDTO {
  jobId: string;
  title?: string | null;
  question: string;
  style: string;
  status?: string | null;
  progress?: number | null;
  createdAt: string;
}

export interface JobProgressDTO {
  jobId: string;
  status?: string | null;
  progress: number;
  stage?: string | null;
  updatedAt?: string | null;
}

export interface VideoSummaryDTO {
  videoId: string;
  jobId: string;
  title?: string | null;
  description?: string | null;
  durationSeconds: number;
  createdAt: string;
  streamUrl: string;
}

export type VideoPublishStatus =
  | "QUEUED"
  | "SCHEDULED"
  | "NOT_CONNECTED"
  | "UNSUPPORTED"
  | "ALREADY_UPLOADED";

export interface VideoPublishResult {
  platform: SocialPlatform;
  status: VideoPublishStatus;
  message?: string | null;
}

export interface VideoPublishResponse {
  videoId: string;
  results: VideoPublishResult[];
}

export type ScheduledUploadStatus =
  | "SCHEDULED"
  | "QUEUED"
  | "PROCESSING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED";

export type ScheduledUploadSourceType = "VIDEO" | "ASSET";

/**
 * One row on the ScheduledPostsPage — a per-(source, platform) upload that
 * is either still waiting for its scheduledAt to elapse or recently fired
 * (the page filters to SCHEDULED on the server but the type covers both
 * for forward-compatibility with an upcoming history view).
 */
export interface ScheduledUploadDTO {
  id: string;
  sourceId: string;
  sourceType: ScheduledUploadSourceType;
  platform: SocialPlatform;
  status: ScheduledUploadStatus;
  scheduledAt: string | null;
  title?: string | null;
  caption?: string | null;
  hashtags?: string | null;
}

export type AssetType =
  | "IMAGE"
  | "SOURCE_VIDEO"
  | "VOICE"
  | "VIDEO_CLIP"
  | "MUSIC"
  | "THUMBNAIL";

export interface AssetSummaryDTO {
  id: string;
  assetType: AssetType;
  jobId?: string | null;
  jobTitle?: string | null;
  metadata?: string | null;
  streamUrl: string;
  createdAt: string;
}

export interface PagedAssetsDTO {
  items: AssetSummaryDTO[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

// ---------------------------------------------------------------------------
// AI Tutor Twin
// ---------------------------------------------------------------------------

/** Lifecycle status shared by twins and lessons (mirrors backend Status). */
export type TutorStatus = "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface TwinDTO {
  id: string;
  name: string;
  status: TutorStatus;
  ready: boolean;
  voiceCloned: boolean;
  errorMessage?: string | null;
  createdOn: string;
}

export interface LessonDTO {
  id: string;
  twinId: string;
  topic: string;
  style?: string | null;
  status: TutorStatus;
  scriptContent?: string | null;
  durationSeconds?: number | null;
  hasVideo: boolean;
  videoId?: string | null;
  errorMessage?: string | null;
  createdOn: string;
}

export interface CreateLessonRequest {
  twinId: string;
  topic: string;
  style?: string;
}