import { useEffect, useRef } from "react";
import type { ReactNode } from "react";
import { X } from "lucide-react";

export function Drawer({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
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
      className="drawer"
      aria-labelledby="drawer-title"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <header className="section-heading">
        <h2 id="drawer-title">{title}</h2>
        <button
          className="icon-button"
          aria-label="Close panel"
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
      </header>
      {children}
    </dialog>
  );
}
