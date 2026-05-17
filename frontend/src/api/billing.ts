import { apiClient } from "./client";

export type PlanCode = "FREE" | "CREATOR" | "PRO" | "UNLIMITED" | "ENTERPRISE";
export type SubscriptionStatus = "ACTIVE" | "INCOMPLETE" | "PAST_DUE" | "CANCELED";

export interface PlanDTO {
  code: PlanCode;
  displayName: string;
  tagline: string | null;
  monthlyPriceCents: number | null;
  monthlyCreditGrant: number | null;
  highlighted: boolean;
  /** True when this plan can be checked out via Stripe (FREE / ENTERPRISE excluded). */
  selfServe: boolean;
}

export interface BillingMeDTO {
  planCode: PlanCode;
  planDisplayName: string;
  status: SubscriptionStatus;
  creditBalance: number;
  monthlyCreditGrant: number | null;
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  cancelAtPeriodEnd: boolean;
  hasStripeCustomer: boolean;
  /**
   * Server-side feature flag. When false, the deployment is running
   * without a Stripe account — paid plans are activated immediately via
   * {@link activatePlan} instead of going through Stripe Checkout.
   */
  paymentsRequired: boolean;
}

interface RedirectUrlDTO {
  url: string;
}

export async function listPlans(): Promise<PlanDTO[]> {
  const { data } = await apiClient.get<PlanDTO[]>("/api/billing/plans");
  return data;
}

export async function getMyBilling(): Promise<BillingMeDTO> {
  const { data } = await apiClient.get<BillingMeDTO>("/api/billing/me");
  return data;
}

/**
 * Starts a Stripe Checkout session for the chosen plan and returns the URL
 * the caller should redirect to. The browser must navigate (not just fetch)
 * so Stripe's hosted page renders correctly.
 *
 * <p>Only callable when {@code BillingMeDTO.paymentsRequired === true}.
 * Returns 409 otherwise — use {@link activatePlan} on no-payments deploys.
 */
export async function startCheckout(plan: PlanCode): Promise<string> {
  const { data } = await apiClient.post<RedirectUrlDTO>("/api/billing/checkout", { plan });
  return data.url;
}

/**
 * Activates a paid plan without payment. Only callable when
 * {@code BillingMeDTO.paymentsRequired === false} — i.e. on deployments
 * that haven't enabled Stripe yet. Grants the plan's monthly credit
 * allowance immediately and returns the refreshed billing snapshot.
 */
export async function activatePlan(plan: PlanCode): Promise<BillingMeDTO> {
  const { data } = await apiClient.post<BillingMeDTO>("/api/billing/activate-plan", { plan });
  return data;
}

/** Opens the Stripe Customer Portal for managing the current subscription. */
export async function openPortal(): Promise<string> {
  const { data } = await apiClient.post<RedirectUrlDTO>("/api/billing/portal");
  return data.url;
}

export async function getEnterpriseContactEmail(): Promise<string> {
  const { data } = await apiClient.get<{ email: string }>("/api/billing/enterprise-contact");
  return data.email;
}