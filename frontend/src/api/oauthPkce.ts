// PKCE + OAuth-popup helpers shared by Twitter and TikTok connect flows.
// YouTube uses Google's hosted JS, which handles all of this internally.

export interface PkcePair {
  verifier: string;
  challenge: string;
}

const VERIFIER_CHARS =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

export async function makePkcePair(): Promise<PkcePair> {
  const verifier = randomString(64);
  const challenge = await sha256Base64Url(verifier);
  return { verifier, challenge };
}

export function randomString(length: number): string {
  const arr = new Uint8Array(length);
  crypto.getRandomValues(arr);
  let out = "";
  for (let i = 0; i < length; i++) {
    out += VERIFIER_CHARS[arr[i] % VERIFIER_CHARS.length];
  }
  return out;
}

async function sha256Base64Url(value: string): Promise<string> {
  const encoded = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", encoded);
  return base64UrlEncode(new Uint8Array(digest));
}

function base64UrlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export interface OAuthPopupResult {
  code: string;
  state: string;
}

/**
 * Opens an OAuth provider authorize URL in a popup window and resolves once
 * the popup posts back the auth code via {@code window.opener.postMessage}.
 *
 * The callback page (mounted at /oauth/callback) is responsible for parsing
 * the redirect query params and posting them back as
 * {@code { type: "fc:oauth", code, state } } before closing itself.
 */
export function openOAuthPopup(authorizeUrl: string, expectedState: string): Promise<OAuthPopupResult> {
  return new Promise((resolve, reject) => {
    const width = 520;
    const height = 640;
    const left = window.screenX + (window.innerWidth - width) / 2;
    const top = window.screenY + (window.innerHeight - height) / 2;
    const popup = window.open(
      authorizeUrl,
      "fc-oauth",
      `width=${width},height=${height},left=${left},top=${top}`,
    );
    if (!popup) {
      reject(new Error("Popup blocked. Allow popups for this site and retry."));
      return;
    }

    const cleanup = () => {
      window.removeEventListener("message", onMessage);
      window.clearInterval(closeWatch);
    };

    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      const data = event.data as
        | { type?: string; code?: string; state?: string; error?: string }
        | null;
      if (!data || data.type !== "fc:oauth") return;
      cleanup();
      try {
        popup.close();
      } catch {
        /* ignore */
      }
      if (data.error) {
        reject(new Error(data.error));
        return;
      }
      if (!data.code || !data.state) {
        reject(new Error("Authorization callback missing code or state."));
        return;
      }
      if (data.state !== expectedState) {
        reject(new Error("OAuth state mismatch — possible CSRF, refusing."));
        return;
      }
      resolve({ code: data.code, state: data.state });
    };

    const closeWatch = window.setInterval(() => {
      if (popup.closed) {
        cleanup();
        reject(new Error("Authorization window was closed before completing."));
      }
    }, 500);

    window.addEventListener("message", onMessage);
  });
}