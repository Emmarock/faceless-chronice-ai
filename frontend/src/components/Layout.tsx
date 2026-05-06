import { useEffect, useState } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function Layout() {
  const { user, signOut } = useAuth();
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
          <NavLink to="/" end className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
            Jobs
          </NavLink>
          <NavLink to="/jobs/new" className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
            Create
          </NavLink>
          <NavLink to="/videos" className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
            Videos
          </NavLink>
          <NavLink to="/connections" className={({ isActive }) => (isActive ? "active" : undefined)} onClick={closeMenu}>
            Connections
          </NavLink>
        </nav>

        <div className="app-header__user">
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