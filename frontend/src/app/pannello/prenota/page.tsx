import type { Metadata } from "next";
import { PanelPage } from "@/components/panel/PanelPage";
import { BookForCustomerClient } from "./BookForCustomerClient";

export const metadata: Metadata = { title: "Prenota per un cliente" };

export default function BookForCustomerPage() {
  return (
    <PanelPage role="PRACTITIONER">
      <BookForCustomerClient />
    </PanelPage>
  );
}
