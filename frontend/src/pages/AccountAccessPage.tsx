import { Link } from "react-router-dom";
import { AccessLayout } from "../components/AccessLayout";

const accounts = [
  {
    username: "acme.hr",
    role: "HR integration",
    task: "Send employee events and upload nightly exports.",
  },
  {
    username: "acme.admin",
    role: "Transport admin",
    task: "Review employees, edit transport fields and view bookings.",
  },
  {
    username: "acme.operator",
    role: "Operator",
    task: "Review reconciliation runs, delivery failures and alerts.",
  },
  {
    username: "E1001",
    role: "Employee",
    task: "View your own profile and bookings.",
  },
];

export function AccountAccessPage() {
  return (
    <AccessLayout>
      <section className="account-access" aria-labelledby="access-title">
        <Link to="/login">Back to sign in</Link>
        <h1 id="access-title">Get access to the demo</h1>
        <p>
          This is a staff application. Each account belongs to a company and has
          an assigned role. Demo accounts are prepared in advance, so there is
          no public account registration.
        </p>
        <p>
          Ask the project owner for the demo password privately. Use one of the
          usernames below, rather than your personal email address. Selecting a
          role fills in its username; it does not sign you in or grant access.
        </p>
        <ul className="access-accounts">
          {accounts.map(({ username, role, task }) => (
            <li key={username}>
              <div>
                <h2>{role}</h2>
                <p>{task}</p>
                <span className="code">{username}</span>
              </div>
              <Link to="/login" state={{ username }}>
                Use this username
                <span className="sr-only"> for {role}</span>
              </Link>
            </li>
          ))}
        </ul>
        <p className="help">
          Company and role permissions are checked by the server. This page does
          not create accounts or change permissions.
        </p>
      </section>
    </AccessLayout>
  );
}
