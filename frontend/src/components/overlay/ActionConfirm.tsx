import { useEffect, useRef } from "react";
import { ErrorNotice } from "../ErrorNotice";

export function ActionConfirm({
  title,
  description,
  pending,
  error,
  onConfirm,
  onClose,
}: {
  title: string;
  description: string;
  pending: boolean;
  error: unknown;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = ref.current!;
    dialog.showModal();
    return () => {
      dialog.close();
      previous?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className="confirm-dialog"
      aria-labelledby="action-title"
      aria-describedby="action-description"
      onCancel={(event) => {
        event.preventDefault();
        if (!pending) onClose();
      }}
    >
      <h2 id="action-title">{title}</h2>
      <p id="action-description">{description}</p>
      {error != null && <ErrorNotice error={error} />}
      <div className="dialog-actions">
        <button
          className="button button-secondary"
          disabled={pending}
          onClick={onClose}
          autoFocus
        >
          Cancel
        </button>
        <button
          className="button button-primary"
          disabled={pending}
          onClick={onConfirm}
        >
          {pending ? "Saving" : title}
        </button>
      </div>
    </dialog>
  );
}
