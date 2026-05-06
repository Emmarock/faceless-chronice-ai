import { useEffect } from "react";

/**
 * Mounted at /oauth/callback. The OAuth provider redirects here inside the
 * popup we opened from the Connections page. We parse the query string,
 * post the result back to the opener, and close ourselves.
 */
export function OAuthCallbackPage() {
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const state = params.get("state");
    const error = params.get("error_description") ?? params.get("error");

    if (window.opener) {
      window.opener.postMessage(
        { type: "fc:oauth", code, state, error },
        window.location.origin,
      );
    }

    // Give the message a tick to flush before the window closes.
    const timer = window.setTimeout(() => {
      try {
        window.close();
      } catch {
        /* ignore */
      }
    }, 50);
    return () => window.clearTimeout(timer);
  }, []);

  return (
    <div style={{ padding: 32, fontFamily: "sans-serif", color: "#aaa" }}>
      Finishing sign-in… you can close this window.
    </div>
  );
}