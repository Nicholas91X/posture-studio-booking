import type { ReactNode } from "react";
import type { ApiErrorCode } from "@/api/client";
import { messageFor } from "@/api/errors";

type Tone = "info" | "error" | "warning" | "success";

export function Notice({ tone = "info", children }: { tone?: Tone; children: ReactNode }) {
  const className = tone === "info" ? "notice" : `notice notice-${tone}`;
  return (
    <div className={className} role={tone === "error" ? "alert" : "status"}>
      {children}
    </div>
  );
}

/** Any error, as a sentence from the dictionary (errors.ts): never a technical text. */
export function ErrorNotice({ error, wording }: { error: unknown; wording?: Partial<Record<ApiErrorCode, string>> }) {
  if (error == null) {
    return null;
  }
  return (
    <Notice tone="error">
      <p>{messageFor(error, wording)}</p>
    </Notice>
  );
}

export function Loading({ label = "Caricamento…" }: { label?: string }) {
  return (
    <p className="muted" role="status">
      <span className="spinner" aria-hidden="true" /> {label}
    </p>
  );
}
