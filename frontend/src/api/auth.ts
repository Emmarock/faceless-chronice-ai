import { apiClient } from "./client";

export type AuthProvider = "GOOGLE" | "FACEBOOK";

export interface SignInPayload {
  provider: AuthProvider;
  providerUserId: string;
  email?: string;
  name?: string;
  picture?: string;
}

/**
 * Notifies the backend that a user just authenticated through a client-side
 * OAuth flow (Google One Tap or Facebook JS SDK). The backend uses this to
 * create / refresh the corresponding {@code AppUser} + {@code UserIdentity}
 * rows so we have a directory of who's using the app — the foundation for
 * real signup / login.
 *
 * <p>Deliberately fire-and-forget at the call site: a failure here must not
 * block the UI sign-in (the user can still use the app; we'd just be missing
 * a row in the directory until next login).
 */
export async function recordSignIn(payload: SignInPayload): Promise<void> {
  await apiClient.post("/api/auth/sign-in", payload);
}