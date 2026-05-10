import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Vite blocks unknown Host headers as a CSRF-style protection. List bare
    // hostnames here (no scheme, no path). A leading "." matches any
    // subdomain of that suffix — handy for ngrok URLs that rotate every restart.
    allowedHosts: [
      "localhost",
      ".ngrok-free.app",
      ".ngrok-free.dev",
      ".ngrok.app",
      ".ngrok.dev",
      ".ngrok.io",
      "https://faceless-chronicle.com",
    ],
    // Forward API + stream traffic to the Spring backend on the same origin
    // the frontend is served from. Mobile devices and ngrok tunnels can't
    // reach the dev machine's localhost directly, so going through Vite's
    // proxy keeps everything same-origin (no CORS, no mixed content).
    proxy: {
      "/api": {
        target: "http://localhost:8090",
        changeOrigin: true,
        // Long video uploads / streams shouldn't time out at the proxy.
        timeout: 0,
      },
    },
  },
});