import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { CatalogClient } from "./CatalogClient";

export const metadata: Metadata = { title: "Servizi e pacchetti" };

export default function CatalogPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <CatalogClient />
    </PanelPage>
  );
}
