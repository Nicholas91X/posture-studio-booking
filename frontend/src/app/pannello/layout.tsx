import type { Metadata } from "next";
import type { ReactNode } from "react";
import { PanelChrome } from "@/components/panel/PanelChrome";

// The panel (step 13): the same application as the site, installable on the phone from these pages only. The manifest
// starts it on the agenda; on iPhone "capable" makes the icon on the Home screen open it as an app (D-032)
export const metadata: Metadata = {
  title: { default: "Pannello", template: "%s · Pannello" },
  manifest: "/pannello.webmanifest",
  appleWebApp: { capable: true, title: "Pannello", statusBarStyle: "default" },
};

export default function PanelLayout({ children }: { children: ReactNode }) {
  return <PanelChrome>{children}</PanelChrome>;
}
