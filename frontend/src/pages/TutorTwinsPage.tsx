import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { createTwin, deleteTwin, listLessons, listTwins } from "../api/tutor";
import type { LessonDTO, TwinDTO } from "../types/api";

// Reject oversized clips client-side before uploading. Kept comfortably under
// the backend's 200MB multipart cap — a short avatar-training clip (15–30s) is
// only a few MB, so this guards against accidental large/long recordings.
const MAX_VIDEO_MB = 150;
const MAX_VIDEO_BYTES = MAX_VIDEO_MB * 1024 * 1024;

// Seconds counted down (on screen) before recording actually starts, so the
// person has time to get framed and ready.
const COUNTDOWN_SECONDS = 5;

// A short, phonetically varied passage to read aloud while recording. ~65
// words ≈ 25–30s at a natural pace — enough audio for a good voice clone
// without dragging on.
const RECORDING_SCRIPT =
  "Hi! I'm recording this so my AI twin can learn how I look and sound. " +
  "I enjoy sharing ideas and breaking big topics down into simple, clear steps. " +
  "Today is a bright new day, full of questions worth exploring. " +
  "From science and history to art and everyday curiosity, there's always " +
  "something fascinating to discover. Thanks for listening — let's learn " +
  "something amazing together.";

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
          Create a twin from a short clip or photo of yourself, then generate lessons where your
          twin teaches any topic in your likeness.
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
                  {t.ready && (
                    <span style={{ marginLeft: 8, fontSize: 12, color: t.voiceCloned ? "#5fd28a" : "#9ca3af" }}>
                      {t.voiceCloned ? "· cloned voice" : "· default voice"}
                    </span>
                  )}
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
  const [previewIsImage, setPreviewIsImage] = useState(false);
  const [recording, setRecording] = useState(false);
  const [countdown, setCountdown] = useState<number | null>(null);
  const [audio, setAudio] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const countdownRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const counting = countdown !== null;

  useEffect(() => {
    return () => {
      streamRef.current?.getTracks().forEach((t) => t.stop());
      if (countdownRef.current) clearInterval(countdownRef.current);
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
    setPreviewIsImage(b.type.startsWith("image"));
    setPreviewUrl(URL.createObjectURL(b));
  };

  // Turn the camera on, show a live preview, then count down before recording
  // so the person can get ready.
  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        videoRef.current.muted = true;
        await videoRef.current.play().catch(() => undefined);
      }
      onError("");
      setCountdown(COUNTDOWN_SECONDS);
      countdownRef.current = setInterval(() => {
        setCountdown((prev) => {
          const next = (prev ?? 1) - 1;
          if (next <= 0) {
            if (countdownRef.current) clearInterval(countdownRef.current);
            countdownRef.current = null;
            beginRecording();
            return null;
          }
          return next;
        });
      }, 1000);
    } catch (err) {
      onError("Could not access camera/microphone. " + extractError(err));
      teardownStream();
    }
  };

  const beginRecording = () => {
    const stream = streamRef.current;
    if (!stream) return;
    chunksRef.current = [];
    const recorder = new MediaRecorder(stream);
    recorder.ondataavailable = (e) => {
      if (e.data.size > 0) chunksRef.current.push(e.data);
    };
    recorder.onstop = () => {
      const recorded = new Blob(chunksRef.current, { type: recorder.mimeType || "video/webm" });
      setRecorded(recorded);
      teardownStream();
    };
    recorderRef.current = recorder;
    recorder.start();
    setRecording(true);
  };

  const stopRecording = () => {
    recorderRef.current?.stop();
    setRecording(false);
  };

  // Abort during the pre-recording countdown.
  const cancelCountdown = () => {
    if (countdownRef.current) clearInterval(countdownRef.current);
    countdownRef.current = null;
    setCountdown(null);
    teardownStream();
  };

  const teardownStream = () => {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  };

  const onAudioFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0] ?? null;
    if (f && f.size > MAX_VIDEO_BYTES) {
      onError(`That audio file is ${formatMb(f.size)} — the limit is ${MAX_VIDEO_MB}MB.`);
      return;
    }
    setAudio(f);
  };

  const onFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) setRecorded(f);
  };

  const submit = async () => {
    if (!blob) return;
    setSubmitting(true);
    try {
      await createTwin(name.trim(), blob, audio);
      setName("");
      setBlob(null);
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      setPreviewUrl(null);
      setPreviewIsImage(false);
      setAudio(null);
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
        Record yourself reading the short script below (~30 seconds) — we capture your likeness
        as a talking avatar and clone your voice from the audio. A well-lit, front-facing shot in
        a quiet room works best. You can also upload a photo and add a separate voice recording
        below; a photo with no voice uses a preset voice.
      </p>

      <input
        style={input}
        placeholder="Twin name (e.g. My teaching twin)"
        value={name}
        onChange={(e) => setName(e.target.value)}
      />

      {/* Before recording, show the script as a plain box. Once the countdown
          starts it moves onto the camera preview (teleprompter overlay below). */}
      {!recording && !counting && (
        <div
          style={{
            marginTop: 12,
            background: "#0f1115",
            border: "1px solid #2a2d33",
            borderRadius: 8,
            padding: 14,
          }}
        >
          <div style={{ fontSize: 12, color: "#888", fontWeight: 600, marginBottom: 6 }}>
            Read this aloud (~30 seconds)
          </div>
          <p style={{ margin: 0, color: "#e6e6e6", fontSize: 16, lineHeight: 1.6 }}>{RECORDING_SCRIPT}</p>
        </div>
      )}

      <div style={{ position: "relative", marginTop: 12, background: "#0f1115", borderRadius: 8, overflow: "hidden" }}>
        {/* Live camera (during the countdown and while recording) uses the <video> ref. */}
        <video
          ref={videoRef}
          playsInline
          muted
          style={{ width: "100%", maxHeight: 320, display: recording || counting ? "block" : "none", background: "#000" }}
        />
        {!recording && !counting && previewUrl && previewIsImage && (
          <img src={previewUrl} alt="Selected" style={{ width: "100%", maxHeight: 320, objectFit: "contain", background: "#000", display: "block" }} />
        )}
        {!recording && !counting && previewUrl && !previewIsImage && (
          <video src={previewUrl} controls playsInline style={{ width: "100%", maxHeight: 320, background: "#000", display: "block" }} />
        )}
        {!recording && !counting && !previewUrl && (
          <div style={{ padding: 24, color: "#666", textAlign: "center" }}>No clip or photo yet</div>
        )}

        {/* Countdown overlay: big number centered over the live preview. */}
        {counting && (
          <div
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              background: "rgba(0,0,0,0.45)",
              color: "#fff",
            }}
          >
            <div style={{ fontSize: 72, fontWeight: 800, lineHeight: 1 }}>{countdown}</div>
            <div style={{ marginTop: 8, fontSize: 14, opacity: 0.85 }}>Get ready…</div>
          </div>
        )}

        {recording && (
          <div
            style={{
              position: "absolute",
              top: 10,
              left: 10,
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
              background: "rgba(0,0,0,0.55)",
              color: "#fff",
              borderRadius: 999,
              padding: "4px 10px",
              fontSize: 12,
              fontWeight: 600,
              zIndex: 2,
            }}
          >
            <span style={{ width: 8, height: 8, borderRadius: 999, background: "#ef4444", display: "inline-block" }} />
            REC
          </div>
        )}

        {/* Teleprompter overlay on the camera preview — read this while looking
            at the lens. Sits over the lower part of the video so your eyeline
            stays near the camera. */}
        {(recording || counting) && (
          <div
            style={{
              position: "absolute",
              left: 0,
              right: 0,
              bottom: 0,
              maxHeight: "60%",
              overflowY: "auto",
              zIndex: 3,
              padding: "28px 18px 16px",
              background: "linear-gradient(to top, rgba(0,0,0,0.88) 30%, rgba(0,0,0,0))",
              color: "#fff",
              fontSize: 18,
              lineHeight: 1.6,
              textAlign: "center",
            }}
          >
            {RECORDING_SCRIPT}
          </div>
        )}
      </div>

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 12 }}>
        {counting ? (
          <button style={{ ...btnSecondary, borderColor: "#5b2330", color: "#f1a5b0" }} onClick={cancelCountdown}>
            Cancel ({countdown})
          </button>
        ) : !recording ? (
          <button style={btnSecondary} onClick={startRecording} disabled={submitting}>
            ● Record
          </button>
        ) : (
          <button style={{ ...btnSecondary, borderColor: "#5b2330", color: "#f1a5b0" }} onClick={stopRecording}>
            ■ Stop
          </button>
        )}

        <label
          style={{
            ...btnSecondary,
            display: "inline-flex",
            alignItems: "center",
            cursor: recording || counting || submitting ? "not-allowed" : "pointer",
            opacity: recording || counting || submitting ? 0.5 : 1,
          }}
        >
          Upload video/photo
          <input type="file" accept="video/*,image/*" onChange={onFile} style={{ display: "none" }} disabled={recording || counting || submitting} />
        </label>

        <button style={btnPrimary} onClick={submit} disabled={!blob || recording || counting || submitting}>
          {submitting ? "Submitting…" : "Train twin"}
        </button>
      </div>

      {/* Optional separate voice sample — handy when the likeness is a photo,
          or to clone the voice from a cleaner audio take than the video. */}
      <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", marginTop: 10 }}>
        <label
          style={{
            ...btnSecondary,
            fontSize: 13,
            display: "inline-flex",
            alignItems: "center",
            cursor: recording || counting || submitting ? "not-allowed" : "pointer",
            opacity: recording || counting || submitting ? 0.5 : 1,
          }}
        >
          {audio ? "Change voice" : "Add voice (optional)"}
          <input type="file" accept="audio/*" onChange={onAudioFile} style={{ display: "none" }} disabled={recording || counting || submitting} />
        </label>
        {audio && (
          <span style={{ color: "#9ca3af", fontSize: 12 }}>
            {audio.name} ({formatMb(audio.size)})
            <button
              onClick={() => setAudio(null)}
              style={{ marginLeft: 8, background: "transparent", border: "none", color: "#f1a5b0", cursor: "pointer", fontSize: 12 }}
            >
              remove
            </button>
          </span>
        )}
      </div>

      <p style={{ color: "#666", fontSize: 12, marginTop: 10, marginBottom: 0 }}>
        MP4 / WebM / MOV or JPG / PNG · up to {MAX_VIDEO_MB}MB · voice: MP3 / WAV / M4A
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
