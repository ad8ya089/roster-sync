import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import type { Employee } from "../api/types";
import { ErrorNotice } from "../components/ErrorNotice";

type Transport = Employee["transport"];
export function transportPatch(
  original: Transport,
  draft: { pickupPoint: string | null; specialAssistance: string | null },
) {
  const patch: Record<string, string | null> = {};
  for (const key of ["pickupPoint", "specialAssistance"] as const)
    if (draft[key] !== original[key]) patch[key] = draft[key];
  return patch;
}
export function TransportForm({ employee }: { employee: Employee }) {
  const { request, session } = useAuth();
  const queries = useQueryClient();
  const [draft, setDraft] = useState({
    pickupPoint: employee.transport.pickupPoint,
    specialAssistance: employee.transport.specialAssistance,
  });
  const [savedVersion, setSavedVersion] = useState<string | null>(null);
  const patch = transportPatch(employee.transport, draft);
  const mutation = useMutation({
    mutationFn: () =>
      request<Employee>(
        `/employees/${encodeURIComponent(employee.employeeId)}/transport-fields`,
        { method: "PATCH", body: JSON.stringify(patch) },
      ),
    retry: false,
    onSuccess: async (updated) => {
      setSavedVersion(updated.transport.version);
      await queries.invalidateQueries({ queryKey: [session?.claims.tid] });
    },
  });
  return (
    <form
      className="stack-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (Object.keys(patch).length) mutation.mutate();
      }}
    >
      <h3>Edit transport fields</h3>
      {(
        [
          ["pickupPoint", "Pickup point"],
          ["specialAssistance", "Special assistance"],
        ] as const
      ).map(([key, label]) => (
        <div key={key}>
          <label htmlFor={key}>{label}</label>
          <div className="clear-field">
            <input
              id={key}
              value={draft[key] ?? ""}
              onChange={(event) => {
                setDraft({ ...draft, [key]: event.target.value });
                setSavedVersion(null);
              }}
            />
            <button
              className="button button-secondary"
              type="button"
              disabled={mutation.isPending || draft[key] === null}
              onClick={() => {
                setDraft({ ...draft, [key]: null });
                setSavedVersion(null);
              }}
            >
              Clear {label.toLowerCase()}
            </button>
          </div>
          {draft[key] === null && employee.transport[key] !== null && (
            <p className="help">Will be cleared on Save.</p>
          )}
        </div>
      ))}
      {mutation.error && <ErrorNotice error={mutation.error} />}
      {savedVersion !== null && (
        <p role="status">Transport fields saved. Version {savedVersion}.</p>
      )}
      <button
        className="button button-primary"
        disabled={Object.keys(patch).length === 0 || mutation.isPending}
      >
        {mutation.isPending
          ? "Saving transport fields"
          : "Save transport fields"}
      </button>
    </form>
  );
}
