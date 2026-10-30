import { describe, expect, it, vi, afterEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ApiError, createClient, parseApiJson } from "../../src/api/client";
import { AuthProvider, useAuth } from "../../src/auth/AuthContext";
import { RequireRole } from "../../src/auth/RequireRole";
import { BrakePanel } from "../../src/components/BrakePanel";
import { OutcomeBadge } from "../../src/components/OutcomeBadge";
import { planTotal } from "../../src/components/PlanBar";
import { firstArrivals, VersionTimeline } from "../../src/components/RouteLine";
import type { Outcome, Receipt } from "../../src/api/types";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("API boundary", () => {
  it("preserves Long.MAX_VALUE from source text as a string at every version key", () => {
    expect(
      parseApiJson(
        '{"hrVersion":9223372036854775807,"transport":{"version":9007199254740993},"currentVersion":7,"rows":120000}',
      ),
    ).toEqual({
      hrVersion: "9223372036854775807",
      transport: { version: "9007199254740993" },
      currentVersion: "7",
      rows: 120000,
    });
  });
  it("does not alter null or already textual versions", () => {
    expect(parseApiJson('{"version":null,"hrVersion":"7"}')).toEqual({
      version: null,
      hrVersion: "7",
    });
  });
  it("surfaces problem details and the correlation header without echoing a body", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            '{"code":"INVALID_RUN_STATE","title":"Run changed","detail":"Refresh the run","errors":[]}',
            {
              status: 409,
              headers: { "X-Correlation-Id": "test-correlation" },
            },
          ),
        ),
    );
    await expect(
      createClient("signed-token")("/reconciliation/runs/id/reject", {
        method: "POST",
        body: "{}",
      }),
    ).rejects.toMatchObject({
      status: 409,
      code: "INVALID_RUN_STATE",
      correlationId: "test-correlation",
    });
    expect(fetch).toHaveBeenCalledOnce();
    const options = vi.mocked(fetch).mock.calls[0][1]!;
    expect(new Headers(options.headers).get("Authorization")).toBe(
      "Bearer signed-token",
    );
    expect(options.credentials).toBe("omit");
  });
  it("expires authenticated sessions on 401 without retrying", async () => {
    const expired = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response('{"code":"TOKEN_EXPIRED"}', { status: 401 }),
        ),
    );
    await expect(
      createClient("token", expired)("/reconciliation/runs/id"),
    ).rejects.toBeInstanceOf(ApiError);
    expect(expired).toHaveBeenCalledOnce();
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("accepts the empty 204 used by operation endpoints", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    await expect(
      createClient()("/ops/alerts/1/ack", { method: "POST" }),
    ).resolves.toBeUndefined();
  });
});

const brake = {
  tripped: true,
  reason: "MISSING_RATIO",
  reasons: ["MISSING_RATIO"],
  activeBefore: 10,
  missingActive: 4,
  missingRatio: 0.4,
  thresholdRatio: 0.3,
  thresholdCount: null,
  message: "Server-authoritative brake message.",
};
describe("operator display", () => {
  it.each([
    ["APPLIED", "Applied"],
    ["STALE", "Stale"],
    ["DUPLICATE", "Duplicate"],
    ["NO_CHANGE", "No change"],
    ["REJECTED", "Rejected"],
    ["CONFLICT", "Conflict"],
    ["RETRYABLE_ERROR", "Retryable error"],
  ] as [Outcome, string][])("labels %s", (outcome, label) => {
    render(<OutcomeBadge outcome={outcome} />);
    expect(screen.getByText(label)).toBeVisible();
  });
  it("shows the server brake sentence verbatim and hides decisions for non-operators", () => {
    render(<BrakePanel brake={brake} awaiting canDecide={false} />);
    expect(screen.getByText(brake.message)).toBeVisible();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
  it("does not offer a decision for an already decided run", () => {
    render(<BrakePanel brake={brake} awaiting={false} canDecide />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
  it("sums all mutually exclusive plan categories, including advances", () => {
    expect(
      planTotal({
        creates: 1,
        updates: 2,
        heals: 3,
        advances: 4,
        deactivations: 5,
        unchanged: 6,
        staleSkipped: 7,
      }),
    ).toBe(28);
  });
  it("sorts arrivals by timestamp, preserving versions as text and input immutability", () => {
    const receipts: Receipt[] = [
      {
        version: "7",
        type: "UPDATE",
        outcome: "APPLIED",
        receivedAt: "2026-10-31T01:00:00.100Z",
      },
      {
        version: "6",
        type: "UPDATE",
        outcome: "STALE",
        receivedAt: "2026-10-31T01:00:00.101Z",
      },
      {
        version: "5",
        type: "CREATE",
        outcome: "APPLIED",
        receivedAt: "2026-10-31T01:00:00Z",
      },
    ];
    expect(firstArrivals(receipts).map((r) => r.version)).toEqual([
      "5",
      "7",
      "6",
    ]);
    expect(receipts[0].version).toBe("7");
    render(<VersionTimeline receipts={receipts} currentVersion="7" />);
    expect(
      screen.getByRole("heading", { name: "First arrival of each version" }),
    ).toBeVisible();
    expect(screen.getByText("v6").closest("li")).toHaveClass("route-branch");
  });
});

it("keeps tokens in memory, gates roles and clears tenant data on logout", async () => {
  const setStorage = vi.spyOn(Storage.prototype, "setItem");
  const claims = btoa(
    JSON.stringify({
      sub: "fixture",
      tid: "tenant",
      tcode: "acme",
      role: "EMPLOYEE",
      emp: "E1001",
    }),
  );
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ accessToken: `header.${claims}.signature` }),
        ),
      ),
  );
  const queries = new QueryClient();
  queries.setQueryData(["old-tenant", "employee"], { private: true });
  function Harness() {
    const auth = useAuth();
    return (
      <>
        <button
          onClick={() => {
            void auth.login("fixture", "fixture");
          }}
        >
          Sign in
        </button>
        <button onClick={auth.logout}>Sign out</button>
        {auth.session && (
          <RequireRole roles={["OPERATOR"]}>
            <p>Operator screen</p>
          </RequireRole>
        )}
      </>
    );
  }
  render(
    <QueryClientProvider client={queries}>
      <AuthProvider>
        <MemoryRouter>
          <Harness />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
  fireEvent.click(screen.getByText("Sign in"));
  await screen.findByText("Access restricted");
  expect(screen.queryByText("Operator screen")).not.toBeInTheDocument();
  queries.setQueryData(["tenant", "employee"], { private: true });
  fireEvent.click(screen.getByText("Sign out"));
  await waitFor(() => expect(queries.getQueryCache().getAll()).toHaveLength(0));
  expect(setStorage).not.toHaveBeenCalled();
});
