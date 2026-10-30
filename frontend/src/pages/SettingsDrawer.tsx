import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { useApiQuery } from "../api/useApiQuery";
import type { Settings } from "../api/types";
import { Drawer } from "../components/overlay/Drawer";
import { ErrorNotice } from "../components/ErrorNotice";

export function SettingsDrawer({ onClose }: { onClose: () => void }) {
  const query = useApiQuery<Settings>("/settings/reconciliation");
  return (
    <Drawer title="Reconciliation settings" onClose={onClose}>
      {query.error ? (
        <ErrorNotice error={query.error} />
      ) : query.data ? (
        <SettingsForm settings={query.data} />
      ) : (
        <p role="status">Loading settings</p>
      )}
    </Drawer>
  );
}
function SettingsForm({ settings }: { settings: Settings }) {
  const { request, session } = useAuth();
  const queries = useQueryClient();
  const [ratio, setRatio] = useState(String(settings.maxMissingRatio));
  const [count, setCount] = useState(
    settings.maxMissingCount === null ? "" : String(settings.maxMissingCount),
  );
  const mutation = useMutation({
    mutationFn: () =>
      request<Settings>("/settings/reconciliation", {
        method: "PUT",
        body: JSON.stringify({
          maxMissingRatio: Number(ratio),
          maxMissingCount: count === "" ? null : Number(count),
        }),
      }),
    retry: false,
    onSuccess: () =>
      queries.invalidateQueries({ queryKey: [session?.claims.tid] }),
  });
  return (
    <form
      className="stack-form"
      onSubmit={(event) => {
        event.preventDefault();
        mutation.mutate();
      }}
    >
      <p>
        Applies to the next run. Existing plans retain their recorded
        thresholds.
      </p>
      <p className="help">
        Source:{" "}
        {mutation.data?.source.toLowerCase() ?? settings.source.toLowerCase()}
      </p>
      <label htmlFor="missing-ratio">Maximum missing ratio (0 to 1)</label>
      <input
        id="missing-ratio"
        type="number"
        min="0.001"
        max="1"
        step="0.001"
        required
        value={ratio}
        onChange={(event) => setRatio(event.target.value)}
      />
      <label htmlFor="missing-count">Maximum missing count (optional)</label>
      <input
        id="missing-count"
        type="number"
        min="1"
        step="1"
        value={count}
        onChange={(event) => setCount(event.target.value)}
      />
      {mutation.error && <ErrorNotice error={mutation.error} />}
      {mutation.isSuccess && <p role="status">Settings saved.</p>}
      <button className="button button-primary" disabled={mutation.isPending}>
        {mutation.isPending ? "Saving settings" : "Save settings"}
      </button>
    </form>
  );
}
