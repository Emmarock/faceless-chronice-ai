import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useParams } from "react-router-dom";
import {
  appendSceneImage,
  deleteSceneImage,
  deleteSourceVideo,
  getJob,
  getJobProgress,
  imageStreamUrl,
  regenerateScene,
  regenerateScenes,
  replaceSceneImage,
  replaceSourceVideo,
  resumeJob,
  sourceVideoStreamUrl,
  updateScript,
} from "../api/jobs";
import { reuseAssetInScene } from "../api/assets";
import { AssetPicker } from "../components/AssetPicker";
import { ProgressBar } from "../components/ProgressBar";
import type {
  AssetSummaryDTO,
  AssetType,
  JobFileDTO,
  JobProgressDTO,
  MediaMode,
  Scene,
  VideoScript,
} from "../types/api";

const PROGRESS_POLL_INTERVAL_MS = 3000;

function isTerminalStatus(status?: string | null): boolean {
  return status === "COMPLETED" || status === "FAILED";
}

// Reserved segment IDs used by the backend's VideoScript model. The title/hook/
// closing wrappers also get generated images, so we expose image grids for
// them too — keyed off these constants when there's no scene-N to render.
const SEGMENT_TITLE = -2;
const SEGMENT_HOOK = -1;
const SEGMENT_CLOSING = 1000;

interface LightboxState {
  jobId: string;
  sceneId: number;
  index: number;
  total: number;
  version: number;
}

interface PickerState {
  sceneId: number;
  type: AssetType;
}

