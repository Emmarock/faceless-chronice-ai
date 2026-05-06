# Faceless Chronicle Frontend

Vite + React + TypeScript frontend for the Faceless Chronicle AI backend.

## Setup

```bash
cd frontend
npm install
cp .env.example .env
# Fill in VITE_GOOGLE_CLIENT_ID and VITE_FACEBOOK_APP_ID once you have them.
npm run dev
```

Dev server runs at `http://localhost:5173`. The Spring backend (port 8080) must
be running and reachable; CORS is preconfigured to accept the Vite dev origin.

## Environment variables

| Variable | Purpose |
|---|---|
| `VITE_API_BASE_URL` | Backend base URL. Defaults to `http://localhost:8080`. |
| `VITE_GOOGLE_CLIENT_ID` | Google OAuth client ID — enables Sign-in with Google and YouTube connection. |
| `VITE_FACEBOOK_APP_ID` | Facebook App ID — enables Sign-in with Facebook and Facebook connection. |

The Login screen renders disabled placeholders for any provider whose env var
is missing, so the app boots without OAuth credentials.

## What it talks to

- `POST /api/job-file/generate` — create a new job (existing endpoint)
- `POST /api/job-file/{jobId}/resume` — re-trigger pipeline (existing endpoint)
- `GET/POST/DELETE /api/social-connections` — store OAuth tokens per user
  (added alongside this frontend)

`X-USER` is set automatically from the signed-in user's email.

## Notes

- Job history is kept in `localStorage` (no backend list endpoint exists yet).
- TikTok and Twitter "Connect" buttons store a placeholder token only — those
  platforms have no backend integration yet.
- YouTube and Facebook tokens are real OAuth tokens but are only stored; no
  upload/posting flow is wired through the backend yet.