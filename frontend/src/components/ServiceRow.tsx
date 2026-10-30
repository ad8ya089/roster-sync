import { CircleCheck, CircleAlert, CirclePause } from "lucide-react";
import type { ReactNode } from "react";
export type ServiceStatus =
  "Running normally" | "Delayed" | "Needs a decision" | "Stopped";
export function ServiceRow({
  name,
  status,
  sentence,
  figures,
  action,
}: {
  name: string;
  status: ServiceStatus;
  sentence: string;
  figures: string;
  action?: ReactNode;
}) {
  const Icon =
    status === "Running normally"
      ? CircleCheck
      : status === "Stopped"
        ? CirclePause
        : CircleAlert;
  return (
    <div className="service-row">
      <div>
        <h2>{name}</h2>
        <span
          className={`service-signal ${status === "Running normally" ? "service-normal" : status === "Stopped" ? "service-stopped" : "service-delayed"}`}
        >
          <Icon aria-hidden="true" />
          {status}
        </span>
      </div>
      <p>{sentence}</p>
      <span className="number">{figures}</span>
      <div>{action}</div>
    </div>
  );
}