export function JobDetailPage() {
  const { jobId = "" } = useParams();
  const location = useLocation();
  const initialJobFile = (location.state as { jobFile?: JobFileDTO } | null)?.jobFile ?? null;

  const [jobFile, setJobFile] = useState<JobFileDTO | null>(initialJobFile);
  const [draft, setDraft] = useState<VideoScript | null>(initialJobFile?.videoScript ?? null);
  const [loading, setLoading] = useState(!initialJobFile);
  const [saving, setSaving] = useState(false);
  const [resuming, setResuming] = useState(false);
  const [regenerating, setRegenerating] = useState<number | "all" | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<JobProgressDTO | null>(null);
  // Bumped after every image mutation to bust the browser's <img> cache for
  // (sceneId,index) slots that get replaced in place.
  const [imageVersion, setImageVersion] = useState(0);
  const [lightbox, setLightbox] = useState<LightboxState | null>(null);
  const [picker, setPicker] = useState<PickerState | null>(null);

  const loadJob = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await getJob(jobId);
      setJobFile(result);
      setDraft(result.videoScript);
    } catch (err) {
      setError(extractError(err));
    } finally {
      setLoading(false);
    }
  }, [jobId]);

  useEffect(() => {
    if (!jobId) return;
    if (!initialJobFile) {
      loadJob();
    }
    // The location-state copy (passed from CreateJobPage) is fine for the
    // first paint; we deliberately don't refetch on every mount when we have it.
  }, [jobId, initialJobFile, loadJob]);

  // Progress poller. Hits the cheap /progress endpoint on a short interval
  // while the pipeline is in-flight, then stops once the job reaches a
  // terminal state. Re-starts itself if the user clicks Resume (which flips
  // status back to PROCESSING).
  useEffect(() => {
    if (!jobId) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const tick = async () => {
      try {
        const next = await getJobProgress(jobId);
        if (cancelled) return;
        setProgress(next);
        if (isTerminalStatus(next.status)) return;
      } catch {
        // Transient failure — try again next tick.
      }
      if (cancelled) return;
      timer = setTimeout(tick, PROGRESS_POLL_INTERVAL_MS);
    };

    tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [jobId, resuming]);

  const isDirty = useMemo(() => {
    if (!draft || !jobFile) return false;
    return JSON.stringify(draft) !== JSON.stringify(jobFile.videoScript);
  }, [draft, jobFile]);

  const handleSave = async () => {
    if (!draft) return;
    setSaving(true);
    setMessage(null);
    setError(null);
    try {
      const updated = await updateScript(jobId, draft);
      setJobFile(updated);
      setDraft(updated.videoScript);
      setMessage("Script saved.");
    } catch (err) {
      setError(extractError(err));
    } finally {
      setSaving(false);
    }
  };

  const handleResume = async () => {
    if (isDirty) {
      setError("You have unsaved edits. Save them before resuming.");
      return;
    }
    setResuming(true);
    setMessage(null);
    setError(null);
    try {
      const result = await resumeJob(jobId);
      setJobFile(result);
      setDraft(result.videoScript);
      setMessage(
        "Resume requested — voice / image / video generation will pick up at the earliest incomplete stage.",
      );
    } catch (err) {
      setError(extractError(err));
    } finally {
      setResuming(false);
    }
  };

  const updateScene = (sceneNumber: number, text: string) => {
    setDraft((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        scenes: (prev.scenes ?? []).map((s) =>
          s.scene === sceneNumber ? { ...s, text } : s,
        ),
      };
    });
  };

  const updateField = (field: "title" | "hook" | "closing", value: string) => {
    setDraft((prev) => (prev ? { ...prev, [field]: value } : prev));
  };

  /**
   * Mode toggle for regular scenes (positive scene numbers). Persisted on
   * the next "Save changes" via the existing /script PUT.
   */
  const updateSceneMediaMode = (sceneNumber: number, mode: MediaMode) => {
    setDraft((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        scenes: (prev.scenes ?? []).map((s) =>
          s.scene === sceneNumber ? { ...s, mediaMode: mode } : s,
        ),
      };
    });
  };

  /**
   * Mode toggle for the title / hook / closing wrapper segments. The
   * backend lazy-init's the matching {@code titleScene} / {@code hookScene}
   * / {@code closingScene} wrapper Scene objects on its side; here we make
   * sure the FE draft also has the wrapper present (with the chosen mode
   * and the current wrapper text copied in), so the serialised script we
   * PUT carries the {@code mediaMode} the user just selected.
   */
  const updateWrapperMediaMode = (
    field: "title" | "hook" | "closing",
    mode: MediaMode,
  ) => {
    setDraft((prev) => {
      if (!prev) return prev;
      const sceneId =
        field === "title" ? -2 : field === "hook" ? -1 : 1000;
      const wrapperKey =
        field === "title"
          ? "titleScene"
          : field === "hook"
          ? "hookScene"
          : "closingScene";
      const existing = prev[wrapperKey] as Scene | undefined;
      const text = (prev[field] as string | undefined) ?? "";
      const next: Scene = existing
        ? { ...existing, mediaMode: mode, text }
        : { scene: sceneId, text, imageFiles: [], sourceVideoFiles: [], mediaMode: mode };
      return { ...prev, [wrapperKey]: next };
    });
  };

  // Image mutations re-fetch from the server (the response IS the refreshed
  // JobFileDTO) so imageFiles, voiceFile, videoFile stay in sync. Local script
  // edits in `draft` are preserved by merging only the asset-bearing fields.
  const mergeAssetsInto = useCallback(
    (refreshed: JobFileDTO) => {
      setJobFile(refreshed);
      setDraft((prev) => {
        if (!prev) return refreshed.videoScript;
        return mergeScriptAssets(prev, refreshed.videoScript);
      });
      setImageVersion((v) => v + 1);
    },
    [],
  );

  const handleReplaceImage = async (sceneId: number, index: number, file: File) => {
    setMessage(null);
    setError(null);
    try {
      const refreshed = await replaceSceneImage(jobId, sceneId, index, file);
      mergeAssetsInto(refreshed);
      setMessage(
        `Replaced image ${index + 1} in ${sceneLabel(sceneId)}. Click Resume to re-render.`,
      );
    } catch (err) {
      setError(extractError(err));
    }
  };

  const handleAppendImage = async (sceneId: number, file: File) => {
    setMessage(null);
    setError(null);
    try {
      const result = await appendSceneImage(jobId, sceneId, file);
      mergeAssetsInto(result.jobFile);
      setMessage(`Added an image to ${sceneLabel(sceneId)}. Click Resume to re-render.`);
    } catch (err) {
      setError(extractError(err));
    }
  };

  const handleDeleteImage = async (sceneId: number, index: number) => {
    setMessage(null);
    setError(null);
    try {
      const refreshed = await deleteSceneImage(jobId, sceneId, index);
      mergeAssetsInto(refreshed);
      setMessage(
        `Removed image ${index + 1} from ${sceneLabel(sceneId)}. Click Resume to re-render.`,
      );
    } catch (err) {
      setError(extractError(err));
    }
  };

  const handleReplaceSourceVideo = async (sceneId: number, file: File) => {
    setMessage(null);
    setError(null);
    try {
      const refreshed = await replaceSourceVideo(jobId, sceneId, file);
      mergeAssetsInto(refreshed);
      setMessage(
        `Replaced source video for ${sceneLabel(sceneId)}. Click Resume to re-render.`,
      );
    } catch (err) {
      setError(extractError(err));
    }
  };

  const handleRegenerateAllScenes = async () => {
    if (isDirty) {
      setError("You have unsaved edits. Save them before regenerating.");
      return;
    }
    if (!confirm(
      "Regenerate every scene's text?\n\nThis will overwrite the current scenes with fresh AI output and delete every voice, image, and video clip tied to those scenes. Title, hook, and closing are kept as-is.",
    )) return;
    setRegenerating("all");
    setMessage(null);
    setError(null);
    try {
      const refreshed = await regenerateScenes(jobId);
      setJobFile(refreshed);
      setDraft(refreshed.videoScript);
      setImageVersion((v) => v + 1);
      setMessage("All scenes regenerated. Click Resume to rebuild voices, images, and the final video.");
    } catch (err) {
      setError(extractError(err));
    } finally {
      setRegenerating(null);
    }
  };

  const handleRegenerateScene = async (sceneId: number) => {
    if (isDirty) {
      setError("You have unsaved edits. Save them before regenerating.");
      return;
    }
    setRegenerating(sceneId);
    setMessage(null);
    setError(null);
    try {
      const refreshed = await regenerateScene(jobId, sceneId);
      setJobFile(refreshed);
      setDraft(refreshed.videoScript);
      setImageVersion((v) => v + 1);
      setMessage(`Scene ${sceneId} regenerated. Click Resume to rebuild its voice and visuals.`);
    } catch (err) {
      setError(extractError(err));
    } finally {
      setRegenerating(null);
    }
  };

  const handlePickFromLibrary = async (asset: AssetSummaryDTO) => {
    if (!picker) return;
    const { sceneId } = picker;
    setMessage(null);
    setError(null);
    try {
      await reuseAssetInScene(asset.id, jobId, sceneId);
      const refreshed = await getJob(jobId);
      mergeAssetsInto(refreshed);
      setPicker(null);
      setMessage(
        `Added ${picker.type === "IMAGE" ? "image" : "video"} from your library to ${sceneLabel(
          sceneId,
        )}. Click Resume to re-render.`,
      );
    } catch (err) {
      setError(extractError(err));
    }
  };

  const handleDeleteSourceVideo = async (sceneId: number) => {
    setMessage(null);
    setError(null);
    try {
      const refreshed = await deleteSourceVideo(jobId, sceneId);
      mergeAssetsInto(refreshed);
      setMessage(
        `Removed source video for ${sceneLabel(sceneId)}. Click Resume to fetch a fresh clip.`,
      );
    } catch (err) {
      setError(extractError(err));
    }
  };

  return (
    <div>
      <div style={{ marginBottom: 16, fontSize: 13, color: "#888" }}>Content ID: {jobId}</div>

      {progress && (
        <div style={{ ...card, marginBottom: 16 }}>
          <ProgressBar
            progress={progress.progress}
            status={progress.status}
            stage={progress.stage}
          />
        </div>
      )}

      {loading ? (
        <div style={{ ...card, color: "#aaa" }}>Loading script...</div>
      ) : draft ? (
        <ScriptEditor
          jobId={jobId}
          script={draft}
          imageVersion={imageVersion}
          regeneratingSceneId={typeof regenerating === "number" ? regenerating : null}
          onUpdateScene={updateScene}
          onUpdateField={updateField}
          onUpdateSceneMediaMode={updateSceneMediaMode}
          onUpdateWrapperMediaMode={updateWrapperMediaMode}
          onRegenerateScene={handleRegenerateScene}
          onReplaceImage={handleReplaceImage}
          onAppendImage={handleAppendImage}
          onDeleteImage={handleDeleteImage}
          onReplaceSourceVideo={handleReplaceSourceVideo}
          onDeleteSourceVideo={handleDeleteSourceVideo}
          onPickFromLibrary={(sceneId, type) => setPicker({ sceneId, type })}
          onZoom={(sceneId, index, total) =>
            setLightbox({ jobId, sceneId, index, total, version: imageVersion })
          }
        />
      ) : (
        <div style={{ ...card, color: "#aaa" }}>
          No script available for this content yet.
        </div>
      )}

      <div style={{ marginTop: 24, display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
        <button
          onClick={handleSave}
          disabled={saving || !draft || !isDirty}
          style={{ ...btnPrimary, opacity: !isDirty || saving ? 0.6 : 1 }}
        >
          {saving ? "Saving..." : isDirty ? "Save changes" : "Saved"}
        </button>
        <button
          onClick={handleResume}
          disabled={resuming || !draft || isDirty}
          style={{ ...btnAccent, opacity: resuming || isDirty ? 0.6 : 1 }}
          title={isDirty ? "Save your edits before resuming" : undefined}
        >
          {resuming ? "Resuming..." : "Continue Video Generation"}
        </button>
        <button
          onClick={handleRegenerateAllScenes}
          disabled={regenerating !== null || !draft || isDirty}
          style={{ ...btnSecondary, opacity: regenerating !== null || isDirty ? 0.6 : 1 }}
          title={isDirty ? "Save your edits before regenerating" : "Ask the AI for a fresh take on every scene"}
        >
          {regenerating === "all" ? "Regenerating..." : "Regenerate all scenes"}
        </button>
        {message && <span style={{ color: "#4ade80" }}>{message}</span>}
        {error && <span style={{ color: "#ff6b6b" }}>{error}</span>}
      </div>
      <p style={{ marginTop: 12, fontSize: 12, color: "#888" }}>
        Voice, image, and video generation only run when you click <em>Resume pipeline</em>.
        Image edits invalidate just the affected scene's cached clip; the next resume re-renders that
        scene and re-concatenates the final video.
      </p>

      {picker && (
        <AssetPicker
          type={picker.type}
          excludeJobId={jobId}
          onCancel={() => setPicker(null)}
          onPick={handlePickFromLibrary}
        />
      )}

      {lightbox && (
        <Lightbox
          state={lightbox}
          onClose={() => setLightbox(null)}
          onNext={() =>
            setLightbox((l) =>
              l ? { ...l, index: (l.index + 1) % l.total } : l,
            )
          }
          onPrev={() =>
            setLightbox((l) =>
              l ? { ...l, index: (l.index - 1 + l.total) % l.total } : l,
            )
          }
        />
      )}
    </div>
  );
}

interface ScriptEditorProps {
  jobId: string;
  script: VideoScript;
  imageVersion: number;
  regeneratingSceneId: number | null;
  onUpdateScene: (sceneNumber: number, text: string) => void;
  onUpdateField: (field: "title" | "hook" | "closing", value: string) => void;
  onUpdateSceneMediaMode: (sceneNumber: number, mode: MediaMode) => void;
  onUpdateWrapperMediaMode: (field: "title" | "hook" | "closing", mode: MediaMode) => void;
  onRegenerateScene: (sceneId: number) => void;
  onReplaceImage: (sceneId: number, index: number, file: File) => void;
  onAppendImage: (sceneId: number, file: File) => void;
  onDeleteImage: (sceneId: number, index: number) => void;
  onReplaceSourceVideo: (sceneId: number, file: File) => void;
  onDeleteSourceVideo: (sceneId: number) => void;
  onPickFromLibrary: (sceneId: number, type: AssetType) => void;
  onZoom: (sceneId: number, index: number, total: number) => void;
}

function ScriptEditor({
  jobId,
  script,
  imageVersion,
  regeneratingSceneId,
  onUpdateScene,
  onUpdateField,
  onUpdateSceneMediaMode,
  onUpdateWrapperMediaMode,
  onRegenerateScene,
  onReplaceImage,
  onAppendImage,
  onDeleteImage,
  onReplaceSourceVideo,
  onDeleteSourceVideo,
  onPickFromLibrary,
  onZoom,
}: ScriptEditorProps) {
  const titleMode: MediaMode = script.titleScene?.mediaMode ?? "VIDEO_CLIP";
  const hookMode: MediaMode = script.hookScene?.mediaMode ?? "VIDEO_CLIP";
  const closingMode: MediaMode = script.closingScene?.mediaMode ?? "VIDEO_CLIP";
  const renderWrapperMedia = (
    sceneId: number,
    mode: MediaMode,
    wrapper: Scene | undefined,
  ) => {
    if (mode === "VIDEO_CLIP") {
      return (
        <SegmentVideo
          jobId={jobId}
          sceneId={sceneId}
          sourceVideo={wrapper?.sourceVideoFiles?.[0]}
          version={imageVersion}
          onReplace={(f) => onReplaceSourceVideo(sceneId, f)}
          onDelete={() => onDeleteSourceVideo(sceneId)}
          onPickFromLibrary={() => onPickFromLibrary(sceneId, "SOURCE_VIDEO")}
        />
      );
    }
    return (
      <SegmentImages
        jobId={jobId}
        sceneId={sceneId}
        imageFiles={wrapper?.imageFiles ?? []}
        imageVersion={imageVersion}
        onReplace={(idx, f) => onReplaceImage(sceneId, idx, f)}
        onAppend={(f) => onAppendImage(sceneId, f)}
        onDelete={(idx) => onDeleteImage(sceneId, idx)}
        onPickFromLibrary={() => onPickFromLibrary(sceneId, "IMAGE")}
        onZoom={(idx, total) => onZoom(sceneId, idx, total)}
      />
    );
  };

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={card}>
        <WrapperHeader label="Title" mode={titleMode} onChange={(m) => onUpdateWrapperMediaMode("title", m)} />
        <input
          value={script.title ?? ""}
          onChange={(e) => onUpdateField("title", e.target.value)}
          style={input}
        />
        {renderWrapperMedia(SEGMENT_TITLE, titleMode, script.titleScene)}
      </div>

      <div style={card}>
        <WrapperHeader label="Hook" mode={hookMode} onChange={(m) => onUpdateWrapperMediaMode("hook", m)} />
        <textarea
          value={script.hook ?? ""}
          onChange={(e) => onUpdateField("hook", e.target.value)}
          rows={3}
          style={textarea}
        />
        {renderWrapperMedia(SEGMENT_HOOK, hookMode, script.hookScene)}
      </div>

      {(script.scenes ?? []).map((s) => (
        <SceneEditor
          key={s.scene}
          jobId={jobId}
          scene={s}
          imageVersion={imageVersion}
          regenerating={regeneratingSceneId === s.scene}
          onChange={(t) => onUpdateScene(s.scene, t)}
          onModeChange={(m) => onUpdateSceneMediaMode(s.scene, m)}
          onRegenerate={() => onRegenerateScene(s.scene)}
          onReplaceImage={(idx, f) => onReplaceImage(s.scene, idx, f)}
          onAppendImage={(f) => onAppendImage(s.scene, f)}
          onDeleteImage={(idx) => onDeleteImage(s.scene, idx)}
          onReplaceSourceVideo={(f) => onReplaceSourceVideo(s.scene, f)}
          onDeleteSourceVideo={() => onDeleteSourceVideo(s.scene)}
          onPickFromLibrary={(type) => onPickFromLibrary(s.scene, type)}
          onZoom={(idx, total) => onZoom(s.scene, idx, total)}
        />
      ))}

      <div style={card}>
        <WrapperHeader
          label="Closing"
          mode={closingMode}
          onChange={(m) => onUpdateWrapperMediaMode("closing", m)}
        />
        <textarea
          value={script.closing ?? ""}
          onChange={(e) => onUpdateField("closing", e.target.value)}
          rows={3}
          style={textarea}
        />
        {renderWrapperMedia(SEGMENT_CLOSING, closingMode, script.closingScene)}
      </div>
    </div>
  );
}

