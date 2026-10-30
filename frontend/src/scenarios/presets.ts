export interface ScenarioEmployee {
  employeeId: string;
  version: number;
  name: string;
  email: string;
  phone: string;
  officeCode: string;
  shiftCode: string;
  status: "ACTIVE" | "INACTIVE";
}
export type ScenarioEvent = Record<string, unknown> & {
  employeeId: string;
  version: number;
  type: string;
};
export const freshEmployee = (): ScenarioEmployee => {
  const id = `UI-${crypto.randomUUID()}`;
  return {
    employeeId: id,
    version: 5,
    name: "UI Example",
    email: `${id.toLowerCase()}@example.test`,
    phone: "+919800000001",
    officeCode: "BLR-1",
    shiftCode: "S1",
    status: "ACTIVE",
  };
};
export function eventFor(
  employee: ScenarioEmployee,
  type = "UPDATE",
): ScenarioEvent {
  return {
    ...employee,
    type,
    eventId: crypto.randomUUID(),
    occurredAt: new Date().toISOString(),
  };
}
export function outOfOrder() {
  const base = freshEmployee();
  return [
    [eventFor(base, "CREATE")],
    [eventFor({ ...base, version: 7, officeCode: "BLR-2" })],
    [eventFor({ ...base, version: 6, shiftCode: "S3" })],
  ];
}
export function badBatch() {
  return [
    ...Array.from({ length: 3 }, () => eventFor(freshEmployee(), "CREATE")),
    eventFor({ ...freshEmployee(), phone: "+91 9800-000001" }, "CREATE"),
    eventFor({ ...freshEmployee(), officeCode: "UNKNOWN-UI" }, "CREATE"),
  ];
}
export function nextScenario(
  current: ScenarioEmployee,
  kind: "ownership" | "no-change" | "deactivate",
) {
  const next = { ...current, version: current.version + 1 };
  if (kind === "deactivate")
    return {
      employeeId: next.employeeId,
      version: next.version,
      type: "DEACTIVATE",
      eventId: crypto.randomUUID(),
    } satisfies ScenarioEvent;
  if (kind === "ownership")
    return {
      ...eventFor({
        ...next,
        shiftCode: next.shiftCode === "S1" ? "S2" : "S1",
      }),
      pickupPoint: "Ignored transport value",
    };
  return eventFor(next);
}
