import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  assetStreamUrl,
  deleteAsset,
  listAssets,
  publishAsset,
  uploadAsset,
} from "../api/assets";
import { listConnections } from "../api/social";
import { Pagination } from "../components/Pagination";
import { PublishModal } from "../components/PublishModal";
import type {
  AssetSummaryDTO,
  AssetType,
  SocialConnectionDTO,
  SocialPlatform,
  VideoPublishResult,
} from "../types/api";

const PAGE_SIZE = 24;

type Filter = "ALL" | AssetType;

interface FilterOption {
  value: Filter;
  label: string;
}

const FILTERS: FilterOption[] = [
  { value: "ALL", label: "All" },
  { value: "IMAGE", label: "Images" },
  { value: "SOURCE_VIDEO", label: "Source videos" },
  { value: "VIDEO_CLIP", label: "Rendered clips" },
];

const UPLOAD_OPTIONS: { type: AssetType; label: string; accept: string }[] = [
  { type: "IMAGE", label: "Image", accept: "image/*" },
  { type: "SOURCE_VIDEO", label: "Source video", accept: "video/*" },
  { type: "VOICE", label: "Voice", accept: "audio/*" },
  { type: "MUSIC", label: "Music", accept: "audio/*" },
];

export function AssetsPage() {
  const [assets, setAssets] = useState<AssetSummaryDTO[]>([]);
  const [filter, setFilter] = useState<Filter>("ALL");
  const [page, setPage] = useState(0);
  const [totalItems, setTotalItems] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [connections, setConnections] = useState<SocialConnectionDTO[]>([]);

  // Connections are stable for the session, so fetch once. Used by the
  // per-clip publish flow inside AssetCard.
  useEffect(() => {
    let cancelled = false;
    listConnections()
      .then((c) => {
        if (!cancelled) setConnections(c);
      })
      .catch(() => {
        // Non-fatal: an empty connections list just disables the publish UI.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const reload = useCallback(async (active: Filter, p: number) => {
    setLoading(true);
    setError(null);
    try {
      const data = await listAssets({
        type: active === "ALL" ? undefined : active,
        page: p,
        size: PAGE_SIZE,
      });
      setAssets(data.items);
      setTotalItems(data.totalItems);
      setTotalPages(Math.max(1, data.totalPages));
      // Clamp the page state if the server snapped us back (e.g. when the
      // last page emptied after a delete).
      if (data.page !== p) setPage(data.page);
    } catch (err) {
      setError(extractError(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload(filter, page);
  }, [filter, page, reload]);

  // Reset to the first page when the filter changes so we don't land on a
  // page that doesn't exist for the new type.
  const onFilterChange = (next: Filter) => {
    setFilter(next);
    setPage(0);
  };

  const handleUpload = async (type: AssetType, file: File) => {
    setUploading(true);
    setMessage(null);
    setError(null);
    try {
      await uploadAsset(type, file);
      setMessage(`Uploaded ${file.name} to your library.`);
      setUploadOpen(false);
      // Drop to page 0 so the new asset is visible (it's the newest, and
      // results are sorted createdOn DESC).
      if (page !== 0) setPage(0);
      else await reload(filter, 0);
    } catch (err) {
      setError(extractError(err));
    } finally {
      setUploading(false);
    }
  };

  const handleDelete = async (asset: AssetSummaryDTO) => {
    if (asset.jobId) {
      setError(
        "Job-bound assets can't be deleted here — open the job and remove the asset from the scene editor.",
      );
      return;
    }
    if (!confirm("Delete this asset from your library?")) return;
    setDeleting(asset.id);
    setMessage(null);
    setError(null);
    try {
      await deleteAsset(asset.id);
      setMessage("Asset deleted.");
      // If we just emptied the current page, step back one.
      if (assets.length === 1 && page > 0) setPage(page - 1);
      else await reload(filter, page);
    } catch (err) {
      setError(extractError(err));
    } finally {
      setDeleting(null);
    }
  };

  return (
    <div>
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          marginBottom: 16,
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <h2 style={{ margin: 0 }}>Asset library</h2>
        <button type="button" style={btnPrimary} onClick={() => setUploadOpen(true)}>
          + Upload asset
        </button>
      </div>
      <p style={{ color: "#888", fontSize: 13, marginTop: 0, marginBottom: 16 }}>
        Every asset you generate while creating videos plus anything you upload here. Reuse them
        directly from any scene's media editor.
      </p>

      <div style={filterRow} role="tablist" aria-label="Filter by asset type">
        {FILTERS.map((f) => {
          const active = filter === f.value;
          return (
            <button
              key={f.value}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => onFilterChange(f.value)}
              style={{
                ...chip,
                background: active ? "#1d4ed8" : "transparent",
                color: active ? "#fff" : "#cbd5e1",
                borderColor: active ? "#3b82f6" : "#2a2d33",
              }}
            >
              {f.label}
              {active && totalItems > 0 ? ` (${totalItems})` : ""}
            </button>
          );
        })}
      </div>

      {message && (
        <div style={{ ...flash, color: "#4ade80", borderColor: "#15803d" }}>{message}</div>
      )}
      {error && (
        <div style={{ ...flash, color: "#ff6b6b", borderColor: "#7a1f1f" }}>{error}</div>
      )}

      {loading ? (
        <div style={{ ...card, color: "#aaa" }}>Loading assets…</div>
      ) : assets.length === 0 ? (
        <div style={{ ...card, textAlign: "center", padding: 48 }}>
          <p style={{ color: "#aaa", marginBottom: 16 }}>
            No assets here yet. Generate some by running a job, or upload one directly.
          </p>
          <button type="button" style={btnPrimary} onClick={() => setUploadOpen(true)}>
            + Upload your first asset
          </button>
        </div>
      ) : (
        <>
          <div className="image-grid">
            {assets.map((a) => (
              <AssetCard
                key={a.id}
                asset={a}
                connections={connections}
                isDeleting={deleting === a.id}
                onDelete={() => handleDelete(a)}
              />
            ))}
          </div>
          <Pagination
            page={page}
            totalPages={totalPages}
            totalItems={totalItems}
            pageSize={PAGE_SIZE}
            onPageChange={setPage}
          />
        </>
      )}

      {uploadOpen && (
        <UploadModal
          uploading={uploading}
          onCancel={() => setUploadOpen(false)}
          onUpload={handleUpload}
        />
      )}
    </div>
  );
}

interface AssetCardProps {
  asset: AssetSummaryDTO;
  connections: SocialConnectionDTO[];
  isDeleting: boolean;
  onDelete: () => void;
}

function AssetCard({ asset, connections, isDeleting, onDelete }: AssetCardProps) {
  const isImage = asset.assetType === "IMAGE" || asset.assetType === "THUMBNAIL";
  const isVideo = asset.assetType === "SOURCE_VIDEO" || asset.assetType === "VIDEO_CLIP";
  const isAudio = asset.assetType === "VOICE" || asset.assetType === "MUSIC";
  // Only rendered clips can be published as standalone uploads — source
  // videos are job inputs and don't carry user-facing metadata.
  const canPublish = asset.assetType === "VIDEO_CLIP";
  const src = assetStreamUrl(asset.id);
  const canDelete = !asset.jobId;

  const [publishOpen, setPublishOpen] = useState(false);
  const [selected, setSelected] = useState<Set<SocialPlatform>>(new Set());
  const [publishing, setPublishing] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);
  const [results, setResults] = useState<VideoPublishResult[]>([]);

  const togglePlatform = (p: SocialPlatform) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(p)) next.delete(p);
      else next.add(p);
      return next;
    });
  };

  const handlePublish = async () => {
    if (selected.size === 0) return;
    setPublishing(true);
    setPublishError(null);
    try {
      const response = await publishAsset(asset.id, Array.from(selected));
      setResults(response.results);
    } catch (err) {
      setPublishError(extractError(err));
    } finally {
      setPublishing(false);
    }
  };

  return (
    <div style={tile}>
      <div style={tileImageWrap}>
        {isImage && <AssetImage src={src} alt={`Asset ${asset.id}`} />}
        {isVideo && (
          <video
            src={src}
            preload="metadata"
            controls
            style={{ width: "100%", height: "100%", objectFit: "contain", background: "#000" }}
          />
        )}
        {isAudio && (
          <div style={audioBlock}>
            <div style={{ fontSize: 32, color: "#9bb3ff", marginBottom: 8 }}>♪</div>
            <audio src={src} controls style={{ width: "100%" }} />
          </div>
        )}
        <div style={tileBadge}>{prettyType(asset.assetType)}</div>
      </div>
      <div style={{ padding: 10, display: "grid", gap: 6 }}>
        <div style={{ fontSize: 12, color: "#aaa" }}>
          {asset.jobId ? (
            <>
              From job:{" "}
              <Link to={`/jobs/${asset.jobId}`} style={{ color: "#9bb3ff" }}>
                {asset.jobTitle ?? "(untitled)"}
              </Link>
            </>
          ) : (
            <span style={{ color: "#86efac" }}>Library upload</span>
          )}
        </div>
        <div style={{ fontSize: 11, color: "#666" }}>{formatDate(asset.createdAt)}</div>
        <div style={{ display: "flex", gap: 6, marginTop: 4, flexWrap: "wrap" }}>
          <a
            href={src}
            target="_blank"
            rel="noreferrer"
            style={tileBtn}
            title="Open the asset in a new tab"
          >
            Open
          </a>
          {canPublish && (
            <button
              type="button"
              onClick={() => setPublishOpen((o) => !o)}
              style={tileBtn}
              title="Upload this clip to a connected social account"
            >
              {publishOpen ? "Close" : "⬆ Upload"}
            </button>
          )}
          <button
            type="button"
            onClick={onDelete}
            disabled={!canDelete || isDeleting}
            style={{
              ...tileBtnDanger,
              opacity: canDelete && !isDeleting ? 1 : 0.4,
              cursor: canDelete && !isDeleting ? "pointer" : "not-allowed",
            }}
            title={
              canDelete
                ? "Remove this asset from your library"
                : "Remove this asset from the scene editor in its job"
            }
          >
            {isDeleting ? "Deleting…" : "Delete"}
          </button>
        </div>
      </div>
      {canPublish && publishOpen && (
        <PublishModal
          title={asset.jobTitle ?? "Rendered clip"}
          connections={connections}
          selected={selected}
          onToggle={togglePlatform}
          onPublish={handlePublish}
          publishing={publishing}
          error={publishError}
          results={results}
          onClose={() => setPublishOpen(false)}
        />
      )}
    </div>
  );
}


