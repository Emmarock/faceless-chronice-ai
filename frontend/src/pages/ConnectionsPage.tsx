import { useGoogleLogin } from "@react-oauth/google";
import { useEffect, useState } from "react";
import {
  disconnect,
  exchangeFacebookCode,
  exchangeInstagramCode,
  exchangeLinkedInCode,
  exchangeTikTokCode,
  exchangeTwitterCode,
  exchangeYouTubeCode,
  listConnections,
} from "../api/social";
import { makePkcePair, openOAuthPopup, randomString } from "../api/oauthPkce";
import type { SocialConnectionDTO, SocialPlatform } from "../types/api";

interface PlatformMeta {
  platform: SocialPlatform;
  label: string;
  description: string;
  color: string;
}

const PLATFORMS: PlatformMeta[] = [
  {
    platform: "YOUTUBE",
    label: "YouTube",
    description: "Upload generated videos to your channel.",
    color: "#ff0033",
  },
  {
    platform: "FACEBOOK",
    label: "Facebook",
    description: "Post videos to your Facebook page.",
    color: "#1877f2",
  },
  {
    platform: "INSTAGRAM",
    label: "Instagram",
    description: "Publish Reels to your linked Instagram Business account.",
    color: "#e1306c",
  },
  {
    platform: "TIKTOK",
    label: "TikTok",
    description: "Send rendered videos to your TikTok inbox to review and post.",
    color: "#000000",
  },
  {
    platform: "TWITTER",
    label: "Twitter / X",
    description: "Tweet rendered videos with the script title as the caption.",
    color: "#1d9bf0",
  },
  {
    platform: "LINKEDIN",
    label: "LinkedIn",
    description: "Share videos on your LinkedIn feed.",
    color: "#0a66c2",
  },
];

/**
 * Per-platform connect error. Most platforms only carry a string message,
 * but Facebook can fail with a structured "no Page on this account" code
 * we render as a dedicated empty-state card with a Create-a-Page CTA
 * instead of a raw red error string.
 */
type ConnectError =
  | { kind: "noFacebookPage"; message: string }
  | { kind: "generic"; message: string };