function WrapperHeader({
  label,
  mode,
  onChange,
}: {
  label: string;
  mode: MediaMode;
  onChange: (m: MediaMode) => void;
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        marginBottom: 6,
        gap: 12,
        flexWrap: "wrap",
      }}
    >
      <Label>{label}</Label>
      <ModeToggle mode={mode} onChange={onChange} />
    </div>
  );
}

interface SceneEditorProps {
  jobId: string;
  scene: Scene;
  imageVersion: number;
  regenerating: boolean;
  onChange: (text: string) => void;
  onModeChange: (mode: MediaMode) => void;
  onRegenerate: () => void;
  onReplaceImage: (index: number, file: File) => void;
  onAppendImage: (file: File) => void;
  onDeleteImage: (index: number) => void;
  onReplaceSourceVideo: (file: File) => void;
  onDeleteSourceVideo: () => void;
  onPickFromLibrary: (type: AssetType) => void;
  onZoom: (index: number, total: number) => void;
}

function SceneEditor({
  jobId,
  scene,
  imageVersion,
  regenerating,
  onChange,
  onModeChange,
  onRegenerate,
  onReplaceImage,
  onAppendImage,
  onDeleteImage,
  onReplaceSourceVideo,
  onDeleteSourceVideo,
  onPickFromLibrary,
  onZoom,
}: SceneEditorProps) {
  const mode: MediaMode = scene.mediaMode ?? "VIDEO_CLIP";
  const sourceVideo = scene.sourceVideoFiles?.[0];

  return (
    <div style={card}>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          marginBottom: 6,
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <div style={{ fontSize: 12, color: "#888" }}>Scene {scene.scene}</div>
        <div style={{ display: "inline-flex", gap: 8, alignItems: "center" }}>
          <button
            type="button"
            onClick={onRegenerate}
            disabled={regenerating}
            style={{
              background: "transparent",
              color: "#cbd5e1",
              border: "1px solid #2a2d33",
              borderRadius: 6,
              padding: "4px 10px",
              fontSize: 12,
              fontWeight: 600,
              cursor: regenerating ? "wait" : "pointer",
              minHeight: 28,
              opacity: regenerating ? 0.6 : 1,
            }}
            title="Ask the AI to rewrite this scene's text"
          >
            {regenerating ? "Regenerating…" : "Regenerate"}
          </button>
          <ModeToggle mode={mode} onChange={onModeChange} />
        </div>
      </div>
      <textarea
        value={scene.text ?? ""}
        onChange={(e) => onChange(e.target.value)}
        rows={4}
        style={textarea}
      />
      <div style={{ display: "flex", gap: 12, marginTop: 6 }}>
        {scene.voiceFile && (
          <span style={{ fontSize: 11, color: "#4ade80" }}>✓ voice generated</span>
        )}
        {scene.videoFile && (
          <span style={{ fontSize: 11, color: "#4ade80" }}>✓ video clip rendered</span>
        )}
      </div>
      {mode === "VIDEO_CLIP" ? (
        <SegmentVideo
          jobId={jobId}
          sceneId={scene.scene}
          sourceVideo={sourceVideo}
          version={imageVersion}
          onReplace={onReplaceSourceVideo}
          onDelete={onDeleteSourceVideo}
          onPickFromLibrary={() => onPickFromLibrary("SOURCE_VIDEO")}
        />
      ) : (
        <SegmentImages
          jobId={jobId}
          sceneId={scene.scene}
          imageFiles={scene.imageFiles ?? []}
          imageVersion={imageVersion}
          onReplace={onReplaceImage}
          onAppend={onAppendImage}
          onDelete={onDeleteImage}
          onPickFromLibrary={() => onPickFromLibrary("IMAGE")}
          onZoom={onZoom}
        />
      )}
    </div>
  );
}

