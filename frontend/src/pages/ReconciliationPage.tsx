import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import type { PageResponse, Run } from "../api/types";
import { terminal } from "../api/queries";
import { StatusPill } from "../components/StatusPill";
import { Pagination } from "../components/Pagination";
import { ErrorNotice } from "../components/ErrorNotice";
import { date, number } from "../components/format";
import { UploadPanel } from "./UploadPanel";
import { SettingsDrawer } from "./SettingsDrawer";

export function ReconciliationPage() {
  const { request, session } = useAuth();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const [settings, setSettings] = useState(false);
  const query = useQuery({
    queryKey: [session?.claims.tid, "runs", status, page],
    queryFn: ({ signal }) =>
      request<PageResponse<Run>>(
        `/reconciliation/runs?page=${page}${status ? `&status=${status}` : ""}`,
        { signal },
      ),
    refetchInterval: (query) =>
      query.state.data?.items.some((run) => !terminal.has(run.status))
        ? 5000
        : false,
  });
  return (
    <article>
      <header className="page-header">
        <h1>Reconciliation runs</h1>
        {session?.claims.role === "OPERATOR" && (
          <button
            className="button button-secondary"
            onClick={() => setSettings(true)}
          >
            Brake settings
          </button>
        )}
      </header>
      {session?.claims.role !== "TRANSPORT_ADMIN" && <UploadPanel />}
      <div className="filters">
        <div>
          <label htmlFor="run-status">Status</label>
          <select
            id="run-status"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          >
            <option value="">All statuses</option>
            {[
              "UPLOADED",
              "DIFFING",
              "PLANNED",
              "AWAITING_APPROVAL",
              "APPLYING",
              "COMPLETED",
              "REJECTED",
              "FAILED",
            ].map((status) => (
              <option key={status} value={status}>
                {status.toLowerCase().replaceAll("_", " ")}
              </option>
            ))}
          </select>
        </div>
      </div>
      {query.error && <ErrorNotice error={query.error} />}
      <div
        className="table-scroll"
        role="region"
        aria-label="Reconciliation records"
        tabIndex={0}
      >
        <table>
          <caption>Nightly export runs</caption>
          <thead>
            <tr>
              <th>Uploaded</th>
              <th>Export taken</th>
              <th>Status</th>
              <th className="number">Rows / rejected</th>
              <th>Plan</th>
              <th>Brake</th>
              <th className="number">Applied</th>
            </tr>
          </thead>
          <tbody>
            {!query.data ? (
              <tr>
                <td colSpan={7}>Loading runs</td>
              </tr>
            ) : query.data.items.length === 0 ? (
              <tr>
                <td colSpan={7}>No runs match this status.</td>
              </tr>
            ) : (
              query.data.items.map((run) => (
                <tr key={run.runId}>
                  <td>
                    <Link to={`/runs/${run.runId}`}>{date(run.createdAt)}</Link>
                    {run.dryRun && <small className="block">Dry run</small>}
                  </td>
                  <td>{date(run.asOf)}</td>
                  <td>
                    <StatusPill status={run.status} />
                  </td>
                  <td className="number">
                    {number(run.rows.total)} / {number(run.rows.rejected)}
                  </td>
                  <td className="plan-cell">
                    {Object.entries(run.plan)
                      .map(([key, value]) => `${key}: ${number(value)}`)
                      .join(", ")}
                  </td>
                  <td>{run.brake.tripped ? "Tripped" : "Clear"}</td>
                  <td className="number">{number(run.applied)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      {query.data && (
        <Pagination
          page={page}
          size={query.data.size}
          total={query.data.totalElements}
          onPage={setPage}
        />
      )}
      {settings && <SettingsDrawer onClose={() => setSettings(false)} />}
    </article>
  );
}