function AssetImage({ src, alt }: { src: string; alt: string }) {
  const [status, setStatus] = useState<"loading" | "ok" | "error">("loading");
  return (
    <>
      {status !== "ok" && (
        <div style={{ ...tileSkeleton, position: "absolute", inset: 0 }}>
          {status === "error" ? "Missing from storage" : "Loading…"}
        </div>
      )}
      <img
        src={src}
        alt={alt}
        onLoad={() => setStatus("ok")}
        onError={() => setStatus("error")}
        style={{
          width: "100%",
          height: "100%",
          objectFit: "contain",
          background: "#0a0c10",
          opacity: status === "ok" ? 1 : 0,
          transition: "opacity 120ms ease-out",
        }}
      />
    </>
  );
}

interface UploadModalProps {
  uploading: boolean;
  onCancel: () => void;
  onUpload: (type: AssetType, file: File) => void;
}

function UploadModal({ uploading, onCancel, onUpload }: UploadModalProps) {
  const [type, setType] = useState<AssetType>("IMAGE");
  const fileRef = useRef<HTMLInputElement>(null);

  const accept = useMemo(
    () => UPLOAD_OPTIONS.find((o) => o.type === type)?.accept ?? "*/*",
    [type],
  );

  const handlePick = () => fileRef.current?.click();

  return (
    <div style={modalBackdrop} onClick={onCancel}>
      <div style={modalCard} onClick={(e) => e.stopPropagation()}>
        <h3 style={{ marginTop: 0 }}>Upload asset</h3>
        <p style={{ color: "#aaa", fontSize: 13, marginTop: 0 }}>
          Add a file directly to your library. You'll be able to drop it into any scene from
          the job editor.
        </p>
        <label style={{ display: "block", fontSize: 12, color: "#888", marginBottom: 6 }}>
          Asset type
        </label>
        <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginBottom: 16 }}>
          {UPLOAD_OPTIONS.map((o) => {
            const active = type === o.type;
            return (
              <button
                key={o.type}
                type="button"
                onClick={() => setType(o.type)}
                style={{
                  ...chip,
                  background: active ? "#1d4ed8" : "transparent",
                  color: active ? "#fff" : "#cbd5e1",
                  borderColor: active ? "#3b82f6" : "#2a2d33",
                }}
              >
                {o.label}
              </button>
            );
          })}
        </div>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <button type="button" onClick={onCancel} style={btnSecondary} disabled={uploading}>
            Cancel
          </button>
          <button
            type="button"
            onClick={handlePick}
            style={btnPrimary}
            disabled={uploading}
          >
            {uploading ? "Uploading…" : "Pick file & upload"}
          </button>
        </div>
        <input
          ref={fileRef}
          type="file"
          accept={accept}
          style={{ display: "none" }}
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) onUpload(type, f);
            e.target.value = "";
          }}
        />
      </div>
    </div>
  );
}

