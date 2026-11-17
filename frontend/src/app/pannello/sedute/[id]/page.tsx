import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { SessionClient } from "./SessionClient";

export const metadata: Metadata = { title: "Seduta" };

export default function SessionPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <SessionClient />
    </PanelPage>
  );
}