function ModeToggle({ mode, onChange }: { mode: MediaMode; onChange: (m: MediaMode) => void }) {
  const seg = (target: MediaMode, label: string) => {
    const active = mode === target;
    return (
      <button
        type="button"
        onClick={() => {
          if (!active) onChange(target);
        }}
        style={{
          background: active ? "#1d4ed8" : "transparent",
          color: active ? "#fff" : "#cbd5e1",
          border: "1px solid " + (active ? "#3b82f6" : "#2a2d33"),
          borderRadius: 6,
          padding: "4px 10px",
          fontSize: 12,
          fontWeight: 600,
          cursor: active ? "default" : "pointer",
          minHeight: 28,
        }}
      >
        {label}
      </button>
    );
  };

  return (
    <div style={{ display: "inline-flex", gap: 6 }} role="group" aria-label="Scene media mode">
      {seg("IMAGES", "Images")}
      {seg("VIDEO_CLIP", "Video")}
    </div>
  );
}

interface SegmentVideoProps {
  jobId: string;
  sceneId: number;
  sourceVideo: string | undefined;
  version: number;
  onReplace: (file: File) => void;
  onDelete: () => void;
  onPickFromLibrary: () => void;
}

function SegmentVideo({ jobId, sceneId, sourceVideo, version, onReplace, onDelete, onPickFromLibrary }: SegmentVideoProps) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [draggingOver, setDraggingOver] = useState(false);
  const [status, setStatus] = useState<"loading" | "ok" | "error">(sourceVideo ? "loading" : "ok");
  const src = sourceVideo ? sourceVideoStreamUrl(jobId, sceneId, version) : null;

  if (!sourceVideo) {
    return (
      <div
        style={emptyImagesBlock}
        onDragEnter={(e) => {
          e.preventDefault();
          setDraggingOver(true);
        }}
        onDragOver={(e) => {
          e.preventDefault();
          setDraggingOver(true);
        }}
        onDragLeave={() => setDraggingOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDraggingOver(false);
          const f = e.dataTransfer.files?.[0];
          if (f && f.type.startsWith("video/")) onReplace(f);
        }}
      >
        <div style={{ fontSize: 12, color: "#aaa", marginBottom: 8 }}>
          No source video for this scene yet.
        </div>
        <div style={{ fontSize: 11, color: "#888", marginBottom: 12 }}>
          Run <em>Resume pipeline</em> to fetch a Pexels stock clip, or upload your own below.
        </div>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            style={{
              ...appendTile,
              height: 140,
              flex: "1 1 220px",
              borderColor: draggingOver ? "#3b82f6" : "#3b82f6",
              background: draggingOver ? "#172033" : "#0f1115",
            }}
            title="Upload a video clip"
          >
            <span style={{ fontSize: 22, lineHeight: 1 }}>+</span>
            <span style={{ fontSize: 12, marginTop: 4 }}>Upload video</span>
            <span style={{ fontSize: 10, color: "#7a8db3", marginTop: 2 }}>
              click or drop a .mp4 / .mov / .webm
            </span>
          </button>
          <button
            type="button"
            onClick={onPickFromLibrary}
            style={{
              ...appendTile,
              height: 140,
              flex: "1 1 220px",
              borderStyle: "solid",
              borderColor: "#2a2d33",
              color: "#cbd5e1",
            }}
            title="Reuse a video from your asset library"
          >
            <span style={{ fontSize: 22, lineHeight: 1 }}>↻</span>
            <span style={{ fontSize: 12, marginTop: 4 }}>From library</span>
            <span style={{ fontSize: 10, color: "#7a8db3", marginTop: 2 }}>
              pick a video you've used before
            </span>
          </button>
        </div>
        <input
          ref={fileRef}
          type="file"
          accept="video/*"
          style={{ display: "none" }}
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) onReplace(f);
            e.target.value = "";
          }}
        />
      </div>
    );
  }

  return (
    <div
      style={{ marginTop: 14 }}
      onDragEnter={(e) => {
        e.preventDefault();
        setDraggingOver(true);
      }}
      onDragOver={(e) => {
        e.preventDefault();
        setDraggingOver(true);
      }}
      onDragLeave={() => setDraggingOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDraggingOver(false);
        const f = e.dataTransfer.files?.[0];
        if (f && f.type.startsWith("video/")) onReplace(f);
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "baseline",
          justifyContent: "space-between",
          marginBottom: 8,
          gap: 8,
          flexWrap: "wrap",
        }}
      >
        <span style={{ fontSize: 12, color: "#aaa" }}>
          Source video — drag a file onto the player to replace
        </span>
      </div>
      <div
        style={{
          ...tile,
          borderColor: draggingOver ? "#3b82f6" : "#2a2d33",
          boxShadow: draggingOver ? "0 0 0 2px rgba(59,130,246,0.35)" : "none",
        }}
      >
        {src && (
          <video
            key={src}
            src={src}
            controls
            preload="metadata"
            onLoadedMetadata={() => setStatus("ok")}
            onError={() => setStatus("error")}
            style={{
              width: "100%",
              maxHeight: 320,
              background: "#000",
              display: "block",
            }}
          />
        )}
        {status === "error" && (
          <div style={{ ...tileFooter, color: "#ffb4b4", fontSize: 12 }}>
            Source video is missing from storage — upload a replacement below.
          </div>
        )}
        <div
          style={{
            ...tileFooter,
            justifyContent: "flex-end",
            gap: 8,
          }}
        >
          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            style={tileBtn}
            title="Replace this source video with one from your computer"
          >
            Replace
          </button>
          <button
            type="button"
            onClick={onPickFromLibrary}
            style={tileBtn}
            title="Replace with a video from your asset library"
          >
            From library
          </button>
          <button
            type="button"
            onClick={onDelete}
            style={tileBtnDanger}
            title="Remove this source video — Resume will re-fetch from the provider"
          >
            Delete
          </button>
        </div>
      </div>
      <input
        ref={fileRef}
        type="file"
        accept="video/*"
        style={{ display: "none" }}
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onReplace(f);
          e.target.value = "";
        }}
      />
    </div>
  );
}

