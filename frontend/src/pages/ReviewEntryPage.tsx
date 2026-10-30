import { useState } from "react";
import { useNavigate } from "react-router-dom";
export function ReviewEntryPage() {
  const [runId, setRunId] = useState("");
  const navigate = useNavigate();
  return (
    <section className="review-entry">
      <p className="breadcrumb">Reconciliation</p>
      <h1>Review a run</h1>
      <p>Open a saved comparison to review its plan and safety brake.</p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          navigate(`/runs/${encodeURIComponent(runId.trim())}`);
        }}
      >
        <label htmlFor="run-id">Run ID</label>
        <input
          id="run-id"
          className="code"
          required
          pattern="[a-fA-F0-9-]{36}"
          value={runId}
          onChange={(event) => setRunId(event.target.value)}
        />
        <button className="button button-primary">Open run</button>
      </form>
    </section>
  );
}
