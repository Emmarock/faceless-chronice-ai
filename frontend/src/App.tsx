import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { GoogleOAuthProvider } from "@react-oauth/google";
import { AuthProvider } from "./context/AuthContext";
import { BillingProvider } from "./context/BillingContext";
import { Layout } from "./components/Layout";
import { RequireAuth } from "./components/RequireAuth";
import { RequirePlanSelected } from "./components/RequirePlanSelected";
import { LoginPage } from "./pages/LoginPage";
import { JobsListPage } from "./pages/JobsListPage";
import { CreateJobPage } from "./pages/CreateJobPage";
import { JobDetailPage } from "./pages/JobDetailPage";
import { VideosListPage } from "./pages/VideosListPage";
import { AssetsPage } from "./pages/AssetsPage";
import { ConnectionsPage } from "./pages/ConnectionsPage";
import { OAuthCallbackPage } from "./pages/OAuthCallbackPage";
import { PrivacyPolicyPage } from "./pages/PrivacyPolicyPage";
import { TermsOfServicePage } from "./pages/TermsOfServicePage";
import { DataDeletionPage } from "./pages/DataDeletionPage";
import { PricingPage } from "./pages/PricingPage";
import { BillingPage } from "./pages/BillingPage";

export function App() {
  const googleClientId = import.meta.env.VITE_GOOGLE_CLIENT_ID ?? "";

  return (
    <GoogleOAuthProvider clientId={googleClientId}>
      <AuthProvider>
        <BillingProvider>
          <BrowserRouter>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/oauth/callback" element={<OAuthCallbackPage />} />
              <Route path="/privacy-policy" element={<PrivacyPolicyPage />} />
              <Route path="/tos" element={<TermsOfServicePage />} />
              <Route path="/delete-data" element={<DataDeletionPage />} />
              <Route
                element={
                  <RequireAuth>
                    <Layout />
                  </RequireAuth>
                }
              >
                {/* Always accessible inside the layout — these are where a
                    newly-signed-up user can interact while gated. */}
                <Route path="pricing" element={<PricingPage />} />
                <Route path="billing" element={<BillingPage />} />

                {/* Gated routes: redirect to /pricing until the user has
                    picked a plan (paid or "Continue with Free"). */}
                <Route element={<RequirePlanSelected />}>
                  <Route index element={<JobsListPage />} />
                  <Route path="jobs/new" element={<CreateJobPage />} />
                  <Route path="jobs/:jobId" element={<JobDetailPage />} />
                  <Route path="videos" element={<VideosListPage />} />
                  <Route path="assets" element={<AssetsPage />} />
                  <Route path="connections" element={<ConnectionsPage />} />
                </Route>
              </Route>
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </BrowserRouter>
        </BillingProvider>
      </AuthProvider>
    </GoogleOAuthProvider>
  );
}