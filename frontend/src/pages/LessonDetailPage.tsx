import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getLesson, lessonStreamUrl } from "../api/tutor";
import { ProgressBar } from "../components/ProgressBar";
import type { LessonDTO } from "../types/api";

/** Coarse percent for the bar — lessons don't report granular progress. */
const PERCENT: Record<string, number> = {
  QUEUED: 10,
  PROCESSING: 60,
  COMPLETED: 100,
  FAILED: 0,
};

export function LessonDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [lesson, setLesson] = useState<LessonDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;

    const load = () =>
      getLesson(id)
        .then((l) => {
          if (!cancelled) setLesson(l);
          return l;
        })
        .catch((err) => {
          if (!cancelled) setError(extractError(err));
          return null;
        });

    void load();
    const timer = setInterval(async () => {
      const l = await load();
      if (l && (l.status === "COMPLETED" || l.status === "FAILED")) clearInterval(timer);
    }, 5000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [id]);

  return (
    <div style={{ maxWidth: 820 }}>
      <Link to="/tutor" style={{ color: "#7c93ff", textDecoration: "none", fontSize: 14 }}>
        ← Back to AI Tutor
      </Link>

      {error && <div style={{ ...card, borderColor: "#5b2330", color: "#f1a5b0", marginTop: 16 }}>{error}</div>}

      {!lesson ? (
        <div style={{ ...card, color: "#aaa", marginTop: 16 }}>Loading…</div>
      ) : (
        <>
          <h2 style={{ marginBottom: 4, marginTop: 16 }}>{lesson.topic}</h2>
          {lesson.style && <p style={{ color: "#888", marginTop: 0 }}>Style: {lesson.style}</p>}

          {lesson.status !== "COMPLETED" && (
            <div style={{ ...card, marginTop: 12 }}>
              <ProgressBar progress={PERCENT[lesson.status] ?? 0} status={lesson.status} />
              <p style={{ color: "#888", marginTop: 10, marginBottom: 0 }}>
                {lesson.status === "FAILED"
                  ? lesson.errorMessage ?? "Lesson generation failed."
                  : "Writing the lesson script and rendering your twin — this takes a few minutes. This page updates automatically."}
              </p>
            </div>
          )}

          {lesson.status === "COMPLETED" && lesson.hasVideo && (
            <video
              key={lesson.id}
              src={lessonStreamUrl(lesson.id)}
              controls
              playsInline
              style={{ width: "100%", borderRadius: 10, marginTop: 12, background: "#000" }}
            />
          )}

          {lesson.scriptContent && (
            <div style={{ ...card, marginTop: 16 }}>
              <h3 style={{ marginTop: 0 }}>Lesson script</h3>
              <p style={{ color: "#ccc", whiteSpace: "pre-wrap", lineHeight: 1.6, margin: 0 }}>{lesson.scriptContent}</p>
            </div>
          )}
        </>
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