interface SegmentImagesProps {
  jobId: string;
  sceneId: number;
  imageFiles: string[];
  imageVersion: number;
  onReplace: (index: number, file: File) => void;
  onAppend: (file: File) => void;
  onDelete: (index: number) => void;
  onPickFromLibrary: () => void;
  onZoom: (index: number, total: number) => void;
}

function SegmentImages({
  jobId,
  sceneId,
  imageFiles,
  imageVersion,
  onReplace,
  onAppend,
  onDelete,
  onPickFromLibrary,
  onZoom,
}: SegmentImagesProps) {
  const count = imageFiles.length;

  if (count === 0) {
    return (
      <div style={emptyImagesBlock}>
        <div style={{ fontSize: 12, color: "#aaa", marginBottom: 8 }}>
          No images generated yet for this segment.
        </div>
        <div style={{ fontSize: 11, color: "#888" }}>
          Run <em>Resume pipeline</em> to generate them, or use <strong>+ Add image</strong> below
          to upload one manually.
        </div>
        <div className="image-grid" style={{ marginTop: 12 }}>
          <AppendTile onPick={onAppend} />
          <LibraryPickTile onPick={onPickFromLibrary} />
        </div>
      </div>
    );
  }

  return (
    <div style={{ marginTop: 14 }}>
      <div
        style={{
          display: "flex",
          alignItems: "baseline",
          justifyContent: "space-between",
          marginBottom: 8,
        }}
      >
        <span style={{ fontSize: 12, color: "#aaa" }}>
          Images ({count}) — click to enlarge, hover for actions
        </span>
      </div>
      <div className="image-grid">
        {imageFiles.map((_, idx) => (
          <ImageTile
            key={idx}
            jobId={jobId}
            sceneId={sceneId}
            index={idx}
            version={imageVersion}
            canDelete={count > 1}
            onReplace={(f) => onReplace(idx, f)}
            onDelete={() => onDelete(idx)}
            onZoom={() => onZoom(idx, count)}
          />
        ))}
        <AppendTile onPick={onAppend} />
        <LibraryPickTile onPick={onPickFromLibrary} />
      </div>
    </div>
  );
}

