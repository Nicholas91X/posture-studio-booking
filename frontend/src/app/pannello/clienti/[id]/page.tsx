import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { CustomerClient } from "./CustomerClient";

export const metadata: Metadata = { title: "Cliente" };

export default function CustomerPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <CustomerClient />
    </PanelPage>
  );
}
