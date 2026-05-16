import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { recordSignIn } from "../api/auth";

export interface AuthUser {
  email: string;
  name: string;
  picture?: string;
  provider: "google" | "facebook";
  /**
   * Stable identifier issued by the OAuth provider (Google `sub`, Facebook
   * user id). Required so the backend can dedupe the {@code UserIdentity}
   * row across sessions even when the email changes.
   */
  providerUserId: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  signIn: (user: AuthUser) => void;
  signOut: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

// Bumped to v2 so any user signed in before the backend started recording
// AppUser / UserIdentity rows is forced through the /login flow once. The
// next sign-in carries providerUserId and triggers POST /api/auth/sign-in,
// backfilling the directory. Bump again if AuthUser ever gains another
// required field.
const STORAGE_KEY = "fc.user.v2";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AuthUser) : null;
  });

  useEffect(() => {
    if (user) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(user));
      localStorage.setItem("fc.userId", user.email);
    } else {
      localStorage.removeItem(STORAGE_KEY);
      localStorage.removeItem("fc.userId");
    }
  }, [user]);

  const signIn = useCallback((u: AuthUser) => {
    setUser(u);
    // Tell the backend who just signed in so it can record the AppUser /
    // UserIdentity row. Fire-and-forget: a failure here shouldn't block the
    // UI sign-in, but we surface it in the console so it's debuggable.
    recordSignIn({
      provider: u.provider === "google" ? "GOOGLE" : "FACEBOOK",
      providerUserId: u.providerUserId,
      email: u.email,
      name: u.name,
      picture: u.picture,
    }).catch((err) => {
      console.error("Failed to record sign-in with backend:", err);
    });
  }, []);
  const signOut = useCallback(() => setUser(null), []);

  const value = useMemo(() => ({ user, signIn, signOut }), [user, signIn, signOut]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}