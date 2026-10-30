import { useEffect, useRef, useState } from "react";
import { useAuth } from "../auth/AuthContext";
import type { IngestResponse, Receipt } from "../api/types";
import { ErrorNotice } from "../components/ErrorNotice";
import { EventResults } from "./EventResults";
import {
  badBatch,
  eventFor,
  freshEmployee,
  nextScenario,
  outOfOrder,
} from "../scenarios/presets";
import type { ScenarioEmployee, ScenarioEvent } from "../scenarios/presets";

const json = (events: ScenarioEvent[]) => JSON.stringify({ events }, null, 2);
export function EventConsolePage() {
  const { request } = useAuth();
  const [editor, setEditor] = useState(() =>
    json([eventFor(freshEmployee(), "CREATE")]),
  );
  const [responses, setResponses] = useState<IngestResponse[]>([]);
  const [receipts, setReceipts] = useState<Receipt[]>([]);
  const [current, setCurrent] = useState<ScenarioEmployee | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState("");
  const [lastBatch, setLastBatch] = useState<string | null>(null);
  const [lastScenario, setLastScenario] = useState<ScenarioEvent[] | undefined>(
    undefined,
  );
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  // The component is remounted on navigation. In-flight HTTP may still commit; never retry POSTs.
  const bytes = new TextEncoder().encode(editor).byteLength;
  const format = () => {
    try {
      let large = false;
      const parsed = JSON.parse(editor, (key, value) => {
        if (
          key === "version" &&
          typeof value === "number" &&
          !Number.isSafeInteger(value)
        )
          large = true;
        return value;
      });
      if (!large) setEditor(JSON.stringify(parsed, null, 2));
      setNotice(
        large
          ? "Valid JSON. Large version tokens kept as entered."
          : "JSON formatted. The server validates each record.",
      );
      setError(null);
    } catch {
      setNotice(
        "Enter valid JSON before sending. Input values are not included in diagnostics.",
      );
    }
  };
  const send = async (batches: string[], scenario?: ScenarioEvent[][]) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setNotice("");
    setResponses([]);
    setReceipts([]);
    let resolved = current;
    try {
      for (let step = 0; step < batches.length; step++) {
        if (!active.current) return;
        const body = batches[step];
        setEditor(body);
        setLastBatch(body);
        setLastScenario(scenario?.[step]);
        const response = await request<IngestResponse>("/hr/events", {
          method: "POST",
          body,
        });
        if (!active.current) return;
        setResponses((previous) => [...previous, response]);
        for (const result of response.results) {
          const event = scenario?.[step]?.[result.index];
          if (
            event &&
            event.employeeId === scenario?.[0][0].employeeId &&
            ["APPLIED", "NO_CHANGE"].includes(result.outcome)
          ) {
            resolved =
              event.type === "DEACTIVATE" && resolved
                ? { ...resolved, version: event.version, status: "INACTIVE" }
                : {
                    employeeId: event.employeeId,
                    version: event.version,
                    name: String(event.name),
                    email: String(event.email),
                    phone: String(event.phone),
                    officeCode: String(event.officeCode),
                    shiftCode: String(event.shiftCode),
                    status: event.status === "INACTIVE" ? "INACTIVE" : "ACTIVE",
                  };
            setCurrent(resolved);
          }
          if (scenario && result.employeeId === scenario[0][0].employeeId)
            setReceipts((previous) => [
              ...previous,
              {
                version: result.version,
                type: scenario[step][result.index]?.type ?? "UPDATE",
                outcome: result.outcome,
                receivedAt: new Date().toISOString(),
              },
            ]);
        }
        if (step + 1 < batches.length)
          await new Promise((resolve) => setTimeout(resolve, 250));
      }
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  };
  const preset = (
    kind: "order" | "bad" | "ownership" | "no-change" | "deactivate",
  ) => {
    const steps =
      kind === "order"
        ? outOfOrder()
        : kind === "bad"
          ? [badBatch()]
          : current
            ? [[nextScenario(current, kind)]]
            : [];
    if (steps.length) void send(steps.map(json), steps);
  };
  return (
    <article>
      <header className="page-header">
        <h1>Event console</h1>
      </header>
      <div className="scenario-buttons" aria-label="Scenario presets">
        <button
          className="button button-secondary"
          disabled={busy}
          onClick={() => preset("order")}
        >
          Out-of-order (brief example)
        </button>
        <button
          className="button button-secondary"
          disabled={busy || !lastBatch}
          onClick={() => {
            if (lastBatch)
              void send([lastBatch], lastScenario ? [lastScenario] : undefined);
          }}
        >
          Replay last batch
        </button>
        <button
          className="button button-secondary"
          disabled={busy}
          onClick={() => preset("bad")}
        >
          Bad batch
        </button>
        <button
          className="button button-secondary"
          disabled={busy || !current || current.status !== "ACTIVE"}
          onClick={() => preset("ownership")}
        >
          Ownership violation
        </button>
        <button
          className="button button-secondary"
          disabled={busy || !current}
          onClick={() => preset("no-change")}
        >
          No-op higher version
        </button>
        <button
          className="button button-secondary"
          disabled={busy || !current}
          onClick={() => preset("deactivate")}
        >
          Deactivate
        </button>
      </div>
      <p className="help">
        Presets send fresh UI-prefixed employees in this tenant. Follow-up
        presets use only successful session state. Brief offices BLR-1 and BLR-2
        must be active; errors remain visible if unavailable.
      </p>
      <div className="event-columns">
        <section>
          <h2>Batch</h2>
          <label htmlFor="event-json">Event JSON</label>
          <textarea
            id="event-json"
            className="json-editor"
            spellCheck={false}
            rows={22}
            value={editor}
            disabled={busy}
            onChange={(event) => setEditor(event.target.value)}
          />
          <p className="help">
            1–1,000 events, up to 2 MB. Current body:{" "}
            {bytes.toLocaleString("en-US")} bytes.
          </p>
          <div className="scenario-buttons">
            <button
              className="button button-secondary"
              disabled={busy}
              onClick={format}
            >
              Format / validate JSON
            </button>
            <button
              className="button button-primary"
              disabled={busy || !editor.trim()}
              onClick={() => void send([editor])}
            >
              {busy ? "Sending events" : "Send batch"}
            </button>
          </div>
          {notice && <p role="status">{notice}</p>}
          {error != null && <ErrorNotice error={error} />}
        </section>
        <EventResults
          responses={responses}
          receipts={receipts}
          currentVersion={String(current?.version ?? "")}
        />
      </div>
    </article>
  );
}
