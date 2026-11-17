import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { PractitionerAdminClient } from "./PractitionerAdminClient";

export const metadata: Metadata = { title: "Professionista" };

export default function PractitionerAdminPage() {
  return (
    <PanelPage role="ADMIN">
      <PractitionerAdminClient />
    </PanelPage>
  );
}
