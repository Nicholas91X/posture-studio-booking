import { Suspense, type ReactNode } from "react";
import { Loading } from "@/components/Notice";
import { RequirePanel, type PanelRole } from "./RequirePanel";

/** The frame of a page of the panel: the guard of its role, and the wait for the query string (useSearchParams). */
export function PanelPage({ role, children }: { role: PanelRole | readonly PanelRole[]; children: ReactNode }) {
  return (
    <Suspense fallback={<Loading />}>
      <RequirePanel role={role}>{children}</RequirePanel>
    </Suspense>
  );
}
