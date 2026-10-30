import { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import type { Decision } from "../BrakePanel";
import { ErrorNotice } from "../ErrorNotice";

const copy: Record<
  Decision,
  { title: string; description: string; action: string }
> = {
  ALL: {
    title: "Approve all changes",
    description:
      "Apply the entire plan, including employee deactivations and their downstream booking effects.",
    action: "Approve all changes",
  },
  SKIP_DEACTIVATIONS: {
    title: "Approve without deactivations",
    description:
      "Apply creates, updates, heals and version advances. Keep employees missing from the file active.",
    action: "Approve without deactivations",
  },
  REJECT: {
    title: "Reject run",
    description:
      "Discard this plan. The current roster stays as it is. HR will need to upload a corrected export.",
    action: "Reject run",
  },
};
export function ConfirmDialog({
  decision,
  pending,
  error,
  onConfirm,
  onDismiss,
}: {
  decision: Decision;
  pending: boolean;
  error: unknown;
  onConfirm: (note: string) => void;
  onDismiss: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [note, setNote] = useState("");
  const text = copy[decision];
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => element?.close();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="confirm-dialog"
      aria-labelledby="decision-title"
      aria-describedby="decision-description"
      onCancel={(event) => {
        if (pending) event.preventDefault();
      }}
      onClose={onDismiss}
    >
      <div className="section-heading">
        <h2 id="decision-title">{text.title}</h2>
        <button
          className="icon-button"
          aria-label="Close decision"
          disabled={pending}
          onClick={onDismiss}
        >
          <X aria-hidden="true" />
        </button>
      </div>
      <p id="decision-description">{text.description}</p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (note.trim()) onConfirm(note.trim());
        }}
      >
        <label htmlFor="decision-note">Decision note</label>
        <textarea
          id="decision-note"
          value={note}
          onChange={(event) => setNote(event.target.value)}
          maxLength={500}
          required
          rows={4}
        />
        <p className="help">
          Required. Record the reason, without personal details.
        </p>
        {error != null && <ErrorNotice error={error} />}
        <div className="dialog-actions">
          <button
            type="button"
            className="button button-secondary"
            disabled={pending}
            onClick={onDismiss}
          >
            Cancel
          </button>
          <button
            className="button button-primary"
            disabled={pending || !note.trim()}
          >
            {pending ? "Saving decision" : text.action}
          </button>
        </div>
      </form>
    </dialog>
  );
}
