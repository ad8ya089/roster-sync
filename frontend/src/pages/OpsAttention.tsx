import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { useApiQuery } from "../api/useApiQuery";
import type { Alert, OutboxRow, PageResponse, Run } from "../api/types";
import { ErrorNotice } from "../components/ErrorNotice";
import { ActionConfirm } from "../components/overlay/ActionConfirm";
import { Pagination } from "../components/Pagination";
import { date } from "../components/format";

export function OpsAttention() {
  const { request, session } = useAuth();
  const queries = useQueryClient();
  const [page, setPage] = useState(0);
  const [runPage, setRunPage] = useState(0);
  const [retryId, setRetryId] = useState<number | null>(null);
  const [notice, setNotice] = useState("");
  const alerts = useApiQuery<Alert[]>(
    "/ops/alerts?acknowledged=false",
    true,
    10_000,
  );
  const dead = useApiQuery<PageResponse<OutboxRow>>(
    `/ops/outbox?status=DEAD&page=${page}`,
    true,
    10_000,
  );
  const runs = useApiQuery<PageResponse<Run>>(
    `/reconciliation/runs?status=AWAITING_APPROVAL&page=${runPage}`,
    true,
    10_000,
  );
  const mutation = useMutation({
    mutationFn: ({ path }: { path: string }) =>
      request(path, { method: "POST" }),
    retry: false,
    onSuccess: async (_, { path }) => {
      setNotice(
        path.endsWith("/ack")
          ? "Alert acknowledged."
          : "Delivery queued for retry.",
      );
      setRetryId(null);
      await queries.invalidateQueries({ queryKey: [session?.claims.tid] });
    },
  });
  return (
    <section id="attention" className="attention-list">
      <h2>Needs attention</h2>
      {notice && <p role="status">{notice}</p>}
      {[
        alerts.error,
        dead.error,
        runs.error,
        retryId === null ? mutation.error : null,
      ]
        .filter(Boolean)
        .map((error, i) => (
          <ErrorNotice key={i} error={error} />
        ))}
      {!alerts.data || !dead.data || !runs.data ? (
        <p role="status">Loading attention list</p>
      ) : (
        <>
          {runs.data.items.length +
            alerts.data.length +
            dead.data.totalElements ===
            0 && <p>No items need attention.</p>}
          {runs.data.items.map((run) => (
            <div className="attention-row" key={run.runId}>
              <Link to={`/runs/${run.runId}`}>
                Review run <span className="code">{run.runId}</span>
              </Link>
              <span>Waiting for approval</span>
            </div>
          ))}
          {runs.data.totalElements > runs.data.size && (
            <Pagination
              page={runPage}
              size={runs.data.size}
              total={runs.data.totalElements}
              onPage={setRunPage}
            />
          )}
          {alerts.data.map((alert) => (
            <div className="attention-row" key={alert.id}>
              <div>
                <h3>{alert.alertType.replaceAll("_", " ").toLowerCase()}</h3>
                <p>{alert.message}</p>
                <small>{date(alert.createdAt)}</small>
              </div>
              <button
                className="button button-secondary"
                disabled={mutation.isPending}
                onClick={() => {
                  mutation.reset();
                  mutation.mutate({ path: `/ops/alerts/${alert.id}/ack` });
                }}
              >
                Acknowledge
              </button>
            </div>
          ))}
          {alerts.data.length === 200 && (
            <p className="help">Showing the 200 most recent open alerts.</p>
          )}
          {dead.data.items.map((row) => (
            <div className="attention-row" key={row.id}>
              <div>
                <h3>{row.eventType}</h3>
                <p className="code">{row.eventId}</p>
                <p>
                  {row.attempts} attempts; {row.lastError ?? "No error code"}
                </p>
              </div>
              <button
                className="button button-secondary"
                onClick={() => {
                  mutation.reset();
                  setRetryId(row.id);
                }}
              >
                Retry delivery
              </button>
            </div>
          ))}
          {dead.data.totalElements > dead.data.size && (
            <Pagination
              page={page}
              size={dead.data.size}
              total={dead.data.totalElements}
              onPage={setPage}
            />
          )}
        </>
      )}
      {retryId !== null && (
        <ActionConfirm
          title="Retry delivery"
          description="Reset the failed event's attempts and queue it for delivery. Consumers deduplicate repeated delivery."
          pending={mutation.isPending}
          error={mutation.error}
          onConfirm={() =>
            mutation.mutate({ path: `/ops/outbox/${retryId}/retry` })
          }
          onClose={() => setRetryId(null)}
        />
      )}
    </section>
  );
}
