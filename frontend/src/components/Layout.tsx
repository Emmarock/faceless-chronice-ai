import { useEffect, useState } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { useBilling } from "../context/BillingContext";

export function Layout() {
  const { user, signOut } = useAuth();
  const { billing } = useBilling();
  const navigate = useNavigate();
  const location = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);

  // Close the mobile dropdown whenever the route changes.
  useEffect(() => {
    setMenuOpen(false);
  }, [location.key]);

  const handleSignOut = () => {
    signOut();
    navigate("/login");
  };

  const closeMenu = () => setMenuOpen(false);

  // During first-time onboarding (no plan picked yet), every nav link except
  // Billing points at a route the RequirePlanSelected gate would bounce back
  // to /pricing. Hide them to avoid a confusing click-redirect loop. Billing
  // stays visible because the user still needs to manage their state.
  // While billing is still loading (`billing === null`) we keep everything
  // visible so returning users don't see a chrome-pop on every refresh.
  const navGated = billing !== null && !billing.planSelected;

  return (
    <div className="app-shell">
      <header className={`app-header${menuOpen ? " is-open" : ""}`}>
        <Link to="/" className="app-header__brand" onClick={closeMenu}>
          <img src="/icon.svg" alt="" width={28} height={28} style={{ verticalAlign: "middle", marginRight: 10 }} />
          Faceless Chronicle AI
        </Link>

        <button
          type="button"
          className="app-header__menu-btn"
          aria-label={menuOpen ? "Close menu" : "Open menu"}
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((v) => !v)}
        >
          {menuOpen ? "✕" : "☰"}
        </button>

        <nav className="app-header__nav">
          {!navGated && (
            <>
              <NavLink to="/" end className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
                Contents
              </NavLink>
              <NavLink to="/jobs/new" className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
                Create
              </NavLink>
              <NavLink to="/videos" className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
                Videos
              </NavLink>
              <NavLink to="/assets" className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
                Assets
              </NavLink>
              <NavLink to="/connections" className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
                Connections
              </NavLink>
              <NavLink to="/scheduled" className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
                Scheduled
              </NavLink>
            </>
          )}
          <NavLink to="/billing" className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
            Billing
          </NavLink>
        </nav>

        <div className="app-header__user">
          {billing && (
            <Link
              to="/pricing"
              title={`${billing.creditBalance} credits remaining on ${billing.planDisplayName}`}
              style={creditChip}
            >
              <span style={{ opacity: 0.7, marginRight: 4 }}>◈</span>
              {billing.creditBalance.toLocaleString()}
            </Link>
          )}
          {user?.picture && <img src={user.picture} alt="" className="app-header__avatar" />}
          <span className="app-header__user-email">{user?.email}</span>
          <button onClick={handleSignOut} style={btnSecondary}>
            Sign out
          </button>
        </div>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}

const btnSecondary: React.CSSProperties = {
  background: "transparent",
  color: "#e6e6e6",
  border: "1px solid #2a2d33",
  borderRadius: 6,
  padding: "6px 12px",
  cursor: "pointer",
  fontSize: 13,
};

const creditChip: React.CSSProperties = {
  background: "#15171b",
  color: "#cbd5e1",
  border: "1px solid #2a2d33",
  borderRadius: 999,
  padding: "4px 10px",
  fontSize: 13,
  fontWeight: 600,
  textDecoration: "none",
  display: "inline-flex",
  alignItems: "center",
  marginRight: 8,
};