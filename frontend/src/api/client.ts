import axios from "axios";

// Empty baseURL = same-origin requests, which the Vite dev server proxies to
// the Spring backend (see vite.config.ts). Override with VITE_API_BASE_URL
// only when you need to point at a backend on a different origin (e.g. a
// production deploy where the frontend is on Vercel and the backend is on
// Railway / Render / etc).
//
// Trailing slashes are stripped so callers can compose paths starting with
// "/api/..." without producing "//api/..." URLs.
export const apiBaseUrl: string = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/+$/, "");

export const apiClient = axios.create({
  baseURL: apiBaseUrl,
});

apiClient.interceptors.request.use((config) => {
  const userId = localStorage.getItem("fc.userId");
  if (userId) {
    config.headers.set("X-USER", userId);
  }
  return config;
});