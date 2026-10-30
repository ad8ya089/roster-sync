import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Copy } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRun } from "../api/queries";
import { useAuth } from "../auth/AuthContext";
import { BrakePanel } from "../components/BrakePanel";
import type { Decision } from "../components/BrakePanel";
import { ErrorNotice } from "../components/ErrorNotice";
import { PlanBar } from "../components/PlanBar";
import { RejectionsTable } from "../components/RejectionsTable";
import { RunStages } from "../components/RouteLine";
import { StatusPill } from "../components/StatusPill";
import { ConfirmDialog } from "../components/overlay/ConfirmDialog";

function date(value: string) {
  return new Date(value).toLocaleString("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZoneName: "short",
  });
}
export function RunDetailPage() {
  const { runId = "" } = useParams();
  const { request, session } = useAuth();
  const queries = useQueryClient();
  const query = useRun(runId);
  const [decision, setDecision] = useState<Decision | null>(null);
  const [tab, setTab] = useState<"overview" | "rejections">("overview");
  const [notice, setNotice] = useState("");
  const mutation = useMutation({
    mutationFn: async (note: string) =>
      request(
        `/reconciliation/runs/${runId}/${decision === "REJECT" ? "reject" : "approve"}`,
        {
          method: "POST",
          body: JSON.stringify(
            decision === "REJECT" ? { note } : { mode: decision, note },
          ),
        },
      ),
    onSuccess: async () => {
      setNotice(decision === "REJECT" ? "Run rejected." : "Run approved.");
      setDecision(null);
      await queries.invalidateQueries({ queryKey: [session?.claims.tid] });
    },
    onError: async () => {
      await queries.invalidateQueries({
        queryKey: [session?.claims.tid, "run", runId],
      });
    },
    retry: false,
  });
  if (query.error)
    return (
      <>
        <Link className="breadcrumb" to="/runs">
          Reconciliation
        </Link>
        <ErrorNotice error={query.error} />
      </>
    );
  if (!query.data) return <p role="status">Loading run</p>;
  const run = query.data;
  return (
    <article className="run-detail">
      <Link className="breadcrumb" to="/runs">
        Reconciliation / Run review
      </Link>
      <header className="page-header">
        <div>
          <h1>Review nightly export</h1>
          <div className="run-identifier">
            <span className="code">{run.runId}</span>
            <button
              className="icon-button"
              aria-label="Copy run ID"
              onClick={() => {
                void navigator.clipboard
                  .writeText(run.runId)
                  .catch(() => undefined);
              }}
            >
              <Copy aria-hidden="true" />
            </button>
          </div>
        </div>
        <StatusPill status={run.status} />
      </header>
      <dl className="run-dates">
        <div>
          <dt>Uploaded</dt>
          <dd>{date(run.createdAt)}</dd>
        </div>
        <div>
          <dt>Export taken</dt>
          <dd>{date(run.asOf)}</dd>
        </div>
        {run.dryRun && (
          <div>
            <dt>Mode</dt>
            <dd>Dry run</dd>
          </div>
        )}
      </dl>
      <RunStages status={run.status} />
      {run.status === "APPLYING" && (
        <p role="status">
          {run.applied.toLocaleString("en-US")} applied,{" "}
          {run.superseded.toLocaleString("en-US")} superseded of{" "}
          {(
            run.plan.creates +
            run.plan.updates +
            run.plan.heals +
            run.plan.advances +
            run.plan.deactivations
          ).toLocaleString("en-US")}{" "}
          planned actions. Skipped deactivations are not applied.
        </p>
      )}
      {notice && (
        <p className="save-notice" role="status">
          {notice}
        </p>
      )}
      <BrakePanel
        brake={run.brake}
        awaiting={run.status === "AWAITING_APPROVAL"}
        canDecide={session?.claims.role === "OPERATOR"}
        onDecision={(value) => {
          mutation.reset();
          setDecision(value);
        }}
      />
      {run.failureReason && (
        <p className="error-notice">
          Run failed: <span className="code">{run.failureReason}</span>
        </p>
      )}
      <div className="run-tabs" aria-label="Run sections">
        <button
          aria-pressed={tab === "overview"}
          onClick={() => setTab("overview")}
        >
          Overview
        </button>
        <button
          aria-pressed={tab === "rejections"}
          onClick={() => setTab("rejections")}
        >
          Rejections{" "}
          <span className="number">
            {run.rows.rejected.toLocaleString("en-US")}
          </span>
        </button>
      </div>
      {tab === "overview" ? (
        <>
          <PlanBar plan={run.plan} />
          <section className="run-statistics" aria-label="Comparison figures">
            <div>
              <h2>File rows</h2>
              <dl>
                <div>
                  <dt>Total received</dt>
                  <dd>{run.rows.total.toLocaleString("en-US")}</dd>
                </div>
                <div>
                  <dt>Valid</dt>
                  <dd>{run.rows.valid.toLocaleString("en-US")}</dd>
                </div>
                <div>
                  <dt>Rejected</dt>
                  <dd>{run.rows.rejected.toLocaleString("en-US")}</dd>
                </div>
              </dl>
            </div>
            <div>
              <h2>Application</h2>
              <dl>
                <div>
                  <dt>Applied</dt>
                  <dd>{run.applied.toLocaleString("en-US")}</dd>
                </div>
                <div>
                  <dt>Superseded</dt>
                  <dd>{run.superseded.toLocaleString("en-US")}</dd>
                </div>
                <div>
                  <dt>Diff time</dt>
                  <dd>
                    {run.timings.diffMs === null
                      ? "Not available"
                      : `${run.timings.diffMs.toLocaleString("en-US")} ms`}
                  </dd>
                </div>
                <div>
                  <dt>Apply time</dt>
                  <dd>
                    {run.timings.applyMs === null
                      ? "Not available"
                      : `${run.timings.applyMs.toLocaleString("en-US")} ms`}
                  </dd>
                </div>
              </dl>
            </div>
          </section>
        </>
      ) : (
        <RejectionsTable runId={runId} />
      )}
      {decision && (
        <ConfirmDialog
          decision={decision}
          pending={mutation.isPending}
          error={mutation.error}
          onConfirm={(note) => mutation.mutate(note)}
          onDismiss={() => {
            if (!mutation.isPending) setDecision(null);
          }}
        />
      )}
    </article>
  );
}
