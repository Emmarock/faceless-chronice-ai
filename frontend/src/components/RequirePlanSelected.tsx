import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useBilling } from "../context/BillingContext";

/**
 * Gates protected app routes on the user having actively picked a plan.
 *
 * <p>Brand-new users sign in, an {@code AppUser} + {@code Subscription} row
 * are bootstrapped with {@code planSelected=false}, and this component
 * redirects every non-billing route to {@code /pricing} until they make a
 * choice (paid plan or "Continue with Free"). Once the flag flips, the
 * gate releases and they see the normal app.
 *
 * <p>Mounted INSIDE {@code RequireAuth} + {@code Layout}, so the user is
 * already authenticated and the chrome is already rendered when this runs.
 * {@code /pricing} and {@code /billing} sit OUTSIDE this wrapper (still
 * inside auth + layout) so the user has somewhere to interact while gated.
 *
 * <h3>Loading semantics</h3>
 * The {@link useBilling} context starts with {@code billing === null} on
 * mount and fetches in the background. We render {@code null} during that
 * window rather than redirecting — otherwise users who are actually
 * onboarded would briefly bounce to /pricing on every page load while the
 * fetch is in flight.
 */
export function RequirePlanSelected() {
  const { billing } = useBilling();
  const location = useLocation();

  // Initial paint — billing hasn't arrived yet. Render nothing so we don't
  // false-positive into a redirect.
  if (billing === null) {
    return null;
  }

  if (!billing.planSelected) {
    return <Navigate to="/pricing" replace state={{ from: location }} />;
  }

  return <Outlet />;
}