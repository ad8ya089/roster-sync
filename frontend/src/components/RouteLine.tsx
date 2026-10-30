import type { ReactNode } from "react";
import type { Receipt, RunStatus } from "../api/types";
import { OutcomeBadge } from "./OutcomeBadge";

interface Stop {
  key: string;
  label: ReactNode;
  detail?: ReactNode;
  state: "past" | "current" | "future" | "branch" | "failed";
}
export function RouteLine({
  stops,
  label,
  vertical = false,
}: {
  stops: Stop[];
  label: string;
  vertical?: boolean;
}) {
  return (
    <ol
      className={`route-line ${vertical ? "route-vertical" : ""}`}
      aria-label={label}
    >
      {stops.map((stop) => (
        <li
          key={stop.key}
          className={`route-stop route-${stop.state}`}
          aria-current={stop.state === "current" ? "step" : undefined}
        >
          <span className="route-marker" aria-hidden="true" />
          <div className="route-label">
            {stop.label}
            {stop.detail && <small>{stop.detail}</small>}
          </div>
        </li>
      ))}
    </ol>
  );
}
export function RunStages({ status }: { status: RunStatus }) {
  const stages = [
    "Uploaded",
    "Comparing",
    "Waiting for approval",
    "Applying",
    "Completed",
  ];
  const position: Record<RunStatus, number> = {
    UPLOADED: 0,
    DIFFING: 1,
    AWAITING_APPROVAL: 2,
    APPLYING: 3,
    COMPLETED: 4,
    PLANNED: 2,
    REJECTED: 2,
    FAILED: 2,
  };
  if (status === "PLANNED") stages[2] = "Plan ready";
  if (status === "REJECTED" || status === "FAILED") {
    stages[2] = status === "REJECTED" ? "Rejected" : "Failed";
    stages.splice(3);
  }
  if (status === "PLANNED") stages.splice(3);
  return (
    <RouteLine
      label="Run stages"
      stops={stages.map((label, i) => ({
        key: label,
        label,
        state:
          i < position[status]
            ? "past"
            : i > position[status]
              ? "future"
              : status === "REJECTED" || status === "FAILED"
                ? "failed"
                : "current",
      }))}
    />
  );
}
export function firstArrivals(receipts: Receipt[]) {
  return [...receipts].sort(
    (a, b) =>
      Date.parse(a.receivedAt) - Date.parse(b.receivedAt) ||
      a.receivedAt
        .replace(
          /(?:\.(\d+))?Z$/,
          (_, digits = "") => `.${digits.padEnd(9, "0")}Z`,
        )
        .localeCompare(
          b.receivedAt.replace(
            /(?:\.(\d+))?Z$/,
            (_, digits = "") => `.${digits.padEnd(9, "0")}Z`,
          ),
        ),
  );
}
export function VersionTimeline({
  receipts,
  currentVersion,
}: {
  receipts: Receipt[];
  currentVersion: string;
}) {
  return (
    <div className="version-history">
      <h3>First arrival of each version</h3>
      <RouteLine
        vertical
        label="Version arrivals"
        stops={firstArrivals(receipts).map((receipt, i) => ({
          key: `${receipt.version}-${i}`,
          state: ["STALE", "DUPLICATE"].includes(receipt.outcome)
            ? "branch"
            : receipt.version === currentVersion
              ? "current"
              : "past",
          label: (
            <>
              <span className="code">v{receipt.version}</span>
              <OutcomeBadge outcome={receipt.outcome} />
              {receipt.version === currentVersion && (
                <span className="current-label">Current</span>
              )}
            </>
          ),
          detail: new Date(receipt.receivedAt).toLocaleTimeString("en-GB", {
            hour12: false,
          }),
        }))}
      />
    </div>
  );
}
