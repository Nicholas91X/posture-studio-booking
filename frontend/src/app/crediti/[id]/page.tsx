import type { Metadata } from "next";
import { Suspense } from "react";
import { Loading } from "@/components/Notice";
import { RequireCustomer } from "@/components/RequireCustomer";
import { LotClient } from "./LotClient";

export const metadata: Metadata = { title: "Storia del credito" };

export default function LotPage() {
  return (
    <Suspense fallback={<Loading />}>
      <RequireCustomer>
        <LotClient />
      </RequireCustomer>
    </Suspense>
  );
}
