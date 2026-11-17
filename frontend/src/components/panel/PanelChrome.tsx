"use client";

import type { ReactNode } from "react";
import { useSession } from "@/lib/session";
import { NotificationPrompt } from "./Notifications";

// Around every page of the panel: the notifications popup, for a practitioner only (the admin receives no pushes)
export function PanelChrome({ children }: { children: ReactNode }) {
  const { session } = useSession();
  return (
    <>
      {children}
      {session.state === "signed-in" && session.role === "PRACTITIONER" ? <NotificationPrompt /> : null}
    </>
  );
}
