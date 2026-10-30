import type { RunStatus } from "../api/types";
const labels: Record<RunStatus, string> = {
  UPLOADED: "Uploaded",
  DIFFING: "Comparing",
  PLANNED: "Plan ready",
  AWAITING_APPROVAL: "Waiting for approval",
  APPLYING: "Applying",
  COMPLETED: "Completed",
  REJECTED: "Rejected",
  FAILED: "Failed",
};
export function StatusPill({ status }: { status: RunStatus }) {
  return (
    <span className={`status status-${status.toLowerCase()}`}>
      <span className="signal" aria-hidden="true" />
      {labels[status]}
    </span>
  );
}
