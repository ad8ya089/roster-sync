import { useState } from "react";
import { Link, Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { AccessLayout } from "../components/AccessLayout";
import { SignInError } from "../components/SignInError";

export function LoginPage() {
  const { session, notice, login } = useAuth();
  const location = useLocation();
  const suggested: unknown = (location.state as { username?: unknown } | null)
    ?.username;
  const [username, setUsername] = useState(
    typeof suggested === "string" ? suggested : "",
  );
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const from: unknown = (location.state as { from?: unknown } | null)?.from;
  if (session)
    return (
      <Navigate
        to={
          typeof from === "string" &&
          from.startsWith("/") &&
          !from.startsWith("//")
            ? from
            : "/"
        }
        replace
      />
    );
  return (
    <AccessLayout>
      <div className="access-content">
        <section className="access-intro">
          <h1>Sign in to operations</h1>
          <p>
            Keep employee records in sync with HR. Review incoming changes,
            transport details and nightly roster checks in one place.
          </p>
          <p>
            Use the staff username and password provided for your role. A
            personal email address is not automatically a demo account.
          </p>
          <Link to="/account-access">Need an account?</Link>
        </section>
        <section className="login-form">
          <h2>Staff account</h2>
          {notice && <p role="status">{notice}</p>}
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              setBusy(true);
              setError(null);
              try {
                await login(username, password);
                setPassword("");
              } catch (failure) {
                setError(failure);
                setPassword("");
              } finally {
                setBusy(false);
              }
            }}
          >
            <label htmlFor="username">Username</label>
            <input
              id="username"
              aria-describedby="username-help"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              required
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
            <p id="username-help" className="help">
              Use the assigned username.{" "}
              <Link to="/account-access">Find your demo role</Link>.
            </p>
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            {error != null && <SignInError error={error} />}
            <button className="button button-primary" disabled={busy}>
              {busy ? "Signing in" : "Sign in"}
            </button>
          </form>
          <p className="help">
            Use only the demo password, never a personal password. Your session
            stays in this tab. Reloading signs you out.
          </p>
        </section>
      </div>
    </AccessLayout>
  );
}
