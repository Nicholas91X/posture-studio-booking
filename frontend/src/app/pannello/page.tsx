import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { AgendaClient } from "./AgendaClient";

export const metadata: Metadata = { title: "Agenda" };

export default function AgendaPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <AgendaClient />
    </PanelPage>
  );
}
