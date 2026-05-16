import { GoogleLogin } from "@react-oauth/google";
import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { fbLogin, fbProfile, loadFacebookSdk } from "../api/facebook";

interface GoogleJwtPayload {
  /** Google's stable user identifier — never changes for the same Google account. */
  sub: string;
  email: string;
  name: string;
  picture?: string;
}

function decodeJwt(token: string): GoogleJwtPayload {
  const payload = token.split(".")[1];
  const decoded = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
  return JSON.parse(decoded);
}

export function LoginPage() {
  const { user, signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [error, setError] = useState<string | null>(null);
  const [fbReady, setFbReady] = useState(false);

  const fbAppId = import.meta.env.VITE_FACEBOOK_APP_ID;
  const googleClientId = import.meta.env.VITE_GOOGLE_CLIENT_ID;
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname ?? "/";

  useEffect(() => {
    if (user) navigate(from, { replace: true });
  }, [user, from, navigate]);

  useEffect(() => {
    if (!fbAppId) return;
    loadFacebookSdk(fbAppId).then(() => setFbReady(true));
  }, [fbAppId]);

  const handleFacebookLogin = async () => {
    setError(null);
    try {
      const login = await fbLogin("public_profile,email");
      if (login.status !== "connected" || !login.authResponse) {
        setError("Facebook login was cancelled.");
        return;
      }
      const profile = await fbProfile();
      if (!profile.email) {
        setError("Facebook did not return an email. Make sure email permission is granted.");
        return;
      }
      signIn({
        email: profile.email,
        name: profile.name,
        picture: profile.picture?.data.url,
        provider: "facebook",
        providerUserId: profile.id,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Facebook login failed.");
    }
  };

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: "#0e0f12",
        color: "#e6e6e6",
      }}
    >
      <div style={{ textAlign: "center", maxWidth: 380, width: "100%", padding: 24 }}>
        <img
          src="/icon.svg"
          alt=""
          width={88}
          height={88}
          style={{ marginBottom: 16, borderRadius: 20 }}
        />
        <h1 style={{ marginBottom: 8, marginTop: 0 }}>Faceless Chronicle AI</h1>
        <p style={{ color: "#aaa", marginBottom: 28 }}>Sign in to start generating videos.</p>

        <div style={{ display: "flex", flexDirection: "column", gap: 12, alignItems: "center" }}>
          {googleClientId ? (
            <GoogleLogin
              onSuccess={(credentialResponse) => {
                if (!credentialResponse.credential) {
                  setError("No credential returned from Google.");
                  return;
                }
                const payload = decodeJwt(credentialResponse.credential);
                signIn({
                  email: payload.email,
                  name: payload.name,
                  picture: payload.picture,
                  provider: "google",
                  providerUserId: payload.sub,
                });
              }}
              onError={() => setError("Google login failed.")}
              theme="filled_black"
            />
          ) : (
            <DisabledButton label="Set VITE_GOOGLE_CLIENT_ID to enable Google" />
          )}

          {fbAppId ? (
            <button
              onClick={handleFacebookLogin}
              disabled={!fbReady}
              style={{
                background: "#1877f2",
                color: "#fff",
                border: "none",
                borderRadius: 6,
                padding: "10px 16px",
                width: 240,
                cursor: fbReady ? "pointer" : "wait",
                fontWeight: 600,
              }}
            >
              {fbReady ? "Continue with Facebook" : "Loading Facebook..."}
            </button>
          ) : (
            <DisabledButton label="Set VITE_FACEBOOK_APP_ID to enable Facebook" />
          )}
        </div>

        {error && <p style={{ color: "#ff6b6b", marginTop: 16 }}>{error}</p>}

        <div style={{ marginTop: 32, fontSize: 12, color: "#888", display: "flex", gap: 8, justifyContent: "center", flexWrap: "wrap" }}>
          <Link to="/tos" style={{ color: "#888" }}>Terms of Service</Link>
          <span style={{ color: "#444" }}>•</span>
          <Link to="/privacy-policy" style={{ color: "#888" }}>Privacy Policy</Link>
          <span style={{ color: "#444" }}>•</span>
          <Link to="/delete-data" style={{ color: "#888" }}>Data Deletion</Link>
        </div>
      </div>
    </div>
  );
}

function DisabledButton({ label }: { label: string }) {
  return (
    <div
      style={{
        background: "#1a1c20",
        color: "#888",
        borderRadius: 6,
        padding: "10px 16px",
        width: 240,
        fontSize: 13,
        border: "1px dashed #2a2d33",
      }}
    >
      {label}
    </div>
  );
}