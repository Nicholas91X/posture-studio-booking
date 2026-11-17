import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { SettingsClient } from "./SettingsClient";

export const metadata: Metadata = { title: "Impostazioni" };

export default function SettingsPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <SettingsClient />
    </PanelPage>
  );
}
