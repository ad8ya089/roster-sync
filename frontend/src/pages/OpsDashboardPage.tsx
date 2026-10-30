import { Link } from "react-router-dom";
import { useApiQuery } from "../api/useApiQuery";
import type { OpsSummary } from "../api/types";
import { ErrorNotice } from "../components/ErrorNotice";
import { ServiceRow } from "../components/ServiceRow";
import { number, date } from "../components/format";
import { OpsAttention } from "./OpsAttention";

export function rejectionShare(outcomes: OpsSummary["durable"]["last15m"]) {
  const total = Object.values(outcomes).reduce((sum, count) => sum + count, 0);
  return total === 0 ? 0 : outcomes.rejected / total;
}
export function OpsDashboardPage() {
  const query = useApiQuery<OpsSummary>("/ops/summary", true, 10_000);
  if (query.error) return <ErrorNotice error={query.error} />;
  if (!query.data) return <p role="status">Loading operations</p>;
  const { durable: d, sinceRestart: s } = query.data;
  const share = rejectionShare(d.last15m);
  // Signals use tenant SQL state, never since-restart counters or inferred trends.
  // Dead delivery outranks lag; rejection share uses only durable recorded outcomes.
  return (
    <article>
      <header className="page-header">
        <h1>Operations</h1>
        <span className="help">Updated {date(query.data.generatedAt)}</span>
      </header>
      <section className="service-board" aria-label="Service status">
        <ServiceRow
          name="Event intake"
          status={share > 0.05 ? "Delayed" : "Running normally"}
          sentence={`${number(d.last15m.applied)} changes applied, ${number(d.last15m.stale)} stale, ${number(d.last15m.rejected)} rejected in the last 15 minutes.`}
          figures={`${number(share * 100)}% rejected`}
        />
        <ServiceRow
          name="Change delivery"
          status={
            d.outbox.dead > 0
              ? "Stopped"
              : d.outbox.oldestPendingAgeSeconds > 60
                ? "Delayed"
                : "Running normally"
          }
          sentence={`${number(d.outbox.pending)} pending; ${number(d.outbox.currentlyRetrying)} currently retrying.`}
          figures={`${number(d.outbox.dead)} dead`}
          action={d.outbox.dead > 0 && <a href="#attention">Review delivery</a>}
        />
        <ServiceRow
          name="Nightly reconciliation"
          status={
            d.reconciliation.awaitingApproval > 0
              ? "Needs a decision"
              : "Running normally"
          }
          sentence={`${number(d.reconciliation.awaitingApproval)} runs awaiting approval.`}
          figures={
            d.reconciliation.lastRun?.status
              .toLowerCase()
              .replaceAll("_", " ") ?? "No runs"
          }
          action={<Link to="/runs">Review runs</Link>}
        />
        <ServiceRow
          name="Alerts"
          status={d.openAlerts > 0 ? "Needs a decision" : "Running normally"}
          sentence={`${number(d.openAlerts)} open alerts.`}
          figures={`${number(d.openAlerts)} open`}
          action={d.openAlerts > 0 && <a href="#attention">Review alerts</a>}
        />
      </section>
      <p className="help">
        Rejection share: {number(share * 100)}% of durable recorded outcomes
        (applied, stale, no change, rejected, conflict).
      </p>
      <div className="statistics-groups">
        <section>
          <h2>This tenant, from the database</h2>
          <dl className="figure-list">
            {Object.entries(d.last15m).map(([key, value]) => (
              <div key={key}>
                <dt>
                  {
                    {
                      applied: "Applied",
                      stale: "Stale",
                      noChange: "No change",
                      rejected: "Rejected",
                      conflict: "Conflict",
                    }[key]
                  }{" "}
                  (last 15 minutes)
                </dt>
                <dd>{number(value)}</dd>
              </div>
            ))}
            <div>
              <dt>Oldest pending age (seconds)</dt>
              <dd>{number(d.outbox.oldestPendingAgeSeconds)}</dd>
            </div>
          </dl>
        </section>
        <section>
          <h2>This app instance, since {date(s.since)}</h2>
          <p className="help">Instance-wide; resets on restart.</p>
          <dl className="figure-list">
            <div>
              <dt>Replay duplicates</dt>
              <dd>{number(s.duplicates)}</dd>
            </div>
            <div>
              <dt>Lock retries</dt>
              <dd>{number(s.lockRetries)}</dd>
            </div>
            <div>
              <dt>Publish failures</dt>
              <dd>{number(s.outboxPublishFailures)}</dd>
            </div>
            <div>
              <dt>Apply p99 (ms)</dt>
              <dd>
                {s.p99ApplyMs === null ? "Not available" : number(s.p99ApplyMs)}
              </dd>
            </div>
            <div>
              <dt>Ingest lag p99 (seconds)</dt>
              <dd>
                {s.lagP99Seconds === null
                  ? "Not available"
                  : number(s.lagP99Seconds)}
              </dd>
            </div>
          </dl>
        </section>
      </div>
      <OpsAttention />
    </article>
  );
}
