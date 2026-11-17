import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { AgendasClient } from "./AgendasClient";

export const metadata: Metadata = { title: "Agende a confronto" };

export default function AgendasPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <AgendasClient />
    </PanelPage>
  );
}
