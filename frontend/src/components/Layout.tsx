import { useState } from "react";
import type { ReactNode } from "react";
import {
  Activity,
  ClipboardList,
  FlaskConical,
  LogOut,
  Menu,
  Palette,
  Route,
  Send,
  User,
  Users,
  X,
} from "lucide-react";
import { Link, NavLink } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useApiQuery } from "../api/useApiQuery";

export function Layout({ children }: { children: ReactNode }) {
  const { session, logout } = useAuth();
  const [expanded, setExpanded] = useState(false);
  const role = session!.claims.role;
  const demo = useApiQuery<unknown[]>("/dev/faults", role === "OPERATOR");
  const roles = {
    HR_SYSTEM: "HR integration",
    TRANSPORT_ADMIN: "Transport admin",
    OPERATOR: "Operator",
    EMPLOYEE: "Employee",
  };
  return (
    <div className="app-shell">
      <a
        className="skip-link"
        href="#main"
        onClick={(event) => {
          event.preventDefault();
          document.getElementById("main")?.focus();
        }}
      >
        Skip to content
      </a>
      <aside className={`sidebar ${expanded ? "nav-expanded" : ""}`}>
        <Link className="brand" to="/">
          <Route aria-hidden="true" />
          <span>Roster Sync</span>
        </Link>
        <button
          className="icon-button mobile-menu"
          aria-label={expanded ? "Close menu" : "Open menu"}
          aria-expanded={expanded}
          onClick={() => setExpanded(!expanded)}
        >
          {expanded ? <X /> : <Menu />}
        </button>
        <nav aria-label="Main navigation" onClick={() => setExpanded(false)}>
          {role === "OPERATOR" && (
            <NavLink to="/ops">
              <Activity aria-hidden="true" />
              Operations
            </NavLink>
          )}
          {role === "HR_SYSTEM" && (
            <NavLink to="/events">
              <Send aria-hidden="true" />
              Event console
            </NavLink>
          )}
          {role !== "EMPLOYEE" && (
            <NavLink to="/runs" end>
              <ClipboardList aria-hidden="true" />
              Runs
            </NavLink>
          )}
          {["OPERATOR", "TRANSPORT_ADMIN"].includes(role) && (
            <NavLink to="/employees">
              <Users aria-hidden="true" />
              Employees
            </NavLink>
          )}
          {role === "EMPLOYEE" && (
            <NavLink to="/me">
              <User aria-hidden="true" />
              My profile
            </NavLink>
          )}
          {role === "OPERATOR" && demo.isSuccess && (
            <NavLink to="/demo">
              <FlaskConical aria-hidden="true" />
              Demo tools
            </NavLink>
          )}
          {import.meta.env.VITE_STYLEGUIDE === "true" && (
            <NavLink to="/styleguide">
              <Palette aria-hidden="true" />
              Design reference
            </NavLink>
          )}
        </nav>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="session-identity">
            <span className="tenant">{session?.claims.tcode}</span>
            <span>{session?.claims.sub}</span>
            <span className="role-label">
              {session && roles[session.claims.role]}
            </span>
          </div>
          <button className="button-text" onClick={logout}>
            <LogOut aria-hidden="true" />
            Log out
          </button>
        </header>
        <main id="main" tabIndex={-1}>
          {children}
        </main>
      </div>
    </div>
  );
}
