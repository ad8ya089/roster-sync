import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ErrorNotice } from "../components/ErrorNotice";
import { localNow } from "../components/format";

export function UploadPanel() {
  const { request, session } = useAuth();
  const queries = useQueryClient();
  const navigate = useNavigate();
  const [asOf, setAsOf] = useState(localNow);
  const [file, setFile] = useState<File | null>(null);
  const [expected, setExpected] = useState("");
  const [dryRun, setDryRun] = useState(false);
  const mutation = useMutation({
    mutationFn: () => {
      const body = new FormData();
      body.set("file", file!);
      body.set("asOf", new Date(asOf).toISOString());
      body.set("dryRun", String(dryRun));
      if (expected !== "") body.set("expectedRowCount", expected);
      return request<{ runId: string }>("/reconciliation/runs", {
        method: "POST",
        body,
      });
    },
    retry: false,
    onSuccess: async (result) => {
      await queries.invalidateQueries({ queryKey: [session?.claims.tid] });
      navigate(`/runs/${result.runId}`);
    },
  });
  return (
    <details className="form-section">
      <summary>Upload nightly export</summary>
      <form
        className="form-grid"
        onSubmit={(event) => {
          event.preventDefault();
          mutation.mutate();
        }}
      >
        <div>
          <label htmlFor="csv-file">CSV file</label>
          <input
            id="csv-file"
            type="file"
            accept=".csv,text/csv"
            required
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
          <p className="help">UTF-8 with header, up to 100 MB.</p>
        </div>
        <div>
          <label htmlFor="export-taken">Export taken (local time)</label>
          <input
            id="export-taken"
            type="datetime-local"
            step="1"
            required
            value={asOf}
            onChange={(event) => setAsOf(event.target.value)}
          />
        </div>
        <div>
          <label htmlFor="expected-rows">Expected row count (optional)</label>
          <input
            id="expected-rows"
            type="number"
            min="0"
            step="1"
            value={expected}
            onChange={(event) => setExpected(event.target.value)}
          />
        </div>
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={dryRun}
            onChange={(event) => setDryRun(event.target.checked)}
          />
          Dry run (plan only)
        </label>
        {mutation.error && <ErrorNotice error={mutation.error} />}
        <div>
          <button
            className="button button-primary"
            disabled={!file || mutation.isPending}
          >
            {mutation.isPending ? "Uploading export" : "Upload export"}
          </button>
        </div>
      </form>
    </details>
  );
}
