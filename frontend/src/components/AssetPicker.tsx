import { useCallback, useEffect, useState } from "react";
import { assetStreamUrl, listAssets } from "../api/assets";
import type { AssetSummaryDTO, AssetType } from "../types/api";

interface AssetPickerProps {
  /**
   * Which asset type to show in the picker. Currently only {@code IMAGE} and
   * {@code SOURCE_VIDEO} are reusable in a scene; pass one of those.
   */
  type: AssetType;
  /**
   * Hide assets that already live on this job — picking one of your own
   * scene's images and dropping it back in is rarely what you want.
   */
  excludeJobId?: string;
  onCancel: () => void;
  onPick: (asset: AssetSummaryDTO) => void;
}

export function AssetPicker({ type, excludeJobId, onCancel, onPick }: AssetPickerProps) {
  const [assets, setAssets] = useState<AssetSummaryDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [picking, setPicking] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listAssets(type);
      setAssets(data.filter((a) => a.jobId !== excludeJobId));
    } catch (err) {
      setError(extractError(err));
    } finally {
      setLoading(false);
    }
  }, [type, excludeJobId]);

  useEffect(() => {
    load();
  }, [load]);

  const isImage = type === "IMAGE";

  const handlePick = async (a: AssetSummaryDTO) => {
    setPicking(a.id);
    try {
      await onPick(a);
    } finally {
      setPicking(null);
    }
  };

  return (
    <div style={backdrop} onClick={onCancel}>
      <div style={dialog} onClick={(e) => e.stopPropagation()}>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            marginBottom: 12,
            gap: 8,
          }}
        >
          <h3 style={{ margin: 0 }}>
            Pick {isImage ? "an image" : "a video"} from your library
          </h3>
          <button type="button" onClick={onCancel} style={closeBtn} title="Close">
            ✕
          </button>
        </div>

        {loading ? (
          <div style={{ color: "#aaa" }}>Loading…</div>
        ) : error ? (
          <div style={{ color: "#ff6b6b" }}>{error}</div>
        ) : assets.length === 0 ? (
          <div style={{ color: "#aaa", padding: 24, textAlign: "center" }}>
            <p>You don't have any {isImage ? "images" : "source videos"} in your library yet.</p>
            <p style={{ fontSize: 12, color: "#888" }}>
              Run a job to generate some, or upload one from the Assets page.
            </p>
          </div>
        ) : (
          <div className="image-grid">
            {assets.map((a) => (
              <button
                key={a.id}
                type="button"
                onClick={() => handlePick(a)}
                disabled={picking !== null}
                style={{
                  ...tile,
                  cursor: picking !== null ? "wait" : "pointer",
                  opacity: picking && picking !== a.id ? 0.4 : 1,
                  border: "1px solid #2a2d33",
                  textAlign: "left",
                  padding: 0,
                  background: "#0f1115",
                  color: "inherit",
                }}
                title="Use this asset in the scene"
              >
                <div style={preview}>
                  {isImage ? (
                    <img
                      src={assetStreamUrl(a.id)}
                      alt=""
                      style={{
                        width: "100%",
                        height: "100%",
                        objectFit: "contain",
                        background: "#0a0c10",
                      }}
                    />
                  ) : (
                    <video
                      src={assetStreamUrl(a.id)}
                      preload="metadata"
                      muted
                      style={{
                        width: "100%",
                        height: "100%",
                        objectFit: "contain",
                        background: "#000",
                      }}
                    />
                  )}
                </div>
                <div style={{ padding: 8, fontSize: 11, color: "#aaa" }}>
                  {a.jobId ? `From: ${a.jobTitle ?? "(untitled)"}` : "Library upload"}
                </div>
                {picking === a.id && (
                  <div style={busyBadge}>Adding to scene…</div>
                )}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function extractError(err: unknown): string {
  if (typeof err === "object" && err !== null && "response" in err) {
    const r = (err as { response?: { data?: { message?: string } } }).response;
    if (r?.data?.message) return r.data.message;
  }
  return err instanceof Error ? err.message : "Could not load library.";
}

const backdrop: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "rgba(0,0,0,0.7)",
  zIndex: 1000,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  padding: 24,
};

const dialog: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #2a2d33",
  borderRadius: 8,
  padding: 16,
  width: "min(960px, 100%)",
  maxHeight: "85vh",
  overflowY: "auto",
};

const closeBtn: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  width: 32,
  height: 32,
  cursor: "pointer",
};

const tile: React.CSSProperties = {
  background: "#0f1115",
  border: "1px solid #2a2d33",
  borderRadius: 8,
  overflow: "hidden",
  position: "relative",
};

const preview: React.CSSProperties = {
  width: "100%",
  height: 140,
  background: "#0a0c10",
};

const busyBadge: React.CSSProperties = {
  position: "absolute",
  inset: 0,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  background: "rgba(0,0,0,0.6)",
  color: "#fff",
  fontSize: 12,
  fontWeight: 600,
};