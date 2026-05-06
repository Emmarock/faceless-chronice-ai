import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { generateJob } from "../api/jobs";

const STYLE_OPTIONS = [
  "documentary",
  "storytelling",
  "educational",
  "motivational",
  "horror",
  "comedy",
];

export function CreateJobPage() {
  const navigate = useNavigate();
  const [question, setQuestion] = useState("");
  const [style, setStyle] = useState(STYLE_OPTIONS[0]);
  const [durationSeconds, setDurationSeconds] = useState<number>(60);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!question.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      const result = await generateJob({
        question: question.trim(),
        style,
        durationSeconds,
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

        <Field label="Duration (seconds)">
          <input
            type="number"
            min={15}
            max={600}
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