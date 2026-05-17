import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { getMyBilling, type BillingMeDTO } from "../api/billing";
import { useAuth } from "./AuthContext";

interface BillingContextValue {
  billing: BillingMeDTO | null;
  loading: boolean;
  /** Force a refetch — call after a checkout return or a 402 error. */
  refresh: () => Promise<void>;
}

const BillingContext = createContext<BillingContextValue | undefined>(undefined);

/**
 * Wraps the app so any component can render the user's current plan + credit
 * balance without re-fetching. Re-fetches automatically when the auth user
 * changes (sign-in / sign-out).
 */
export function BillingProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [billing, setBilling] = useState<BillingMeDTO | null>(null);
  const [loading, setLoading] = useState(false);
  // Guard against late responses after sign-out updating state.
  const requestIdRef = useRef(0);

  const refresh = useCallback(async () => {
    if (!user) {
      setBilling(null);
      return;
    }
    const myId = ++requestIdRef.current;
    setLoading(true);
    try {
      const data = await getMyBilling();
      if (myId === requestIdRef.current) setBilling(data);
    } catch {
      // Swallow — UI can render without billing info; we'll retry on next refresh().
    } finally {
      if (myId === requestIdRef.current) setLoading(false);
    }
  }, [user]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const value = useMemo(() => ({ billing, loading, refresh }), [billing, loading, refresh]);
  return <BillingContext.Provider value={value}>{children}</BillingContext.Provider>;
}

export function useBilling(): BillingContextValue {
  const ctx = useContext(BillingContext);
  if (!ctx) throw new Error("useBilling must be used within BillingProvider");
  return ctx;
}