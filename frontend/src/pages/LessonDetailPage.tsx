import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getLesson, lessonStreamUrl } from "../api/tutor";
import { listConnections } from "../api/social";
import { publishVideo } from "../api/videos";
import { ProgressBar } from "../components/ProgressBar";
import { PublishModal } from "../components/PublishModal";
import { card as uiCard, buttonStyle } from "../components/ui";
import type { LessonDTO, SocialConnectionDTO, VideoPublishRequest } from "../types/api";

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
  const [connections, setConnections] = useState<SocialConnectionDTO[]>([]);
  const [publishOpen, setPublishOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listConnections().then(setConnections).catch(() => undefined);
  }, []);

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
            <>
              <video
                key={lesson.id}
                src={lessonStreamUrl(lesson.id)}
                controls
                playsInline
                style={{ width: "100%", borderRadius: 10, marginTop: 12, background: "#000" }}
              />
              <div style={{ marginTop: 12 }}>
                <button
                  style={uploadBtn}
                  disabled={!lesson.videoId}
                  title={lesson.videoId ? "Upload to connected social accounts" : "Preparing for upload…"}
                  onClick={() => setPublishOpen(true)}
                >
                  ⬆ Upload to social
                </button>
              </div>

              {lesson.videoId && (
                <PublishModal
                  open={publishOpen}
                  title={lesson.topic}
                  defaultTitle={lesson.topic}
                  defaultDescription={lesson.scriptContent ?? ""}
                  connections={connections}
                  videoFormat={null}
                  onClose={() => setPublishOpen(false)}
                  onSubmit={async (request: VideoPublishRequest) => {
                    const response = await publishVideo(lesson.videoId as string, request);
                    return response.results;
                  }}
                />
              )}
            </>
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

const card = uiCard;

const uploadBtn: React.CSSProperties = buttonStyle("primary");
