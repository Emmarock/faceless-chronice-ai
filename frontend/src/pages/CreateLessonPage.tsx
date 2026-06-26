import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { createLesson, listTwins } from "../api/tutor";
import type { TwinDTO } from "../types/api";

export function CreateLessonPage() {
  const [twins, setTwins] = useState<TwinDTO[]>([]);
  const [twinId, setTwinId] = useState("");
  const [topic, setTopic] = useState("");
  const [style, setStyle] = useState("");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    listTwins()
      .then((all) => {
        const ready = all.filter((t) => t.ready);
        setTwins(ready);
        if (ready.length > 0) setTwinId(ready[0].id);
      })
      .catch((err) => setError(extractError(err)))
      .finally(() => setLoading(false));
  }, []);

  const submit = async () => {
    if (!twinId || !topic.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      const lesson = await createLesson({ twinId, topic: topic.trim(), style: style.trim() || undefined });
      navigate(`/tutor/lessons/${lesson.id}`);
    } catch (err) {
      setError(extractError(err));
      setSubmitting(false);
    }
  };

  return (
    <div style={{ maxWidth: 640 }}>
      <h2 style={{ marginBottom: 4 }}>New lesson</h2>
      <p style={{ color: "#888", marginTop: 0 }}>
        Pick a twin and a topic. We&apos;ll write the lesson script and render your twin
        teaching it — this runs in the background and takes a few minutes.
      </p>

      {error && <div style={{ ...card, borderColor: "#5b2330", color: "#f1a5b0", marginBottom: 16 }}>{error}</div>}

      {loading ? (
        <div style={{ ...card, color: "#aaa" }}>Loading twins…</div>
      ) : twins.length === 0 ? (
        <div style={{ ...card, color: "#aaa" }}>
          You don&apos;t have a ready twin yet. Train one on the AI Tutor page first.
        </div>
      ) : (
        <div style={card}>
          <label style={label}>Twin</label>
          <select style={input} value={twinId} onChange={(e) => setTwinId(e.target.value)}>
            {twins.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>

          <label style={{ ...label, marginTop: 16 }}>Topic</label>
          <textarea
            style={{ ...input, minHeight: 80, resize: "vertical" }}
            placeholder="e.g. How compound interest works"
            value={topic}
            onChange={(e) => setTopic(e.target.value)}
          />

          <label style={{ ...label, marginTop: 16 }}>Style (optional)</label>
          <input
            style={input}
            placeholder="e.g. friendly, exam-prep, beginner-friendly"
            value={style}
            onChange={(e) => setStyle(e.target.value)}
          />

          <div style={{ marginTop: 20 }}>
            <button style={btnPrimary} onClick={submit} disabled={submitting || !topic.trim()}>
              {submitting ? "Creating…" : "Generate lesson"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

const card: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #2a2d33",
  borderRadius: 10,
  padding: 16,
};

const label: React.CSSProperties = {
  display: "block",
  color: "#aaa",
  fontSize: 13,
  marginBottom: 6,
};

const input: React.CSSProperties = {
  width: "100%",
  background: "#0f1115",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "10px 12px",
  fontSize: 14,
  boxSizing: "border-box",
};

const btnPrimary: React.CSSProperties = {
  background: "#3b5bdb",
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "10px 16px",
  cursor: "pointer",
  fontSize: 14,
  fontWeight: 600,
};
