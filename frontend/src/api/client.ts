import axios from "axios";

// Empty baseURL = same-origin requests, which the Vite dev server proxies to
// the Spring backend (see vite.config.ts). Override with VITE_API_BASE_URL
// only when you need to point at a backend on a different origin (e.g. a
// dedicated staging host).
const baseURL = import.meta.env.VITE_API_BASE_URL ?? "";

export const apiClient = axios.create({
  baseURL,
});

apiClient.interceptors.request.use((config) => {
  const userId = localStorage.getItem("fc.userId");
  if (userId) {
    config.headers.set("X-USER", userId);
  }
  return config;
});