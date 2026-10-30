import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useApiQuery } from "../api/useApiQuery";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/client";
import { ErrorNotice } from "../components/ErrorNotice";

export function DemoToolsPage() {
  const { request, session } = useAuth();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const probe = useApiQuery<unknown[]>("/dev/faults");
  const [count, setCount] = useState("200000");
  const [truncate, setTruncate] = useState("120000");
  const [mutate, setMutate] = useState("0");
  const [drop, setDrop] = useState("0");
  const [exported, setExported] = useState<{ blob: Blob; asOf: string } | null>(
    null,
  );
  const [notice, setNotice] = useState("");
  const seed = useMutation({
    mutationFn: () =>
      request(`/dev/seed-employees?count=${count}`, { method: "POST" }),
    retry: false,
    onSuccess: async () => {
      setNotice("Employees seeded.");
      setExported(null);
      await queries.invalidateQueries({ queryKey: [session?.claims.tid] });
    },
  });
  const generate = useMutation({
    mutationFn: async () => {
      const asOf = new Date().toISOString();
      const params = new URLSearchParams({
        mutatePercent: mutate,
        dropPercent: drop,
      });
      if (truncate !== "") params.set("truncateAt", truncate);
      const blob = await request<Blob>(`/dev/export.csv?${params}`, {}, "blob");
      return { blob, asOf };
    },
    retry: false,
    onSuccess: (result) => {
      setExported(result);
      setNotice("Export generated. Downloaded CSV is ready for upload.");
      const url = URL.createObjectURL(result.blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "roster-export.csv";
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
  });
  const upload = useMutation({
    mutationFn: () => {
      const body = new FormData();
      body.set("file", exported!.blob, "roster-export.csv");
      body.set("asOf", exported!.asOf);
      return request<{ runId: string }>("/reconciliation/runs", {
        method: "POST",
        body,
      });
    },
    retry: false,
    onSuccess: async ({ runId }) => {
      await queries.invalidateQueries({ queryKey: [session?.claims.tid] });
      navigate(`/runs/${runId}`);
    },
  });
  const busy = seed.isPending || generate.isPending || upload.isPending;
  if (
    probe.error instanceof ApiError &&
    [403, 404].includes(probe.error.status)
  )
    return (
      <section className="empty-state">
        <h1>Demo tools unavailable</h1>
        <p>The demo profile is not enabled.</p>
      </section>
    );
  if (probe.error) return <ErrorNotice error={probe.error} />;
  if (!probe.data) return <p role="status">Checking demo profile</p>;
  return (
    <article>
      <header className="page-header">
        <h1>Demo tools</h1>
      </header>
      <p>
        Tenant-scoped demo data. Seeding bypasses receipts and change delivery.
        Fault injection stays in the terminal.
      </p>
      <section className="form-section">
        <h2>Seed employees</h2>
        <form
          className="filters"
          onSubmit={(event) => {
            event.preventDefault();
            seed.mutate();
          }}
        >
          <div>
            <label htmlFor="seed-count">Employee count</label>
            <input
              id="seed-count"
              type="number"
              min="1"
              max="200000"
              step="1"
              required
              value={count}
              onChange={(event) => setCount(event.target.value)}
            />
          </div>
          <button className="button button-secondary" disabled={busy}>
            {seed.isPending ? "Seeding employees" : "Seed employees"}
          </button>
        </form>
      </section>
      <section className="form-section">
        <h2>Generate export</h2>
        <form
          className="form-grid"
          onSubmit={(event) => {
            event.preventDefault();
            generate.mutate();
          }}
        >
          <div>
            <label htmlFor="truncate-at">Truncate at (optional)</label>
            <input
              id="truncate-at"
              type="number"
              min="0"
              step="1"
              value={truncate}
              onChange={(event) => setTruncate(event.target.value)}
            />
          </div>
          <div>
            <label htmlFor="mutate-percent">Mutate percent</label>
            <input
              id="mutate-percent"
              type="number"
              min="0"
              max="100"
              step="any"
              required
              value={mutate}
              onChange={(event) => setMutate(event.target.value)}
            />
          </div>
          <div>
            <label htmlFor="drop-percent">Drop percent</label>
            <input
              id="drop-percent"
              type="number"
              min="0"
              max="100"
              step="any"
              required
              value={drop}
              onChange={(event) => setDrop(event.target.value)}
            />
          </div>
          <div>
            <button className="button button-primary" disabled={busy}>
              {generate.isPending ? "Generating export" : "Generate export"}
            </button>
          </div>
        </form>
        <p className="help">
          Generate the truncated file, then upload it to review the brake. A
          200,000-row local seed demonstrates the brief's 120,000-row example.
        </p>
      </section>
      {exported && (
        <button
          className="button button-primary"
          disabled={busy}
          onClick={() => upload.mutate()}
        >
          {upload.isPending
            ? "Uploading export"
            : "Upload as reconciliation run"}
        </button>
      )}
      {notice && <p role="status">{notice}</p>}
      {[seed.error, generate.error, upload.error]
        .filter(Boolean)
        .map((error, i) => (
          <ErrorNotice key={i} error={error} />
        ))}
    </article>
  );
}
