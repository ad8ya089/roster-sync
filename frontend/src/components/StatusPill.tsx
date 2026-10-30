import type { RunStatus } from "../api/types";
type Status =
  RunStatus | "ACTIVE" | "INACTIVE" | "SCHEDULED" | "CANCELLED" | "COMPLETED";
const labels: Record<Status, string> = {
  UPLOADED: "Uploaded",
  DIFFING: "Comparing",
  PLANNED: "Plan ready",
  AWAITING_APPROVAL: "Waiting for approval",
  APPLYING: "Applying",
  COMPLETED: "Completed",
  REJECTED: "Rejected",
  FAILED: "Failed",
  ACTIVE: "Active",
  INACTIVE: "Inactive",
  SCHEDULED: "Scheduled",
  CANCELLED: "Cancelled",
};
export function StatusPill({ status }: { status: string }) {
  return (
    <span className={`status status-${status.toLowerCase()}`}>
      <span className="signal" aria-hidden="true" />
      {labels[status as Status] ?? status.toLowerCase().replaceAll("_", " ")}
    </span>
  );
}
