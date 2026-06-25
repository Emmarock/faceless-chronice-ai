import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { createTwin, deleteTwin, listLessons, listTwins } from "../api/tutor";
import type { LessonDTO, TwinDTO } from "../types/api";

// Reject oversized clips client-side before uploading. Kept comfortably under
// the backend's 200MB multipart cap — a short avatar-training clip (15–30s) is
// only a few MB, so this guards against accidental large/long recordings.
const MAX_VIDEO_MB = 150;
const MAX_VIDEO_BYTES = MAX_VIDEO_MB * 1024 * 1024;

export function TutorTwinsPage() {
  const [twins, setTwins] = useState<TwinDTO[]>([]);
  const [lessons, setLessons] = useState<LessonDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  const refresh = () =>
    Promise.all([listTwins(), listLessons()])
      .then(([t, l]) => {
        setTwins(t);
        setLessons(l);
      })
      .catch((err) => setError(extractError(err)));

  useEffect(() => {
    setLoading(true);
    refresh().finally(() => setLoading(false));
  }, []);

  // Poll while anything is still training/rendering so the UI advances live.
  const anyPending =
    twins.some((t) => t.status === "QUEUED" || t.status === "PROCESSING") ||
    lessons.some((l) => l.status === "QUEUED" || l.status === "PROCESSING");
  useEffect(() => {
    if (!anyPending) return;
    const id = setInterval(() => {
      void refresh();
    }, 5000);
    return () => clearInterval(id);
  }, [anyPending]);

  const readyTwins = twins.filter((t) => t.ready);

  return (
    <div>
      <div style={{ marginBottom: 24 }}>
        <h2 style={{ margin: 0 }}>AI Tutor</h2>
        <p style={{ color: "#888", marginTop: 6 }}>
          Train a twin from a short clip of yourself, then generate lessons where your twin
          teaches any topic in your own likeness and voice.
        </p>
      </div>

      {error && (
        <div style={{ ...card, borderColor: "#5b2330", color: "#f1a5b0", marginBottom: 16 }}>{error}</div>
      )}

      <TwinOnboarding onCreated={() => void refresh()} onError={setError} />

      <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", marginTop: 32 }}>
        <h3 style={{ margin: 0 }}>Your twins</h3>
        <button
          style={btnPrimary}
          disabled={readyTwins.length === 0}
          title={readyTwins.length === 0 ? "Train a twin first" : undefined}
          onClick={() => navigate("/tutor/lessons/new")}
        >
          + New lesson
        </button>
      </div>

      {loading ? (
        <div style={{ ...card, color: "#aaa", marginTop: 12 }}>Loading...</div>
      ) : twins.length === 0 ? (
        <div style={{ ...card, color: "#aaa", marginTop: 12 }}>
          No twins yet — record or upload a clip above to create your first one.
        </div>
      ) : (
        <div style={{ display: "grid", gap: 12, marginTop: 12 }}>
          {twins.map((t) => (
            <div key={t.id} style={{ ...card, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <div>
                <div style={{ fontWeight: 600 }}>{t.name}</div>
                <div style={{ marginTop: 4 }}>
                  <StatusBadge status={t.status} />
                  {t.errorMessage && <span style={{ color: "#f1a5b0", marginLeft: 8, fontSize: 13 }}>{t.errorMessage}</span>}
                </div>
              </div>
              <button
                style={btnSecondary}
                onClick={() => {
                  if (!confirm(`Delete twin "${t.name}"?`)) return;
                  void deleteTwin(t.id).then(refresh).catch((err) => setError(extractError(err)));
                }}
              >
                Delete
              </button>
            </div>
          ))}
        </div>
      )}

      <h3 style={{ marginTop: 32 }}>Recent lessons</h3>
      {lessons.length === 0 ? (
        <div style={{ ...card, color: "#aaa", marginTop: 12 }}>No lessons yet.</div>
      ) : (
        <div style={{ display: "grid", gap: 12, marginTop: 12 }}>
          {lessons.map((l) => (
            <Link key={l.id} to={`/tutor/lessons/${l.id}`} style={{ textDecoration: "none", color: "inherit" }}>
              <div style={{ ...card, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <div>
                  <div style={{ fontWeight: 600 }}>{l.topic}</div>
                  <div style={{ marginTop: 4 }}>
                    <StatusBadge status={l.status} />
                  </div>
                </div>
                <span style={{ color: "#7c93ff", fontSize: 14 }}>Open →</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Onboarding: record via webcam OR upload a file
// ---------------------------------------------------------------------------

function TwinOnboarding({ onCreated, onError }: { onCreated: () => void; onError: (msg: string) => void }) {
  const [name, setName] = useState("");
  const [blob, setBlob] = useState<Blob | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [recording, setRecording] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);

  useEffect(() => {
    return () => {
      streamRef.current?.getTracks().forEach((t) => t.stop());
      if (previewUrl) URL.revokeObjectURL(previewUrl);
    };
  }, [previewUrl]);

  const setRecorded = (b: Blob) => {
    if (b.size > MAX_VIDEO_BYTES) {
      onError(
        `That clip is ${formatMb(b.size)} — the limit is ${MAX_VIDEO_MB}MB. ` +
          `Record a shorter clip (15–30s is plenty) or upload a smaller file.`,
      );
      return;
    }
    onError(""); // clear any prior size error on a valid selection
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    setBlob(b);
    setPreviewUrl(URL.createObjectURL(b));
  };

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        videoRef.current.muted = true;
        await videoRef.current.play().catch(() => undefined);
      }
      chunksRef.current = [];
      const recorder = new MediaRecorder(stream);
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data);
      };
      recorder.onstop = () => {
        const recorded = new Blob(chunksRef.current, { type: recorder.mimeType || "video/webm" });
        setRecorded(recorded);
        streamRef.current?.getTracks().forEach((t) => t.stop());
        if (videoRef.current) videoRef.current.srcObject = null;
      };
      recorderRef.current = recorder;
      recorder.start();
      setRecording(true);
    } catch (err) {
      onError("Could not access camera/microphone. " + extractError(err));
    }
  };

  const stopRecording = () => {
    recorderRef.current?.stop();
    setRecording(false);
  };

  const onFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) setRecorded(f);
  };

  const submit = async () => {
    if (!blob) return;
    setSubmitting(true);
    try {
      await createTwin(name.trim(), blob);
      setName("");
      setBlob(null);
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      setPreviewUrl(null);
      onCreated();
    } catch (err) {
      onError(extractError(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={card}>
      <h3 style={{ marginTop: 0 }}>Create a twin</h3>
      <p style={{ color: "#888", marginTop: 0 }}>
        Record a 15–60 second clip looking at the camera and speaking naturally, or upload an
        existing one. We&apos;ll train your avatar and clone your voice.
      </p>

      <input
        style={input}
        placeholder="Twin name (e.g. My teaching twin)"
        value={name}
        onChange={(e) => setName(e.target.value)}
      />

      <div style={{ marginTop: 12, background: "#0f1115", borderRadius: 8, overflow: "hidden" }}>
        <video
          ref={videoRef}
          src={!recording && previewUrl ? previewUrl : undefined}
          controls={!recording && !!previewUrl}
          playsInline
          style={{ width: "100%", maxHeight: 320, display: recording || previewUrl ? "block" : "none", background: "#000" }}
        />
        {!recording && !previewUrl && (
          <div style={{ padding: 24, color: "#666", textAlign: "center" }}>No clip yet</div>
        )}
      </div>

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 12 }}>
        {!recording ? (
          <button style={btnSecondary} onClick={startRecording} disabled={submitting}>
            ● Record
          </button>
        ) : (
          <button style={{ ...btnSecondary, borderColor: "#5b2330", color: "#f1a5b0" }} onClick={stopRecording}>
            ■ Stop
          </button>
        )}

        <label style={{ ...btnSecondary, display: "inline-flex", alignItems: "center", cursor: "pointer" }}>
          Upload file
          <input type="file" accept="video/*" onChange={onFile} style={{ display: "none" }} disabled={recording || submitting} />
        </label>

        <button style={btnPrimary} onClick={submit} disabled={!blob || recording || submitting}>
          {submitting ? "Submitting…" : "Train twin"}
        </button>
      </div>

      <p style={{ color: "#666", fontSize: 12, marginTop: 10, marginBottom: 0 }}>
        MP4 / WebM / MOV · up to {MAX_VIDEO_MB}MB
        {blob ? ` · selected ${formatMb(blob.size)}` : ""}
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { bg: string; fg: string; label: string }> = {
    QUEUED: { bg: "#1f2937", fg: "#9ca3af", label: "Queued" },
    PROCESSING: { bg: "#1e2a4a", fg: "#7c93ff", label: "Processing…" },
    COMPLETED: { bg: "#13351f", fg: "#5fd28a", label: "Ready" },
    FAILED: { bg: "#3a1820", fg: "#f1a5b0", label: "Failed" },
  };
  const s = map[status] ?? { bg: "#1f2937", fg: "#9ca3af", label: status };
  return (
    <span style={{ background: s.bg, color: s.fg, borderRadius: 999, padding: "2px 10px", fontSize: 12, fontWeight: 600 }}>
      {s.label}
    </span>
  );
}

function formatMb(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
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
  padding: "8px 14px",
  cursor: "pointer",
  fontSize: 14,
  fontWeight: 600,
};

const btnSecondary: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "8px 14px",
  cursor: "pointer",
  fontSize: 14,
};
