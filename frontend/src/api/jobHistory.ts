import type { JobHistoryEntry } from "../types/api";

const KEY = "fc.jobHistory";

export function getJobHistory(): JobHistoryEntry[] {
  const raw = localStorage.getItem(KEY);
  if (!raw) return [];
  try {
    return JSON.parse(raw) as JobHistoryEntry[];
  } catch {
    return [];
  }
}

export function addJobToHistory(entry: JobHistoryEntry): void {
  const existing = getJobHistory().filter((e) => e.jobId !== entry.jobId);
  const updated = [entry, ...existing].slice(0, 50);
  localStorage.setItem(KEY, JSON.stringify(updated));
}

export function clearJobHistory(): void {
  localStorage.removeItem(KEY);
}