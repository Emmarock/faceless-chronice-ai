export interface GenerateJobRequest {
  question: string;
  style: string;
  durationSeconds?: number;
}

export type MediaMode = "IMAGES" | "VIDEO_CLIP";

export interface Scene {
  scene: number;
  text: string;
  imageFiles?: string[];
  /**
   * Source clip URL(s) used when {@link mediaMode} is "VIDEO_CLIP". Parallel
   * to imageFiles — kept separately so a user can flip a scene's mode back
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

export type SocialPlatform = "YOUTUBE" | "FACEBOOK" | "TIKTOK" | "TWITTER";

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
  expiresAt?: string;
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