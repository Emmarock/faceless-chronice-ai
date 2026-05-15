interface PaginationProps {
  /** Zero-indexed current page. */
  page: number;
  totalPages: number;
  totalItems: number;
  pageSize: number;
  onPageChange: (page: number) => void;
}

/**
 * Simple prev/next pager with a "showing A–B of T" label. Hides itself when
 * there's only one page so callers can render unconditionally.
 */
export function Pagination({ page, totalPages, totalItems, pageSize, onPageChange }: PaginationProps) {
  if (totalPages <= 1) return null;

  const start = page * pageSize + 1;
  const end = Math.min((page + 1) * pageSize, totalItems);

  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        marginTop: 20,
        gap: 12,
        flexWrap: "wrap",
      }}
    >
      <span style={{ fontSize: 12, color: "#888" }}>
        Showing {start}–{end} of {totalItems}
      </span>
      <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
        <button
          type="button"
          onClick={() => onPageChange(page - 1)}
          disabled={page <= 0}
          style={{
            ...btnPager,
            opacity: page <= 0 ? 0.4 : 1,
            cursor: page <= 0 ? "not-allowed" : "pointer",
          }}
        >
          ‹ Prev
        </button>
        <span style={{ fontSize: 12, color: "#aaa", minWidth: 80, textAlign: "center" }}>
          Page {page + 1} of {totalPages}
        </span>
        <button
          type="button"
          onClick={() => onPageChange(page + 1)}
          disabled={page >= totalPages - 1}
          style={{
            ...btnPager,
            opacity: page >= totalPages - 1 ? 0.4 : 1,
            cursor: page >= totalPages - 1 ? "not-allowed" : "pointer",
          }}
        >
          Next ›
        </button>
      </div>
    </div>
  );
}

const btnPager: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "6px 12px",
  fontSize: 13,
};