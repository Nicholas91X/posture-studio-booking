import type { Metadata } from "next";
import { Suspense } from "react";
import { Loading } from "@/components/Notice";
import { RequireCustomer } from "@/components/RequireCustomer";
import { BookingsClient } from "./BookingsClient";

export const metadata: Metadata = { title: "Le mie sedute" };

export default function BookingsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <RequireCustomer>
        <BookingsClient />
      </RequireCustomer>
    </Suspense>
  );
}
