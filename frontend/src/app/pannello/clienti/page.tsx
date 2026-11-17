import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { CustomersClient } from "./CustomersClient";

export const metadata: Metadata = { title: "I miei clienti" };

export default function CustomersPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <CustomersClient />
    </PanelPage>
  );
}
