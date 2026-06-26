import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listJobs } from "../api/jobs";
import { listConnections } from "../api/social";
import { listLessons, listTwins } from "../api/tutor";
import { useAuth } from "../context/AuthContext";
import type { JobSummaryDTO, LessonDTO, SocialConnectionDTO, TwinDTO } from "../types/api";

/**
 * First-run home / launchpad. Orients a brand-new user: a short setup
 * checklist that reflects real account state, the two core "create" actions
 * front-and-centre, and a peek at recent work. Once the account is set up the
 * checklist collapses so returning users go straight to their content.
 */
export function DashboardPage() {
  const { user } = useAuth();
  const [jobs, setJobs] = useState<JobSummaryDTO[]>([]);
  const [lessons, setLessons] = useState<LessonDTO[]>([]);
  const [twins, setTwins] = useState<TwinDTO[]>([]);
  const [connections, setConnections] = useState<SocialConnectionDTO[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([listJobs(), listLessons(), listTwins(), listConnections()]).then((res) => {
      if (cancelled) return;
      if (res[0].status === "fulfilled") setJobs(res[0].value);
      if (res[1].status === "fulfilled") setLessons(res[1].value);
      if (res[2].status === "fulfilled") setTwins(res[2].value);
      if (res[3].status === "fulfilled") setConnections(res[3].value);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const firstName = (user?.name ?? user?.email ?? "").split(/[\s@]/)[0];

  const hasConnection = connections.length > 0;
  const hasContent = jobs.length > 0;
  const hasTwin = twins.some((t) => t.ready);

  const steps = [
    {
      done: hasContent,
      title: "Create your first video",
      desc: "Turn any topic into a narrated, faceless video.",
      to: "/jobs/new",
      cta: "Create",
    },
    {
      done: hasTwin,
      title: "Train your AI tutor twin",
      desc: "Record a short clip and your avatar can teach any topic in your voice.",
      to: "/tutor",
      cta: "Train",
    },
    {
      done: hasConnection,
      title: "Connect a social account",
      desc: "Link Facebook, YouTube, Instagram and more so you can publish in one click.",
      to: "/connections",
      cta: "Connect",
    },
  ];
  const doneCount = steps.filter((s) => s.done).length;
  const allSetUp = doneCount === steps.length;

  return (
    <div>
      <div style={{ marginBottom: 28 }}>
        <h2 style={{ margin: 0, fontSize: 26 }}>
          {firstName ? `Welcome back, ${firstName}` : "Welcome"} 👋
        </h2>
        <p style={{ color: "var(--text-muted)", marginTop: 8, marginBottom: 0, fontSize: 15 }}>
          Create faceless videos and AI-tutor lessons, then publish them across your social
          accounts — all from one place.
        </p>
      </div>

      {/* Primary actions — always visible so the two ways to create are obvious. */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))", gap: 16 }}>
        <ActionCard
          to="/jobs/new"
          emoji="🎬"
          title="Faceless video"
          desc="Type a topic — we write the script, generate visuals and voice, and render a video."
          cta="Create a video"
        />
        <ActionCard
          to="/tutor"
          emoji="🧑‍🏫"
          title="AI Tutor twin"
          desc="Your AI twin teaches any topic on camera, in your own likeness and voice."
          cta="Open AI Tutor"
        />
      </div>

      {/* Setup checklist — only while the account isn't fully set up. */}
      {!loading && !allSetUp && (
        <div style={{ ...card, marginTop: 24 }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 4 }}>
            <h3 style={{ margin: 0 }}>Get started</h3>
            <span style={{ color: "var(--text-dim)", fontSize: 13 }}>
              {doneCount} of {steps.length} done
            </span>
          </div>
          <div style={{ height: 6, background: "#0f1115", borderRadius: 999, overflow: "hidden", margin: "10px 0 16px" }}>
            <div style={{ width: `${(doneCount / steps.length) * 100}%`, height: "100%", background: "#3b82f6", transition: "width 0.3s" }} />
          </div>
          <div style={{ display: "grid", gap: 10 }}>
            {steps.map((s) => (
              <StepRow key={s.title} {...s} />
            ))}
          </div>
        </div>
      )}

      {/* Recent work. */}
      {!loading && (hasContent || lessons.length > 0) && (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))", gap: 16, marginTop: 24 }}>
          {hasContent && (
            <RecentPanel title="Recent videos" allHref="/contents" allLabel="All content">
              {jobs.slice(0, 4).map((j) => (
                <RecentRow
                  key={j.jobId}
                  to={j.status === "COMPLETED" ? `/videos/job/${j.jobId}` : `/jobs/${j.jobId}`}
                  label={j.title?.trim() ? j.title : j.question?.trim() ? j.question : "(untitled)"}
                  meta={j.status ?? undefined}
                />
              ))}
            </RecentPanel>
          )}
          {lessons.length > 0 && (
            <RecentPanel title="Recent lessons" allHref="/tutor" allLabel="AI Tutor">
              {lessons.slice(0, 4).map((l) => (
                <RecentRow key={l.id} to={`/tutor/lessons/${l.id}`} label={l.topic} meta={l.status} />
              ))}
            </RecentPanel>
          )}
        </div>
      )}
    </div>
  );
}

