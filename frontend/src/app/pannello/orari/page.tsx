import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { HoursClient } from "./HoursClient";

export const metadata: Metadata = { title: "Orari e assenze" };

export default function HoursPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <HoursClient />
    </PanelPage>
  );
}
