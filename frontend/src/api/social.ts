import { apiClient } from "./client";
import type {
  SocialConnectionDTO,
  SocialConnectionRequest,
  SocialPlatform,
} from "../types/api";

export async function listConnections(): Promise<SocialConnectionDTO[]> {
  const { data } = await apiClient.get<SocialConnectionDTO[]>("/api/social-connections");
  return data;
}

export async function saveConnection(
  request: SocialConnectionRequest,
): Promise<SocialConnectionDTO> {
  const { data } = await apiClient.post<SocialConnectionDTO>("/api/social-connections", request);
  return data;
}

export async function disconnect(platform: SocialPlatform): Promise<void> {
  await apiClient.delete(`/api/social-connections/${platform}`);
}

export async function exchangeYouTubeCode(
  code: string,
  redirectUri: string = "postmessage",
): Promise<SocialConnectionDTO> {
  const { data } = await apiClient.post<SocialConnectionDTO>(
    "/api/social-connections/youtube/oauth-exchange",
    { code, redirectUri },
  );
  return data;
}

export async function exchangeTwitterCode(
  code: string,
  redirectUri: string,
  codeVerifier: string,
): Promise<SocialConnectionDTO> {
  const { data } = await apiClient.post<SocialConnectionDTO>(
    "/api/social-connections/twitter/oauth-exchange",
    { code, redirectUri, codeVerifier },
  );
  return data;
}

export async function exchangeTikTokCode(
  code: string,
  redirectUri: string,
  codeVerifier: string,
): Promise<SocialConnectionDTO> {
  const { data } = await apiClient.post<SocialConnectionDTO>(
    "/api/social-connections/tiktok/oauth-exchange",
    { code, redirectUri, codeVerifier },
  );
  return data;
}

export async function exchangeFacebookCode(
  code: string,
  redirectUri: string,
): Promise<SocialConnectionDTO> {
  const { data } = await apiClient.post<SocialConnectionDTO>(
    "/api/social-connections/facebook/oauth-exchange",
    { code, redirectUri },
  );
  return data;
}