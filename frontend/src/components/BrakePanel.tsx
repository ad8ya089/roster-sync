import { ShieldAlert } from "lucide-react";
import type { Brake } from "../api/types";

export type Decision = "ALL" | "SKIP_DEACTIVATIONS" | "REJECT";
export function BrakePanel({
  brake,
  canDecide,
  awaiting,
  onDecision,
}: {
  brake: Brake;
  canDecide: boolean;
  awaiting: boolean;
  onDecision?: (decision: Decision) => void;
}) {
  if (!brake.tripped) return null;
  return (
    <section className="brake-panel" aria-labelledby="brake-title">
      <div className="brake-caption">
        <ShieldAlert aria-hidden="true" />
        <h2 id="brake-title">
          {awaiting ? "A decision is needed" : "Safety brake at comparison"}
        </h2>
      </div>
      <p className="brake-message">{brake.message}</p>
      <dl className="brake-figures">
        <div>
          <dt>Active before</dt>
          <dd>{brake.activeBefore.toLocaleString("en-US")}</dd>
        </div>
        <div>
          <dt>Missing</dt>
          <dd>{brake.missingActive.toLocaleString("en-US")}</dd>
        </div>
        <div>
          <dt>Missing share</dt>
          <dd>{(brake.missingRatio * 100).toFixed(1)}%</dd>
        </div>
        <div>
          <dt>Threshold</dt>
          <dd>
            {brake.thresholdRatio === null
              ? "Not available"
              : `${(brake.thresholdRatio * 100).toFixed(1)}%`}
          </dd>
        </div>
      </dl>
      {awaiting &&
        (canDecide ? (
          <div className="brake-actions">
            <button
              className="button brake-primary"
              onClick={() => onDecision?.("ALL")}
            >
              Approve all changes
            </button>
            <button
              className="button brake-secondary"
              onClick={() => onDecision?.("SKIP_DEACTIVATIONS")}
            >
              Approve without deactivations
            </button>
            <button
              className="button brake-secondary"
              onClick={() => onDecision?.("REJECT")}
            >
              Reject run
            </button>
          </div>
        ) : (
          <p>Waiting for an operator. No changes have been applied.</p>
        ))}
    </section>
  );
}
