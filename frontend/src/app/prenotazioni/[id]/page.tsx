import type { Metadata } from "next";
import { Suspense } from "react";
import { Loading } from "@/components/Notice";
import { RequireCustomer } from "@/components/RequireCustomer";
import { BookingClient } from "./BookingClient";

export const metadata: Metadata = { title: "La tua seduta" };

// Also the page Stripe sends the customer back to: /prenotazioni/{id}?pagamento=completato|annullato (D-061)
export default function BookingPage() {
  return (
    <Suspense fallback={<Loading />}>
      <RequireCustomer>
        <BookingClient />
      </RequireCustomer>
    </Suspense>
  );
}
