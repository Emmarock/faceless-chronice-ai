import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { generateJob } from "../api/jobs";
import type { VideoFormat } from "../types/api";

const STYLE_OPTIONS = [
  "documentary",
  "storytelling",
  "educational",
  "motivational",
  "horror",
  "comedy",
];

// Per-format duration constraints. REELS caps at 30s to match Shorts /
// TikTok / Reels norms; long-form keeps the legacy 15–600s window.
const REELS_MAX_SECONDS = 30;
const REELS_DEFAULT_SECONDS = 20;
const VIDEO_MIN_SECONDS = 15;
const VIDEO_MAX_SECONDS = 600;
const VIDEO_DEFAULT_SECONDS = 60;

export function CreateJobPage() {
  const navigate = useNavigate();
  const [question, setQuestion] = useState("");
  const [style, setStyle] = useState(STYLE_OPTIONS[0]);
  const [videoFormat, setVideoFormat] = useState<VideoFormat>("VIDEO");
  const [durationSeconds, setDurationSeconds] = useState<number>(VIDEO_DEFAULT_SECONDS);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isReels = videoFormat === "REELS";
  const durationMin = isReels ? 5 : VIDEO_MIN_SECONDS;
  const durationMax = isReels ? REELS_MAX_SECONDS : VIDEO_MAX_SECONDS;

  const handleFormatChange = (next: VideoFormat) => {
    if (next === videoFormat) return;
    setVideoFormat(next);
    // Snap duration into the new format's window so the input doesn't show a
    // value the backend would reject.
    setDurationSeconds(next === "REELS" ? REELS_DEFAULT_SECONDS : VIDEO_DEFAULT_SECONDS);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!question.trim()) return;
    const clamped = Math.min(durationMax, Math.max(durationMin, durationSeconds));
    setSubmitting(true);
    setError(null);
    try {
      const result = await generateJob({
        question: question.trim(),
        style,
        durationSeconds: clamped,
        videoFormat,
      });
      navigate(`/jobs/${result.jobId}`, { state: { jobFile: result } });
    } catch (err) {
      setError(extractError(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ maxWidth: 640, width: "100%" }}>
      <h2>Create a new job</h2>
      <p style={{ color: "#aaa", marginBottom: 24 }}>
        Generate a video script and kick off the production pipeline.
      </p>

      <form onSubmit={handleSubmit} style={{ display: "grid", gap: 16 }}>
        <Field label="Question / Topic">
          <textarea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="e.g. What are the strangest unsolved mysteries of the deep ocean?"
            rows={4}
            style={input}
            required
          />
        </Field>

        <Field label="Style">
          <select value={style} onChange={(e) => setStyle(e.target.value)} style={input}>
            {STYLE_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Format">
          <div style={{ display: "flex", gap: 8 }} role="group" aria-label="Video format">
            <FormatButton
              active={videoFormat === "VIDEO"}
              onClick={() => handleFormatChange("VIDEO")}
              title="Multi-scene, multi-minute long-form video"
              label="Video"
              sub="long-form, multi-scene"
            />
            <FormatButton
              active={videoFormat === "REELS"}
              onClick={() => handleFormatChange("REELS")}
              title="Single-scene short video, ≤30s — for Shorts / Reels / TikTok"
              label="Reels"
              sub="≤30s, 1 scene"
            />
          </div>
        </Field>

        <Field label={`Duration (seconds) — max ${durationMax}`}>
          <input
            type="number"
            min={durationMin}
            max={durationMax}
            value={durationSeconds}
            onChange={(e) => setDurationSeconds(Number(e.target.value))}
            style={input}
          />
        </Field>

        {error && <div style={{ color: "#ff6b6b" }}>{error}</div>}

        <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          <button type="submit" disabled={submitting} style={btnPrimary}>
            {submitting ? "Generating..." : "Generate job"}
          </button>
          <button
            type="button"
            onClick={() => navigate("/")}
            disabled={submitting}
            style={btnSecondary}
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: "grid", gap: 6 }}>
      <span style={{ fontSize: 13, color: "#aaa" }}>{label}</span>
      {children}
    </label>
  );
}

function FormatButton({
  active,
  onClick,
  label,
  sub,
  title,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
  sub: string;
  title: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      style={{
        flex: 1,
        background: active ? "#1d4ed8" : "transparent",
        color: active ? "#fff" : "#cbd5e1",
        border: "1px solid " + (active ? "#3b82f6" : "#2a2d33"),
        borderRadius: 6,
        padding: "10px 12px",
        cursor: active ? "default" : "pointer",
        fontWeight: 600,
        display: "flex",
        flexDirection: "column",
        alignItems: "flex-start",
        gap: 2,
      }}
    >
      <span style={{ fontSize: 14 }}>{label}</span>
      <span style={{ fontSize: 11, color: active ? "#d6e3ff" : "#7a8db3", fontWeight: 400 }}>
        {sub}
      </span>
    </button>
  );
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

const input: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "10px 12px",
  color: "#e6e6e6",
  fontFamily: "inherit",
  fontSize: 16,
  width: "100%",
};

const btnPrimary: React.CSSProperties = {
  background: "#3b82f6",
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
  fontWeight: 600,
};

const btnSecondary: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
};