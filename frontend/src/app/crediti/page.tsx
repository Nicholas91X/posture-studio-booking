import type { Metadata } from "next";
import { Suspense } from "react";
import { Loading } from "@/components/Notice";
import { RequireCustomer } from "@/components/RequireCustomer";
import { CreditsClient } from "./CreditsClient";

export const metadata: Metadata = { title: "I tuoi crediti" };

// Also the page Stripe sends the customer back to after a package: /crediti?acquisto=completato|annullato (D-070)
export default function CreditsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <RequireCustomer>
        <CreditsClient />
      </RequireCustomer>
    </Suspense>
  );
}
