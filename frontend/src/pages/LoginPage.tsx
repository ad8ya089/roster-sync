import { useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Route } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { ErrorNotice } from '../components/ErrorNotice';

export function LoginPage() {
  const { session, notice, login } = useAuth(); const location = useLocation();
  const [username, setUsername] = useState(''); const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false); const [error, setError] = useState<unknown>(null);
  const from: unknown = (location.state as { from?: unknown } | null)?.from;
  if (session) return <Navigate to={typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : '/'} replace />;
  return <main className="login-page"><div className="brand"><Route aria-hidden="true" />Roster Sync</div>
    <section className="login-form"><h1>Sign in to operations</h1><p>Review roster changes and the decisions that keep transport running.</p>
      {notice && <p role="status">{notice}</p>}
      <form onSubmit={async event => { event.preventDefault(); setBusy(true); setError(null);
        try { await login(username, password); setPassword(''); } catch (failure) { setError(failure); setPassword(''); } finally { setBusy(false); }
      }}><label htmlFor="username">Username</label><input id="username" autoComplete="username" required value={username} onChange={event => setUsername(event.target.value)} />
        <label htmlFor="password">Password</label><input id="password" type="password" autoComplete="current-password" required value={password} onChange={event => setPassword(event.target.value)} />
        {error != null && <ErrorNotice error={error} />}<button className="button button-primary" disabled={busy}>{busy ? 'Signing in' : 'Sign in'}</button>
      </form><p className="help">Your session stays in this tab. Reloading signs you out.</p>
      {import.meta.env.VITE_DEMO_HINTS === 'true' && <details><summary>Demo usernames</summary><p>acme.operator, acme.hr, acme.admin, E1001</p><p>Use the password provided privately.</p></details>}
    </section></main>;
}
