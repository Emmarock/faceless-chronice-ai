import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from "react";

/**
 * Shared UI kit — the "Linear" foundation. A small set of consistent
 * primitives (header, card, button, field, stepper, option card) so pages stop
 * re-defining ad-hoc inline styles and the app feels like one product. Built on
 * the design tokens in styles.css (var(--surface), var(--accent), …).
 */

const ACCENT = "#3b82f6";

export const card: CSSProperties = {
  background: "var(--surface)",
  border: "1px solid var(--border-strong)",
  borderRadius: 12,
  padding: 20,
};

export const inputStyle: CSSProperties = {
  background: "var(--surface-2)",
  border: "1px solid var(--border-strong)",
  borderRadius: 8,
  padding: "11px 13px",
  color: "var(--text)",
  fontFamily: "inherit",
  fontSize: 15,
  width: "100%",
  boxSizing: "border-box",
};

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 16, flexWrap: "wrap", marginBottom: 24 }}>
      <div style={{ minWidth: 0 }}>
        <h2 style={{ margin: 0, fontSize: 24 }}>{title}</h2>
        {subtitle && <p style={{ color: "var(--text-muted)", margin: "8px 0 0", fontSize: 15 }}>{subtitle}</p>}
      </div>
      {actions}
    </div>
  );
}

export function Card({ children, style }: { children: ReactNode; style?: CSSProperties }) {
  return <div style={{ ...card, ...style }}>{children}</div>;
}

type ButtonVariant = "primary" | "secondary" | "ghost";

export function buttonStyle(variant: ButtonVariant = "primary"): CSSProperties {
  const base: CSSProperties = {
    borderRadius: 8,
    padding: "10px 18px",
    fontSize: 14,
    fontWeight: 600,
    cursor: "pointer",
    border: "1px solid transparent",
    display: "inline-flex",
    alignItems: "center",
    gap: 8,
    justifyContent: "center",
  };
  if (variant === "secondary") return { ...base, background: "transparent", color: "var(--text)", borderColor: "var(--border-strong)" };
  if (variant === "ghost") return { ...base, background: "transparent", color: "var(--text-muted)" };
  return { ...base, background: ACCENT, color: "#fff" };
}

export function Button({
  variant = "primary",
  children,
  style,
  ...rest
}: { variant?: ButtonVariant } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button {...rest} style={{ ...buttonStyle(variant), ...style }}>
      {children}
    </button>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label style={{ display: "grid", gap: 6 }}>
      <span style={{ fontSize: 13, color: "var(--text-muted)", fontWeight: 500 }}>{label}</span>
      {children}
      {hint && <span style={{ fontSize: 12, color: "var(--text-dim)" }}>{hint}</span>}
    </label>
  );
}

/** Horizontal progress stepper (Linear/Synthesia style). */
export function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return (
    <div style={{ display: "flex", alignItems: "center", marginBottom: 28, flexWrap: "wrap", rowGap: 8 }}>
      {steps.map((label, i) => {
        const done = i < current;
        const active = i === current;
        return (
          <div key={label} style={{ display: "flex", alignItems: "center" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <div
                style={{
                  width: 26,
                  height: 26,
                  borderRadius: 999,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  fontSize: 13,
                  fontWeight: 700,
                  flexShrink: 0,
                  background: active ? ACCENT : done ? "#13351f" : "transparent",
                  color: active ? "#fff" : done ? "#5fd28a" : "var(--text-dim)",
                  border: active || done ? "none" : "1.5px solid var(--border-strong)",
                }}
              >
                {done ? "✓" : i + 1}
              </div>
              <span style={{ fontSize: 13, fontWeight: active ? 600 : 400, color: active ? "var(--text)" : "var(--text-dim)", whiteSpace: "nowrap" }}>
                {label}
              </span>
            </div>
            {i < steps.length - 1 && <div style={{ width: 28, height: 1, background: "var(--border-strong)", margin: "0 12px" }} />}
          </div>
        );
      })}
    </div>
  );
}

/** A large, selectable choice card (format pickers, mode pickers, …). */
export function OptionCard({
  active,
  onClick,
  title,
  desc,
  badge,
  locked,
  icon,
}: {
  active: boolean;
  onClick: () => void;
  title: string;
  desc: string;
  badge?: string;
  locked?: boolean;
  icon?: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        flex: 1,
        minWidth: 180,
        textAlign: "left",
        background: active ? "rgba(59,130,246,0.08)" : "var(--surface-2)",
        border: `1.5px solid ${active ? ACCENT : "var(--border-strong)"}`,
        borderRadius: 10,
        padding: 16,
        cursor: "pointer",
        display: "flex",
        flexDirection: "column",
        gap: 6,
        position: "relative",
      }}
    >
      {icon && <div style={{ fontSize: 22 }}>{icon}</div>}
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ fontWeight: 700, fontSize: 15, color: "var(--text)" }}>{locked ? `🔒 ${title}` : title}</span>
        {badge && (
          <span style={{ fontSize: 11, fontWeight: 600, color: active ? "#bcd2ff" : "var(--text-dim)", background: active ? "rgba(59,130,246,0.18)" : "transparent", border: active ? "none" : "1px solid var(--border-strong)", borderRadius: 999, padding: "1px 8px" }}>
            {badge}
          </span>
        )}
      </div>
      <span style={{ fontSize: 13, color: "var(--text-muted)" }}>{desc}</span>
    </button>
  );
}
