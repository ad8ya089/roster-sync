import { useState } from "react";
import { BrakePanel } from "../components/BrakePanel";
import { ErrorNotice } from "../components/ErrorNotice";
import { OutcomeBadge } from "../components/OutcomeBadge";
import { OwnerTag } from "../components/OwnerTag";
import { PlanBar } from "../components/PlanBar";
import { RunStages, VersionTimeline } from "../components/RouteLine";
import { StatusPill } from "../components/StatusPill";
import { ApiError } from "../api/client";
import type { Outcome } from "../api/types";

const brake = {
  tripped: true,
  reason: "MISSING_RATIO",
  reasons: ["MISSING_RATIO"],
  activeBefore: 200000,
  missingActive: 80000,
  missingRatio: 0.4,
  thresholdRatio: 0.3,
  thresholdCount: null,
  message:
    "File appears to be missing 80,000 of 200,000 active employees (40.0%). Threshold is 30.0%. No one has been deactivated. Approve or reject this run.",
};
const tokens = [
  "paper",
  "surface",
  "ink",
  "ink-muted",
  "rule",
  "route",
  "line-hr",
  "line-transport",
];
export function StyleguidePage() {
  const [message, setMessage] = useState("");
  return (
    <article className="styleguide">
      <header className="page-header">
        <div>
          <p className="breadcrumb">Design reference</p>
          <h1>A dispatch board for roster decisions</h1>
          <p className="help">
            Local component specimens. The numbers below are illustrative, not
            operational data.
          </p>
        </div>
      </header>
      <section className="specimen">
        <h2>Run stages</h2>
        <RunStages status="AWAITING_APPROVAL" />
      </section>
      <BrakePanel
        brake={brake}
        canDecide
        awaiting
        onDecision={(decision) =>
          setMessage(
            `Selected ${decision === "REJECT" ? "Reject run" : decision === "ALL" ? "Approve all changes" : "Approve without deactivations"}. Specimen only; no request sent.`,
          )
        }
      />
      {message && <p role="status">{message}</p>}
      <div className="specimen-grid">
        <section className="specimen">
          <h2>Roster outcomes</h2>
          <div className="outcome-specimens">
            {(
              [
                "APPLIED",
                "STALE",
                "DUPLICATE",
                "NO_CHANGE",
                "REJECTED",
                "CONFLICT",
                "RETRYABLE_ERROR",
              ] as Outcome[]
            ).map((outcome) => (
              <OutcomeBadge key={outcome} outcome={outcome} />
            ))}
          </div>
          <div className="table-scroll">
            <table>
              <caption className="sr-only">Illustrative roster records</caption>
              <thead>
                <tr>
                  <th>Employee ID</th>
                  <th>Office</th>
                  <th className="number">Version</th>
                  <th>Outcome</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td className="code">UI-1042</td>
                  <td>BLR-2</td>
                  <td className="number code">7</td>
                  <td>
                    <OutcomeBadge outcome="APPLIED" />
                  </td>
                </tr>
                <tr>
                  <td className="code">UI-1042</td>
                  <td>BLR-1</td>
                  <td className="number code">6</td>
                  <td>
                    <OutcomeBadge outcome="STALE" />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div className="owner-specimens">
            <OwnerTag owner="HR" />
            <OwnerTag owner="TRANSPORT" />
          </div>
          <div className="tokens" aria-label="Colour tokens">
            {tokens.map((token) => (
              <div key={token}>
                <span className={`swatch swatch-${token}`} />
                <span>{token.replaceAll("-", " ")}</span>
              </div>
            ))}
          </div>
        </section>
        <section className="specimen">
          <VersionTimeline
            currentVersion="7"
            receipts={[
              {
                version: "7",
                outcome: "APPLIED",
                type: "UPDATE",
                receivedAt: "2026-10-31T01:15:02Z",
              },
              {
                version: "6",
                outcome: "STALE",
                type: "UPDATE",
                receivedAt: "2026-10-31T01:15:04Z",
              },
              {
                version: "5",
                outcome: "APPLIED",
                type: "CREATE",
                receivedAt: "2026-10-31T01:14:58Z",
              },
            ]}
          />
          <label htmlFor="specimen-field">Export taken</label>
          <input
            id="specimen-field"
            type="datetime-local"
            defaultValue="2026-10-31T06:45"
          />
          <p className="help">
            Use the time recorded by HR, not the upload time.
          </p>
          <div className="state-specimens">
            <span className="empty-inline">No rejected rows.</span>
            <StatusPill status="COMPLETED" />
          </div>
        </section>
      </div>
      <section className="specimen">
        <PlanBar
          plan={{
            creates: 200,
            updates: 1922,
            heals: 0,
            advances: 0,
            deactivations: 1010,
            unchanged: 196868,
            staleSkipped: 0,
          }}
        />
      </section>
      <section className="specimen">
        <ErrorNotice
          error={
            new ApiError(
              409,
              "INVALID_RUN_STATE",
              "Run changed",
              "Refresh the run before making another decision.",
              "00000000-0000-0000-0000-000000000009",
            )
          }
        />
      </section>
    </article>
  );
}