export function ConnectionsPage() {
  const [connections, setConnections] = useState<SocialConnectionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [platformErrors, setPlatformErrors] = useState<Partial<Record<SocialPlatform, ConnectError>>>({});
  const [busy, setBusy] = useState<SocialPlatform | null>(null);

  const setPlatformError = (platform: SocialPlatform, err: ConnectError | null) => {
    setPlatformErrors((prev) => {
      const next = { ...prev };
      if (err) next[platform] = err;
      else delete next[platform];
      return next;
    });
  };

  useEffect(() => {
    refresh();
  }, []);

  async function refresh() {
    setLoading(true);
    try {
      setConnections(await listConnections());
      setError(null);
    } catch (err) {
      setError(extractError(err));
    } finally {
      setLoading(false);
    }
  }

  // Auth-code flow (instead of implicit) so the backend receives a refresh
  // token and can keep uploading after the 1h access token expires.
  // `select_account: true` forces Google to show the account picker on every
  // connect, which side-steps the case where a user previously granted
  // implicit-flow access (Google won't re-issue a refresh token to an
  // already-authorized app unless consent is re-requested).
  const googleConnect = useGoogleLogin({
    flow: "auth-code",
    scope: "https://www.googleapis.com/auth/youtube.upload email profile",
    select_account: true,
    onSuccess: async (response) => {
      try {
        await exchangeYouTubeCode(response.code);
        await refresh();
      } catch (err) {
        setError(extractError(err));
      } finally {
        setBusy(null);
      }
    },
    onError: () => {
      setBusy(null);
      setError("Google authorization failed.");
    },
  });

  const handleConnect = async (platform: SocialPlatform) => {
    setBusy(platform);
    setError(null);
    setPlatformError(platform, null);

    if (platform === "YOUTUBE") {
      googleConnect();
      return;
    }

    if (platform === "FACEBOOK") {
      try {
        await connectFacebook();
        await refresh();
      } catch (err) {
        const code = extractErrorCode(err);
        if (code === "NO_FACEBOOK_PAGE") {
          setPlatformError(platform, { kind: "noFacebookPage", message: extractError(err) });
        } else {
          setPlatformError(platform, { kind: "generic", message: extractError(err) });
        }
      } finally {
        setBusy(null);
      }
      return;
    }

    if (platform === "TWITTER") {
      try {
        await connectTwitter();
        await refresh();
      } catch (err) {
        setError(extractError(err));
      } finally {
        setBusy(null);
      }
      return;
    }

    if (platform === "TIKTOK") {
      try {
        await connectTikTok();
        await refresh();
      } catch (err) {
        setError(extractError(err));
      } finally {
        setBusy(null);
      }
      return;
    }

    if (platform === "INSTAGRAM") {
      try {
        await connectInstagram();
        await refresh();
      } catch (err) {
        setPlatformError(platform, { kind: "generic", message: extractError(err) });
      } finally {
        setBusy(null);
      }
      return;
    }

    if (platform === "LINKEDIN") {
      try {
        await connectLinkedIn();
        await refresh();
      } catch (err) {
        setPlatformError(platform, { kind: "generic", message: extractError(err) });
      } finally {
        setBusy(null);
      }
      return;
    }

    setBusy(null);
    setError(`No connect flow registered for ${platform}.`);
  };

  const connectTwitter = async () => {
    const clientId = import.meta.env.VITE_TWITTER_CLIENT_ID as string | undefined;
    if (!clientId) {
      throw new Error(
        "VITE_TWITTER_CLIENT_ID is not configured — set it in the frontend env to connect Twitter.",
      );
    }
    const redirectUri = `${window.location.origin}/oauth/callback`;
    const { verifier, challenge } = await makePkcePair();
    const state = randomString(24);
    const scope = "tweet.read tweet.write users.read media.write offline.access";
    const url =
      "https://twitter.com/i/oauth2/authorize" +
      `?response_type=code&client_id=${encodeURIComponent(clientId)}` +
      `&redirect_uri=${encodeURIComponent(redirectUri)}` +
      `&scope=${encodeURIComponent(scope)}` +
      `&state=${encodeURIComponent(state)}` +
      `&code_challenge=${encodeURIComponent(challenge)}` +
      `&code_challenge_method=S256`;
    const { code } = await openOAuthPopup(url, state);
    await exchangeTwitterCode(code, redirectUri, verifier);
  };

  // Server-side code-exchange flow (no JS SDK). Mirrors Twitter / TikTok but
  // without PKCE — Facebook's web flow authenticates with the app secret on
  // the backend instead. The resulting code is exchanged by the API for a
  // long-lived Page access token, which is what FacebookUploadService needs.
  const connectFacebook = async () => {
    const appId = import.meta.env.VITE_FACEBOOK_APP_ID as string | undefined;
    if (!appId) {
      throw new Error(
        "VITE_FACEBOOK_APP_ID is not configured — set it in the frontend env to connect Facebook.",
      );
    }
    const apiVersion = (import.meta.env.VITE_FACEBOOK_API_VERSION as string | undefined) ?? "v25.0";
    const redirectUri = `${window.location.origin}/oauth/callback`;
    const state = randomString(24);
    // `publish_video` was deprecated by Meta and rejected as an invalid scope
    // by modern Graph API versions; posting to /{page}/videos is now governed
    // by pages_manage_posts (with the Page access token granted via
    // pages_show_list + pages_read_engagement). Adding publish_video here
    // causes Facebook to fail the whole login dialog with "Invalid Scopes".
    const scope = [
      "email",
      "public_profile",
      "pages_show_list",
      "pages_read_engagement",
      "pages_manage_posts",
    ].join(",");
    const url =
      `https://www.facebook.com/${apiVersion}/dialog/oauth` +
      `?client_id=${encodeURIComponent(appId)}` +
      `&redirect_uri=${encodeURIComponent(redirectUri)}` +
      `&response_type=code` +
      `&scope=${encodeURIComponent(scope)}` +
      `&state=${encodeURIComponent(state)}`;
    const { code } = await openOAuthPopup(url, state);
    await exchangeFacebookCode(code, redirectUri);
  };

  // Instagram Business publishing rides on the Facebook Graph API — the
  // authorize dialog is the same one Facebook uses but with IG-specific
  // scopes (instagram_basic + instagram_content_publish on top of the
  // Page-management scopes). Reuses VITE_FACEBOOK_APP_ID, so no separate
  // frontend env var is needed.
  const connectInstagram = async () => {
    const appId = import.meta.env.VITE_FACEBOOK_APP_ID as string | undefined;
    if (!appId) {
      throw new Error(
        "VITE_FACEBOOK_APP_ID is not configured — Instagram publishing uses the Facebook App credentials.",
      );
    }
    const apiVersion = (import.meta.env.VITE_FACEBOOK_API_VERSION as string | undefined) ?? "v25.0";
    const redirectUri = `${window.location.origin}/oauth/callback`;
    const state = randomString(24);
    const scope = [
      "email",
      "public_profile",
      "pages_show_list",
      "pages_read_engagement",
      "business_management",
      "instagram_basic",
      "instagram_content_publish",
    ].join(",");
    const url =
      `https://www.facebook.com/${apiVersion}/dialog/oauth` +
      `?client_id=${encodeURIComponent(appId)}` +
      `&redirect_uri=${encodeURIComponent(redirectUri)}` +
      `&response_type=code` +
      `&scope=${encodeURIComponent(scope)}` +
      `&state=${encodeURIComponent(state)}`;
    const { code } = await openOAuthPopup(url, state);
    await exchangeInstagramCode(code, redirectUri);
  };

  // LinkedIn's authorize endpoint uses the v2 OAuth 2.0 code flow with
  // server-side client_secret authentication — no PKCE. The backend handles
  // the userinfo lookup so we get the member's display name and URN
  // automatically.
  const connectLinkedIn = async () => {
    const clientId = import.meta.env.VITE_LINKEDIN_CLIENT_ID as string | undefined;
    if (!clientId) {
      throw new Error(
        "VITE_LINKEDIN_CLIENT_ID is not configured — set it in the frontend env to connect LinkedIn.",
      );
    }
    const redirectUri = `${window.location.origin}/oauth/callback`;
    const state = randomString(24);
    const scope = "openid profile email w_member_social";
    const url =
      "https://www.linkedin.com/oauth/v2/authorization" +
      `?response_type=code&client_id=${encodeURIComponent(clientId)}` +
      `&redirect_uri=${encodeURIComponent(redirectUri)}` +
      `&state=${encodeURIComponent(state)}` +
      `&scope=${encodeURIComponent(scope)}`;
    const { code } = await openOAuthPopup(url, state);
    await exchangeLinkedInCode(code, redirectUri);
  };

  const connectTikTok = async () => {
    const clientKey = import.meta.env.VITE_TIKTOK_CLIENT_KEY as string | undefined;
    if (!clientKey) {
      throw new Error(
        "unable to connect to tiktok at the moment, this feature is still in development mode.",
      );
    }
    const redirectUri = `${window.location.origin}/oauth/callback`;
    const { verifier, challenge } = await makePkcePair();
    const state = randomString(24);
    const scope = "user.info.basic,video.upload,video.publish";
    const url =
      "https://www.tiktok.com/v2/auth/authorize/" +
      `?client_key=${encodeURIComponent(clientKey)}` +
      `&scope=${encodeURIComponent(scope)}` +
      `&response_type=code` +
      `&redirect_uri=${encodeURIComponent(redirectUri)}` +
      `&state=${encodeURIComponent(state)}` +
      `&code_challenge=${encodeURIComponent(challenge)}` +
      `&code_challenge_method=S256`;
    const { code } = await openOAuthPopup(url, state);
    await exchangeTikTokCode(code, redirectUri, verifier);
  };

  const handleDisconnect = async (platform: SocialPlatform) => {
    setBusy(platform);
    try {
      await disconnect(platform);
      await refresh();
    } catch (err) {
      setError(extractError(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>Social connections</h2>
      <p style={{ color: "#aaa", marginBottom: 24 }}>
        Connect accounts so generated videos can be published automatically.
      </p>

      {error && <div style={{ color: "#ff6b6b", marginBottom: 16 }}>{error}</div>}

      {loading ? (
        <div style={{ color: "#aaa" }}>Loading connections...</div>
      ) : (
        <div className="cards-grid">
          {PLATFORMS.map((p) => {
            const existing = connections.find((c) => c.platform === p.platform);
            const isBusy = busy === p.platform;
            const platformErr = platformErrors[p.platform];
            return (
              <div key={p.platform} style={card}>
                <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 8 }}>
                  <div
                    style={{
                      width: 10,
                      height: 10,
                      borderRadius: "50%",
                      background: p.color,
                    }}
                  />
                  <strong>{p.label}</strong>
                  {existing && (
                    <span
                      style={{
                        marginLeft: "auto",
                        fontSize: 11,
                        color: "#4ade80",
                        textTransform: "uppercase",
                        letterSpacing: 0.5,
                      }}
                    >
                      Connected
                    </span>
                  )}
                </div>
                <p style={{ color: "#aaa", fontSize: 13, minHeight: 40 }}>{p.description}</p>
                {existing?.accountHandle && (
                  <div style={{ fontSize: 12, color: "#888", marginBottom: 12 }}>
                    {existing.accountHandle}
                  </div>
                )}

                {platformErr?.kind === "noFacebookPage" && !existing && (
                  <NoFacebookPageCard
                    message={platformErr.message}
                    busy={isBusy}
                    onRetry={() => handleConnect(p.platform)}
                  />
                )}
                {platformErr?.kind === "generic" && !existing && (
                  <div
                    style={{
                      fontSize: 12,
                      color: "#ff6b6b",
                      marginBottom: 12,
                      lineHeight: 1.4,
                    }}
                  >
                    {platformErr.message}
                  </div>
                )}

                {existing ? (
                  <button
                    disabled={isBusy}
                    onClick={() => handleDisconnect(p.platform)}
                    style={btnSecondary}
                  >
                    {isBusy ? "..." : "Disconnect"}
                  </button>
                ) : platformErr?.kind === "noFacebookPage" ? null : (
                  <button
                    disabled={isBusy}
                    onClick={() => handleConnect(p.platform)}
                    style={{ ...btnPrimary, background: p.color }}
                  >
                    {isBusy ? "Connecting..." : `Connect ${p.label}`}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/**
 * Empty-state card for the {@code NO_FACEBOOK_PAGE} failure mode. Tells the
 * user *why* the connect attempt didn't take and gives them the two actions
 * that actually fix it: create a Page, or grant their account full Facebook
 * access to an existing one. Kept inside the platform tile so it replaces
 * the connect button in-place rather than dumping a banner at the top.
 */
function NoFacebookPageCard({
  message,
  busy,
  onRetry,
}: {
  message: string;
  busy: boolean;
  onRetry: () => void;
}) {
  return (
    <div
      style={{
        background: "#0f1115",
        border: "1px solid #2a2d33",
        borderRadius: 6,
        padding: 12,
        marginBottom: 12,
        fontSize: 13,
        color: "#cbd5e1",
        lineHeight: 1.5,
      }}
    >
      <div style={{ fontWeight: 600, color: "#fff", marginBottom: 6 }}>
        No Facebook Page on this account
      </div>
      <div style={{ marginBottom: 12, color: "#aaa" }}>{message}</div>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <a
          href="https://www.facebook.com/pages/create/"
          target="_blank"
          rel="noreferrer"
          style={{
            background: "#1877f2",
            color: "#fff",
            textDecoration: "none",
            borderRadius: 6,
            padding: "8px 14px",
            fontSize: 13,
            fontWeight: 600,
            display: "inline-block",
          }}
        >
          Create a Page ↗
        </a>
        <button
          disabled={busy}
          onClick={onRetry}
          style={{
            background: "transparent",
            color: "#e6e6e6",
            border: "1px solid #2a2d33",
            borderRadius: 6,
            padding: "8px 14px",
            cursor: busy ? "wait" : "pointer",
            fontSize: 13,
          }}
        >
          {busy ? "Retrying..." : "Try connecting again"}
        </button>
      </div>
      <details style={{ marginTop: 10, fontSize: 12, color: "#888" }}>
        <summary style={{ cursor: "pointer" }}>Already have a Page?</summary>
        <div style={{ marginTop: 6, lineHeight: 1.5 }}>
          Open your Page → <em>Settings → Page access → People with Facebook access</em> →
          add your personal account and toggle on <strong>Allow this person to have full
          control</strong>. Pages assigned only via Business Manager don't appear here.
        </div>
      </details>
    </div>
  );
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

/**
 * Reads the optional {@code errorCode} discriminator the backend sets on
 * specific failure modes (currently only {@code NO_FACEBOOK_PAGE}). Lets us
 * render different UI per known failure without parsing the human message.
 */
function extractErrorCode(err: unknown): string | null {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { errorCode?: string } } }).response;
    if (r?.data?.errorCode) return r.data.errorCode;
  }
  return null;
}

const card: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #1f2125",
  borderRadius: 8,
  padding: 16,
};

const btnPrimary: React.CSSProperties = {
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "8px 14px",
  cursor: "pointer",
  fontWeight: 600,
  width: "100%",
};

const btnSecondary: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "8px 14px",
  cursor: "pointer",
  width: "100%",
};