import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TransportForm, transportPatch } from "../../src/pages/TransportForm";
import { rejectionShare } from "../../src/pages/OpsDashboardPage";
import {
  badBatch,
  nextScenario,
  outOfOrder,
} from "../../src/scenarios/presets";
import { createClient } from "../../src/api/client";
import { EmployeeFields } from "../../src/pages/EmployeeFields";
import type { Employee } from "../../src/api/types";

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock("../../src/auth/AuthContext", () => ({
  useAuth: () => ({
    request,
    session: { claims: { tid: "tenant-test", role: "TRANSPORT_ADMIN" } },
  }),
}));
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  request.mockReset();
});
const employee: Employee = {
  employeeId: "UI-TEST",
  status: "INACTIVE",
  hrVersion: "9223372036854775807",
  name: null,
  email: null,
  phone: null,
  managerId: null,
  officeCode: null,
  shiftCode: null,
  transport: {
    pickupPoint: "Canary Lane",
    specialAssistance: "Support",
    version: "3",
  },
  hrUpdatedAt: "2026-10-31T00:00:00Z",
  deactivatedAt: "2026-10-31T00:00:00Z",
};
function form() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            mutations: { retry: false },
            queries: { retry: false },
          },
        })
      }
    >
      <TransportForm employee={employee} />
    </QueryClientProvider>,
  );
}
describe("transport intent", () => {
  it("omits unchanged keys and retains explicit null", () => {
    expect(
      transportPatch(employee.transport, {
        pickupPoint: employee.transport.pickupPoint,
        specialAssistance: employee.transport.specialAssistance,
      }),
    ).toEqual({});
    expect(
      transportPatch(employee.transport, {
        pickupPoint: null,
        specialAssistance: employee.transport.specialAssistance,
      }),
    ).toEqual({ pickupPoint: null });
    expect(
      transportPatch(employee.transport, {
        pickupPoint: "",
        specialAssistance: employee.transport.specialAssistance,
      }),
    ).toEqual({ pickupPoint: "" });
  });
  it("disables no-op save without a request", () => {
    form();
    expect(
      screen.getByRole("button", { name: "Save transport fields" }),
    ).toBeDisabled();
    expect(request).not.toHaveBeenCalled();
  });
  it("sends null on Clear, excludes untouched fields, shows returned version", async () => {
    request.mockResolvedValue({
      ...employee,
      transport: { ...employee.transport, pickupPoint: null, version: "4" },
    });
    form();
    fireEvent.click(screen.getByRole("button", { name: "Clear pickup point" }));
    fireEvent.click(
      screen.getByRole("button", { name: "Save transport fields" }),
    );
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
    expect(request.mock.calls[0]).toEqual([
      "/employees/UI-TEST/transport-fields",
      { method: "PATCH", body: '{"pickupPoint":null}' },
    ]);
    expect(await screen.findByRole("status")).toHaveTextContent("Version 4");
  });
  it("displays tombstone fields and exact versions without numeric coercion", () => {
    render(<EmployeeFields employee={employee} />);
    expect(screen.getByText("9223372036854775807")).toBeInTheDocument();
    expect(screen.getAllByText("Not set").length).toBe(6);
  });
});
describe("board and presets", () => {
  it("counts only durable outcomes and handles zero", () => {
    expect(
      rejectionShare({
        applied: 0,
        stale: 0,
        noChange: 0,
        rejected: 0,
        conflict: 0,
      }),
    ).toBe(0);
    expect(
      rejectionShare({
        applied: 5,
        stale: 1,
        noChange: 1,
        rejected: 1,
        conflict: 2,
      }),
    ).toBe(0.1);
    expect(
      rejectionShare({
        applied: 95,
        stale: 0,
        noChange: 0,
        rejected: 5,
        conflict: 0,
      }),
    ).toBe(0.05);
  });
  it("uses fresh ids and exact brief versions, offices and shifts", () => {
    const first = outOfOrder();
    const second = outOfOrder();
    expect(first.map((step) => step[0].version)).toEqual([5, 7, 6]);
    expect(first.map((step) => step[0].officeCode)).toEqual([
      "BLR-1",
      "BLR-2",
      "BLR-1",
    ]);
    expect(first[2][0].shiftCode).toBe("S3");
    expect(first[0][0].employeeId).not.toBe(second[0][0].employeeId);
  });
  it("isolates the two bad rows without reusing identifiers", () => {
    const events = badBatch();
    expect(events).toHaveLength(5);
    expect(new Set(events.map((event) => event.employeeId)).size).toBe(5);
    expect(events[3].phone).toContain(" ");
    expect(events[4].officeCode).toBe("UNKNOWN-UI");
  });
  it("preserves no-op content and changes an HR field for ownership", () => {
    const state = {
      employeeId: "UI-one",
      version: 7,
      name: "UI Example",
      email: "ui@example.test",
      phone: "+919800000001",
      officeCode: "BLR-2",
      shiftCode: "S1",
      status: "ACTIVE" as const,
    };
    expect(nextScenario(state, "no-change")).toMatchObject({
      ...state,
      version: 8,
    });
    expect(nextScenario(state, "ownership")).toMatchObject({
      version: 8,
      shiftCode: "S2",
      pickupPoint: "Ignored transport value",
    });
    expect(nextScenario(state, "deactivate")).not.toHaveProperty("name");
  });
});
it("downloads an authenticated CSV as a Blob", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue(
      new Response("employee_id,version\n", {
        headers: { "Content-Type": "text/csv" },
      }),
    );
  vi.stubGlobal("fetch", fetch);
  const blob = await createClient("test-token")<Blob>(
    "/dev/export.csv",
    {},
    "blob",
  );
  expect(await blob.text()).toBe("employee_id,version\n");
  expect(fetch.mock.calls[0][1].headers.get("Authorization")).toBe(
    "Bearer test-token",
  );
});