function LibraryPickTile({ onPick }: { onPick: () => void }) {
  return (
    <button
      type="button"
      onClick={onPick}
      style={{
        ...appendTile,
        borderStyle: "solid",
        borderColor: "#2a2d33",
        color: "#cbd5e1",
      }}
      title="Reuse an image from your asset library"
    >
      <span style={{ fontSize: 22, lineHeight: 1 }}>↻</span>
      <span style={{ fontSize: 12, marginTop: 4 }}>From library</span>
      <span style={{ fontSize: 10, color: "#7a8db3", marginTop: 2 }}>
        pick an image you've used before
      </span>
    </button>
  );
}

interface ImageTileProps {
  jobId: string;
  sceneId: number;
  index: number;
  version: number;
  canDelete: boolean;
  onReplace: (file: File) => void;
  onDelete: () => void;
  onZoom: () => void;
}

function ImageTile({
  jobId,
  sceneId,
  index,
  version,
  canDelete,
  onReplace,
  onDelete,
  onZoom,
}: ImageTileProps) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [hover, setHover] = useState(false);
  const [status, setStatus] = useState<"loading" | "ok" | "error">("loading");
  const [draggingOver, setDraggingOver] = useState(false);
  const src = imageStreamUrl(jobId, sceneId, index, version);

  return (
    <div
      style={{
        ...tile,
        borderColor: draggingOver ? "#3b82f6" : "#2a2d33",
        boxShadow: draggingOver ? "0 0 0 2px rgba(59,130,246,0.35)" : "none",
      }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onDragEnter={(e) => {
        e.preventDefault();
        setDraggingOver(true);
      }}
      onDragOver={(e) => {
        e.preventDefault();
        setDraggingOver(true);
      }}
      onDragLeave={() => setDraggingOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDraggingOver(false);
        const f = e.dataTransfer.files?.[0];
        if (f && f.type.startsWith("image/")) onReplace(f);
      }}
    >
      <div
        style={tileImageWrap}
        onClick={status === "ok" ? onZoom : undefined}
        title={status === "ok" ? "Click to enlarge" : undefined}
      >
        {status === "loading" && (
          <div style={{ ...tileSkeleton, position: "absolute", inset: 0 }}>
            Loading...
          </div>
        )}
        {status === "error" && (
          <div
            style={{
              ...tileSkeleton,
              position: "absolute",
              inset: 0,
              color: "#ffb4b4",
              display: "flex",
              flexDirection: "column",
              gap: 8,
              padding: 12,
              textAlign: "center",
            }}
          >
            <span style={{ fontSize: 22 }}>⚠</span>
            <span style={{ fontSize: 12, lineHeight: 1.4 }}>
              Image is missing from storage
            </span>
            <span style={{ fontSize: 10, color: "#9aa3b2" }}>
              (the file was lost — likely a LocalStack restart)
            </span>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                fileRef.current?.click();
              }}
              style={{ ...tileBtn, marginTop: 4 }}
              title="Upload a replacement at this slot"
            >
              Upload replacement
            </button>
          </div>
        )}
        <img
          src={src}
          alt={`${sceneLabel(sceneId)} image ${index + 1}`}
          onLoad={() => setStatus("ok")}
          onError={() => setStatus("error")}
          style={{
            width: "100%",
            height: "100%",
            objectFit: "contain",
            display: "block",
            background: "#0a0c10",
            opacity: status === "ok" ? 1 : 0,
            transition: "opacity 120ms ease-out",
          }}
        />
        <div style={{
          ...tileBadge,
          opacity: hover || status !== "ok" ? 0 : 1,
        }}>
          #{index + 1}
        </div>
        {status === "ok" && (
          <div style={{ ...tileHover, opacity: hover ? 1 : 0 }}>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                fileRef.current?.click();
              }}
              style={tileBtn}
              title="Replace this image with one from your computer"
            >
              Replace
            </button>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onDelete();
              }}
              disabled={!canDelete}
              style={{
                ...tileBtnDanger,
                opacity: canDelete ? 1 : 0.4,
                cursor: canDelete ? "pointer" : "not-allowed",
              }}
              title={canDelete ? "Remove this image" : "A scene needs at least one image"}
            >
              Delete
            </button>
          </div>
        )}
      </div>
      <div style={tileFooter}>
        <span style={{ fontSize: 11, color: status === "error" ? "#ffb4b4" : "#aaa" }}>
          Image #{index + 1}
          {status === "error" && " — missing"}
        </span>
        <span style={{ fontSize: 11, color: "#666" }}>
          {status === "error" ? "click ⚠ to upload" : "drag to replace"}
        </span>
      </div>
      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        style={{ display: "none" }}
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onReplace(f);
          e.target.value = "";
        }}
      />
    </div>
  );
}

