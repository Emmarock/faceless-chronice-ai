import { UploadPanel } from "./UploadPanel";
import type {
  SocialConnectionDTO,
  SocialPlatform,
  VideoPublishResult,
} from "../types/api";

interface PublishModalProps {
  /** Shown in the modal subhead — e.g. the video / clip title. */
  title: string;
  connections: SocialConnectionDTO[];
  selected: Set<SocialPlatform>;
  onToggle: (p: SocialPlatform) => void;
  onPublish: () => void;
  publishing: boolean;
  error: string | null;
  results: VideoPublishResult[];
  onClose: () => void;
}

/**
 * Centered modal that hosts the {@link UploadPanel}. Used by both the video
 * list and the asset library so the publish UX is the same everywhere.
 * Backdrop click and the ✕ button both close.
 */
export function PublishModal({
  title,
  connections,
  selected,
  onToggle,
  onPublish,
  publishing,
  error,
  results,
  onClose,
}: PublishModalProps) {
  return (
    <div style={backdrop} onClick={onClose}>
      <div style={card} onClick={(e) => e.stopPropagation()}>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 12,
            marginBottom: 4,
          }}
        >
          <h3 style={{ margin: 0 }}>Publish</h3>
          <button type="button" onClick={onClose} style={closeBtn} title="Close">
            ✕
          </button>
        </div>
        <p style={{ color: "#aaa", fontSize: 13, marginTop: 4, marginBottom: 0 }}>
          Pick the connected accounts to upload <strong>{title}</strong> to.
        </p>
        <UploadPanel
          connections={connections}
          selected={selected}
          onToggle={onToggle}
          onPublish={onPublish}
          publishing={publishing}
          error={error}
          results={results}
        />
      </div>
    </div>
  );
}

const backdrop: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "rgba(0,0,0,0.7)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  zIndex: 1000,
  padding: 24,
};

const card: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #2a2d33",
  borderRadius: 8,
  padding: 16,
  width: "min(480px, 100%)",
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