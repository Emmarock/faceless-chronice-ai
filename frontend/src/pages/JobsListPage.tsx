import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { listJobProgress, listJobs } from "../api/jobs";
import { ProgressBar } from "../components/ProgressBar";
import type { JobProgressDTO, JobSummaryDTO } from "../types/api";

// How often to re-fetch progress while at least one job is still running.
// Tuned to feel responsive without hammering the API — the endpoint is cheap
// (single SELECT per job) but every active tab on every device will hit it.
const POLL_INTERVAL_MS = 4000;

function isTerminal(status?: string | null): boolean {
  return status === "COMPLETED" || status === "FAILED";
}

const PAGE_SIZE = 10;

export function JobsListPage() {
  const [jobs, setJobs] = useState<JobSummaryDTO[]>([]);
  const [progressMap, setProgressMap] = useState<Map<string, JobProgressDTO>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  // Mounted ref keeps the poll loop from updating state after unmount.
  const mountedRef = useRef(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    listJobs()
      .then((data) => {
        if (!cancelled) setJobs(data);
      })
      .catch((err) => {
        if (!cancelled) setError(extractError(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Progress polling — runs as long as at least one row in the current list
  // is still active. Stops itself once every known job reaches a terminal
  // state (COMPLETED / FAILED) so an idle dashboard doesn't churn forever.
  useEffect(() => {
    mountedRef.current = true;
    if (jobs.length === 0) return;
    if (jobs.every((j) => isTerminal(j.status))) return;

    let timer: ReturnType<typeof setTimeout> | null = null;
    let stopped = false;

    const tick = async () => {
      try {
        const list = await listJobProgress();
        if (!mountedRef.current) return;
        setProgressMap((prev) => {
          const next = new Map(prev);
          for (const p of list) next.set(p.jobId, p);
          return next;
        });
        const byId = new Map(list.map((p) => [p.jobId, p]));
        if (jobs.every((j) => isTerminal(byId.get(j.jobId)?.status ?? j.status))) {
          stopped = true;
        }
      } catch {
        // Swallow: a transient network blip shouldn't tear down the page.
        // The next tick will recover.
      }
      if (!mountedRef.current || stopped) return;
      timer = setTimeout(tick, POLL_INTERVAL_MS);
    };
    timer = setTimeout(tick, POLL_INTERVAL_MS);
    return () => {
      mountedRef.current = false;
      if (timer) clearTimeout(timer);
    };
  }, [jobs]);

  const totalPages = Math.max(1, Math.ceil(jobs.length / PAGE_SIZE));
  // Clamp the current page when the list shrinks (e.g. after a refetch).
  const safePage = Math.min(page, totalPages);
  const pageJobs = useMemo(() => {
    const start = (safePage - 1) * PAGE_SIZE;
    return jobs.slice(start, start + PAGE_SIZE);
  }, [jobs, safePage]);

  return (
    <div>
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          marginBottom: 24,
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <h2 style={{ margin: 0 }}>Your contents</h2>
        <Link to="/jobs/new" style={btnPrimary}>
          + New content
        </Link>
      </div>

      {loading ? (
        <div style={{ ...card, color: "#aaa" }}>Loading...</div>
      ) : error ? (
        <div style={{ ...card, color: "#ff6b6b" }}>{error}</div>
      ) : jobs.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <div style={{ display: "grid", gap: 12 }}>
            {pageJobs.map((job) => {
              const live = progressMap.get(job.jobId);
              const status = live?.status ?? job.status ?? null;
              const progress = live?.progress ?? job.progress ?? 0;
              const stage = live?.stage ?? null;
              // Completed jobs jump straight to a single-video page that
              // fetches only the rendered video for this job; everything else
              // still goes to the detail page where the user can watch
              // progress / inspect failures.
              const target =
                status === "COMPLETED" ? `/videos/job/${job.jobId}` : `/jobs/${job.jobId}`;
              return (
                <Link
                  key={job.jobId}
                  to={target}
                  style={{
                    ...card,
                    textDecoration: "none",
                    color: "inherit",
                    display: "block",
                  }}
                >
                  <div style={{ fontSize: 12, color: "#888", marginBottom: 6 }}>
                    {formatDate(job.createdAt)}
                  </div>
                  <div style={{ fontWeight: 600, fontSize: 16, marginBottom: 10 }}>
                    {job.title?.trim() ? job.title : <span style={{ color: "#888" }}>(untitled)</span>}
                  </div>
                  <ProgressBar progress={progress} status={status} stage={stage} compact />
                </Link>
              );
            })}
          </div>

          <Pagination
            page={safePage}
            totalPages={totalPages}
            totalItems={jobs.length}
            pageSize={PAGE_SIZE}
            onPageChange={setPage}
          />
        </>
      )}
    </div>
  );
}

interface PaginationProps {
  page: number;
  totalPages: number;
  totalItems: number;
  pageSize: number;
  onPageChange: (page: number) => void;
}

function Pagination({ page, totalPages, totalItems, pageSize, onPageChange }: PaginationProps) {
  if (totalPages <= 1) return null;

  const start = (page - 1) * pageSize + 1;
  const end = Math.min(page * pageSize, totalItems);

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
          disabled={page <= 1}
          style={{ ...btnPager, opacity: page <= 1 ? 0.4 : 1, cursor: page <= 1 ? "not-allowed" : "pointer" }}
        >
          ‹ Prev
        </button>
        <span style={{ fontSize: 12, color: "#aaa", minWidth: 80, textAlign: "center" }}>
          Page {page} of {totalPages}
        </span>
        <button
          type="button"
          onClick={() => onPageChange(page + 1)}
          disabled={page >= totalPages}
          style={{ ...btnPager, opacity: page >= totalPages ? 0.4 : 1, cursor: page >= totalPages ? "not-allowed" : "pointer" }}
        >
          Next ›
        </button>
      </div>
    </div>
  );
}

function EmptyState() {
  return (
    <div style={{ ...card, textAlign: "center", padding: 48 }}>
      <p style={{ color: "#aaa", marginBottom: 16 }}>You haven't created any content yet.</p>
      <Link to="/jobs/new" style={btnPrimary}>
        Create your first content
      </Link>
    </div>
  );
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
  return err instanceof Error ? err.message : "Could not load contents.";
}

const card: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #1f2125",
  borderRadius: 8,
  padding: 16,
};

const btnPrimary: React.CSSProperties = {
  background: "#3b82f6",
  color: "#fff",
  border: "none",
  borderRadius: 6,
  padding: "8px 14px",
  cursor: "pointer",
  fontWeight: 600,
  textDecoration: "none",
  display: "inline-block",
};

const btnPager: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "6px 12px",
  fontSize: 13,
};