function AppendTile({ onPick }: { onPick: (file: File) => void }) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [draggingOver, setDraggingOver] = useState(false);
  return (
    <button
      type="button"
      onClick={() => fileRef.current?.click()}
      onDragEnter={(e) => {
        e.preventDefault();
        setDraggingOver(true);
      }}
      onDragOver={(e) => {
        e.preventDefault();
        setDraggingOver(true);
      }}
      onDragLeave={() => setDraggingOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDraggingOver(false);
        const f = e.dataTransfer.files?.[0];
        if (f && f.type.startsWith("image/")) onPick(f);
      }}
      style={{
        ...appendTile,
        background: draggingOver ? "#172033" : "#0f1115",
        borderColor: draggingOver ? "#3b82f6" : "#3b82f6",
      }}
      title="Add an image to this segment"
    >
      <span style={{ fontSize: 22, lineHeight: 1 }}>+</span>
      <span style={{ fontSize: 12, marginTop: 4 }}>Add image</span>
      <span style={{ fontSize: 10, color: "#7a8db3", marginTop: 2 }}>
        click or drop a file
      </span>
      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        style={{ display: "none" }}
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onPick(f);
          e.target.value = "";
        }}
      />
    </button>
  );
}

interface LightboxProps {
  state: LightboxState;
  onClose: () => void;
  onNext: () => void;
  onPrev: () => void;
}

function Lightbox({ state, onClose, onNext, onPrev }: LightboxProps) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowRight") onNext();
      else if (e.key === "ArrowLeft") onPrev();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, onNext, onPrev]);

  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(0,0,0,0.85)",
        zIndex: 1000,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: "clamp(8px, 4vw, 32px)",
      }}
    >
      <img
        onClick={(e) => e.stopPropagation()}
        src={imageStreamUrl(state.jobId, state.sceneId, state.index, state.version)}
        alt={`${sceneLabel(state.sceneId)} image ${state.index + 1}`}
        style={{
          maxWidth: "100%",
          maxHeight: "100%",
          objectFit: "contain",
          borderRadius: 6,
          boxShadow: "0 8px 32px rgba(0,0,0,0.6)",
        }}
      />
      <button onClick={onClose} style={lightboxBtn} title="Close (Esc)">
        ✕
      </button>
      {state.total > 1 && (
        <>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onPrev();
            }}
            style={{ ...lightboxNav, left: 16 }}
            title="Previous (←)"
          >
            ‹
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onNext();
            }}
            style={{ ...lightboxNav, right: 16 }}
            title="Next (→)"
          >
            ›
          </button>
        </>
      )}
      <div
        style={{
          position: "absolute",
          bottom: 16,
          left: "50%",
          transform: "translateX(-50%)",
          color: "#cbd5e1",
          fontSize: 13,
          background: "rgba(0,0,0,0.6)",
          padding: "6px 12px",
          borderRadius: 999,
        }}
      >
        {sceneLabel(state.sceneId)} — image {state.index + 1} of {state.total}
      </div>
    </div>
  );
}

