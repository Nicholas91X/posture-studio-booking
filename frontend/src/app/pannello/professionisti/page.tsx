import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { PractitionersClient } from "./PractitionersClient";

export const metadata: Metadata = { title: "Professionisti" };

export default function PractitionersPage() {
  return (
    <PanelPage role="ADMIN">
      <PractitionersClient />
    </PanelPage>
  );
}
