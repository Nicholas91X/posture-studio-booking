import { Suspense } from "react";
import { Loading } from "@/components/Notice";
import { SlotsClient } from "./SlotsClient";

export default function SlotsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <SlotsClient />
    </Suspense>
  );
}
