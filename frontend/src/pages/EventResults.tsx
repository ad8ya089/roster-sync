import type { IngestResponse, Receipt } from "../api/types";
import { OutcomeBadge } from "../components/OutcomeBadge";
import { VersionTimeline } from "../components/RouteLine";
export function EventResults({
  responses,
  receipts,
  currentVersion,
}: {
  responses: IngestResponse[];
  receipts: Receipt[];
  currentVersion: string;
}) {
  return (
    <section aria-label="Event response">
      <h2>Response</h2>
      {responses.length === 0 ? (
        <p>Send a batch to see server outcomes.</p>
      ) : (
        <>
          {responses.map((response, step) => (
            <section key={step} className="event-response">
              <h3>Step {step + 1}</h3>
              <dl className="summary-strip">
                {Object.entries(response.summary).map(([key, value]) => (
                  <div key={key}>
                    <dt>{key.replace(/([A-Z])/g, " $1").toLowerCase()}</dt>
                    <dd>{value}</dd>
                  </div>
                ))}
              </dl>
              <div className="table-scroll">
                <table>
                  <caption>Record outcomes, step {step + 1}</caption>
                  <thead>
                    <tr>
                      <th className="number">Index</th>
                      <th>Employee ID</th>
                      <th>Version</th>
                      <th>Outcome</th>
                      <th>Current / original</th>
                      <th>Warnings / errors</th>
                    </tr>
                  </thead>
                  <tbody>
                    {response.results.map((result) => (
                      <tr key={result.index}>
                        <td className="number">{result.index}</td>
                        <td className="code">
                          {result.employeeId ?? "Not available"}
                        </td>
                        <td className="code">{result.version}</td>
                        <td>
                          <OutcomeBadge outcome={result.outcome} />
                        </td>
                        <td>
                          {result.currentVersion ?? "None"} /{" "}
                          {result.originalOutcome
                            ?.toLowerCase()
                            .replaceAll("_", " ") ?? "None"}
                        </td>
                        <td>
                          {[
                            ...(result.warnings ?? []),
                            ...(result.errors ?? []),
                          ].map((field, i) => (
                            <span className="field-code" key={i}>
                              {field.field}: {field.code}
                            </span>
                          ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          ))}
          {receipts.length > 0 && (
            <>
              <VersionTimeline
                receipts={receipts}
                currentVersion={currentVersion}
              />
              <p className="help">
                Session results for this employee only, drawn in arrival order.
                Server-side history: Employees screen (admin/operator).
              </p>
            </>
          )}
        </>
      )}
    </section>
  );
}