function prettyType(t: AssetType): string {
  switch (t) {
    case "IMAGE":
      return "Image";
    case "SOURCE_VIDEO":
      return "Source video";
    case "VIDEO_CLIP":
      return "Rendered clip";
    case "VOICE":
      return "Voice";
    case "MUSIC":
      return "Music";
    case "THUMBNAIL":
      return "Thumbnail";
  }
}

function formatDate(value: string | null | undefined): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  const dd = String(d.getDate()).padStart(2, "0");
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const yyyy = d.getFullYear();
  return `${dd}-${mm}-${yyyy}`;
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

const tile: React.CSSProperties = {
  background: "#0f1115",
  border: "1px solid #2a2d33",
  borderRadius: 8,
  overflow: "hidden",
  display: "flex",
  flexDirection: "column",
};

const tileImageWrap: React.CSSProperties = {
  position: "relative",
  width: "100%",
  height: 180,
  background: "#0a0c10",
};

const tileSkeleton: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  color: "#6b7280",
  fontSize: 12,
};

const tileBadge: React.CSSProperties = {
  position: "absolute",
  top: 6,
  left: 6,
  background: "rgba(0,0,0,0.7)",
  color: "#e6e6e6",
  fontSize: 11,
  padding: "2px 8px",
  borderRadius: 999,
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
  textDecoration: "none",
  display: "inline-block",
};