function ActionCard({ to, emoji, title, desc, cta }: { to: string; emoji: string; title: string; desc: string; cta: string }) {
  return (
    <Link to={to} style={{ ...card, textDecoration: "none", color: "inherit", display: "flex", flexDirection: "column", gap: 8 }}>
      <div style={{ fontSize: 30 }}>{emoji}</div>
      <div style={{ fontSize: 18, fontWeight: 700 }}>{title}</div>
      <p style={{ color: "var(--text-muted)", margin: 0, fontSize: 14, flex: 1 }}>{desc}</p>
      <span style={{ color: "#7c93ff", fontWeight: 600, fontSize: 14, marginTop: 4 }}>{cta} →</span>
    </Link>
  );
}

function StepRow({ done, title, desc, to, cta }: { done: boolean; title: string; desc: string; to: string; cta: string }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "10px 12px", background: "#0f1115", borderRadius: 8 }}>
      <div
        style={{
          width: 24,
          height: 24,
          borderRadius: 999,
          flexShrink: 0,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontSize: 13,
          fontWeight: 700,
          background: done ? "#13351f" : "transparent",
          color: done ? "#5fd28a" : "#666",
          border: done ? "none" : "1.5px solid #2a2d33",
        }}
      >
        {done ? "✓" : ""}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 600, color: done ? "var(--text-dim)" : "var(--text)", textDecoration: done ? "line-through" : "none" }}>
          {title}
        </div>
        {!done && <div style={{ color: "var(--text-dim)", fontSize: 13, marginTop: 2 }}>{desc}</div>}
      </div>
      {!done && (
        <Link to={to} style={{ ...btnSecondary, whiteSpace: "nowrap", textDecoration: "none" }}>
          {cta}
        </Link>
      )}
    </div>
  );
}

function RecentPanel({ title, allHref, allLabel, children }: { title: string; allHref: string; allLabel: string; children: React.ReactNode }) {
  return (
    <div style={card}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 12 }}>
        <h3 style={{ margin: 0, fontSize: 16 }}>{title}</h3>
        <Link to={allHref} style={{ color: "#7c93ff", textDecoration: "none", fontSize: 13 }}>
          {allLabel} →
        </Link>
      </div>
      <div style={{ display: "grid", gap: 8 }}>{children}</div>
    </div>
  );
}

function RecentRow({ to, label, meta }: { to: string; label: string; meta?: string }) {
  return (
    <Link to={to} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 10, padding: "8px 10px", background: "#0f1115", borderRadius: 6, textDecoration: "none", color: "inherit" }}>
      <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{label}</span>
      {meta && <span style={{ color: "var(--text-dim)", fontSize: 12, flexShrink: 0 }}>{meta}</span>}
    </Link>
  );
}

const card: React.CSSProperties = {
  background: "var(--surface)",
  border: "1px solid var(--border-strong)",
  borderRadius: 10,
  padding: 18,
};

const btnSecondary: React.CSSProperties = {
  background: "transparent",
  color: "var(--text)",
  border: "1px solid var(--border-strong)",
  borderRadius: 6,
  padding: "6px 12px",
  cursor: "pointer",
  fontSize: 13,
};
