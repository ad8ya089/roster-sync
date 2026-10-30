import { useState } from "react";
import { useRejections } from "../api/queries";
import { ErrorNotice } from "./ErrorNotice";
export function RejectionsTable({ runId }: { runId: string }) {
  const [page, setPage] = useState(0);
  const query = useRejections(runId, true, page);
  if (query.error) return <ErrorNotice error={query.error} />;
  if (!query.data) return <p role="status">Loading rejections</p>;
  const result = query.data;
  return (
    <div>
      <div className="table-scroll">
        <table>
          <caption className="sr-only">Rejected file rows</caption>
          <thead>
            <tr>
              <th className="number">Row</th>
              <th>Employee ID</th>
              <th>Error codes</th>
            </tr>
          </thead>
          <tbody>
            {result.items.length === 0 ? (
              <tr>
                <td colSpan={3} className="empty-cell">
                  No rejected rows in this run.
                </td>
              </tr>
            ) : (
              result.items.map((row, i) => (
                <tr key={`${row.rowNumber}-${i}`}>
                  <td className="number">{row.rowNumber}</td>
                  <td className="code">{row.employeeId ?? "Not available"}</td>
                  <td className="code">{row.errorCodes.join(", ")}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      {result.totalElements > result.size && (
        <div className="pagination">
          <button
            className="button button-secondary"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous
          </button>
          <span>Page {page + 1}</span>
          <button
            className="button button-secondary"
            disabled={(page + 1) * result.size >= result.totalElements}
            onClick={() => setPage(page + 1)}
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
