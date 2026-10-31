import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { Route } from "lucide-react";

export function AccessLayout({ children }: { children: ReactNode }) {
  return (
    <main className="login-page">
      <header className="access-header">
        <Link className="brand" to="/login">
          <Route aria-hidden="true" />
          HR Roster Sync
        </Link>
        <span className="help">A project by Aditya Prakash</span>
      </header>
      {children}
    </main>
  );
}