function sceneLabel(sceneId: number): string {
  if (sceneId === SEGMENT_TITLE) return "Title";
  if (sceneId === SEGMENT_HOOK) return "Hook";
  if (sceneId === SEGMENT_CLOSING) return "Closing";
  return `Scene ${sceneId}`;
}

/**
 * Replace asset-bearing fields (imageFiles / sourceVideoFiles / voiceFile /
 * videoFile / durationSeconds) from {@code refreshed}, but keep the
 * in-flight script edits the user has typed into title / hook / closing /
 * scene text — including the mediaMode they may have just toggled but not
 * saved yet.
 */
function mergeScriptAssets(local: VideoScript, refreshed: VideoScript): VideoScript {
  const refreshedScenesById = new Map<number, Scene>();
  (refreshed.scenes ?? []).forEach((s) => refreshedScenesById.set(s.scene, s));
  return {
    ...local,
    titleScene: refreshed.titleScene ?? local.titleScene,
    hookScene: refreshed.hookScene ?? local.hookScene,
    closingScene: refreshed.closingScene ?? local.closingScene,
    scenes: (local.scenes ?? []).map((s) => {
      const r = refreshedScenesById.get(s.scene);
      if (!r) return s;
      return {
        ...s,
        imageFiles: r.imageFiles,
        sourceVideoFiles: r.sourceVideoFiles,
        voiceFile: r.voiceFile,
        videoFile: r.videoFile,
      };
    }),
  };
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
  border: "1px solid #1f2125",
  borderRadius: 8,
  padding: 16,
};

const input: React.CSSProperties = {
  width: "100%",
  background: "#0f1115",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "10px 12px",
  color: "#e6e6e6",
  fontFamily: "inherit",
  fontSize: 16,
  boxSizing: "border-box",
};

const textarea: React.CSSProperties = {
  ...input,
  resize: "vertical",
  lineHeight: 1.5,
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

const btnAccent: React.CSSProperties = {
  background: "#22c55e",
  color: "#0a1f10",
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
  fontWeight: 600,
};

const tile: React.CSSProperties = {
  background: "#0f1115",
  border: "1px solid #2a2d33",
  borderRadius: 8,
  overflow: "hidden",
  transition: "border-color 120ms, box-shadow 120ms",
};

const tileImageWrap: React.CSSProperties = {
  position: "relative",
  width: "100%",
  height: 170,
  background: "#0a0c10",
  cursor: "zoom-in",
  overflow: "hidden",
};

const tileSkeleton: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  color: "#6b7280",
  fontSize: 12,
  background: "#0a0c10",
};

const tileBadge: React.CSSProperties = {
  position: "absolute",
  top: 6,
  left: 6,
  background: "rgba(0,0,0,0.6)",
  color: "#e6e6e6",
  fontSize: 11,
  padding: "2px 6px",
  borderRadius: 999,
  transition: "opacity 100ms",
};

const tileHover: React.CSSProperties = {
  position: "absolute",
  inset: 0,
  background: "linear-gradient(to top, rgba(0,0,0,0.7) 0%, rgba(0,0,0,0) 60%)",
  display: "flex",
  alignItems: "flex-end",
  justifyContent: "center",
  gap: 6,
  padding: 8,
  transition: "opacity 120ms",
  pointerEvents: "auto",
};

const tileFooter: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  padding: "6px 10px",
  background: "#15171b",
  borderTop: "1px solid #1f2125",
};

const tileBtn: React.CSSProperties = {
  background: "rgba(15,17,21,0.85)",
  color: "#9bb3ff",
  border: "1px solid #3b82f6",
  borderRadius: 4,
  padding: "4px 10px",
  cursor: "pointer",
  fontSize: 12,
  fontWeight: 600,
};

const tileBtnDanger: React.CSSProperties = {
  ...tileBtn,
  color: "#ff8b8b",
  borderColor: "#7a1f1f",
};

const appendTile: React.CSSProperties = {
  background: "#0f1115",
  border: "1px dashed #3b82f6",
  borderRadius: 8,
  color: "#9bb3ff",
  cursor: "pointer",
  fontSize: 13,
  height: 200,
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  transition: "background 120ms, border-color 120ms",
};

const emptyImagesBlock: React.CSSProperties = {
  marginTop: 14,
  padding: 14,
  background: "#0f1115",
  border: "1px dashed #2a2d33",
  borderRadius: 8,
};

const lightboxBtn: React.CSSProperties = {
  position: "absolute",
  top: 16,
  right: 16,
  background: "rgba(0,0,0,0.6)",
  color: "#fff",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  width: 36,
  height: 36,
  cursor: "pointer",
  fontSize: 16,
};

const lightboxNav: React.CSSProperties = {
  position: "absolute",
  top: "50%",
  transform: "translateY(-50%)",
  background: "rgba(0,0,0,0.5)",
  color: "#fff",
  border: "1px solid #2a2d33",
  borderRadius: 999,
  width: 44,
  height: 44,
  cursor: "pointer",
  fontSize: 22,
  fontWeight: 700,
};

function Label({ children }: { children: React.ReactNode }) {
  return <div style={{ fontSize: 12, color: "#888", marginBottom: 6 }}>{children}</div>;
}