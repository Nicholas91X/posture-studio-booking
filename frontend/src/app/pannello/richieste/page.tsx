import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { RequestsClient } from "./RequestsClient";

export const metadata: Metadata = { title: "Richieste" };

export default function RequestsPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <RequestsClient />
    </PanelPage>
  );
}