const tileBtnDanger: React.CSSProperties = {
  ...tileBtn,
  color: "#ff8b8b",
  borderColor: "#7a1f1f",
};

const audioBlock: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  width: "100%",
  height: "100%",
  background: "#0a0c10",
  padding: 12,
  boxSizing: "border-box",
};

const filterRow: React.CSSProperties = {
  display: "flex",
  gap: 6,
  flexWrap: "wrap",
  marginBottom: 16,
};

const chip: React.CSSProperties = {
  border: "1px solid #2a2d33",
  borderRadius: 999,
  padding: "5px 12px",
  cursor: "pointer",
  fontSize: 12,
  fontWeight: 600,
};

const flash: React.CSSProperties = {
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "8px 12px",
  fontSize: 13,
  marginBottom: 12,
};

const btnPrimary: React.CSSProperties = {
  background: "#3b82f6",
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "8px 14px",
  cursor: "pointer",
  fontWeight: 600,
};

const btnSecondary: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "8px 14px",
  cursor: "pointer",
  fontWeight: 600,
};

const modalBackdrop: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "rgba(0,0,0,0.7)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  zIndex: 1000,
  padding: 24,
};

const modalCard: React.CSSProperties = {
  ...card,
  width: "min(480px, 100%)",
  background: "#15171b",
  border: "1px solid #2a2d33",
};