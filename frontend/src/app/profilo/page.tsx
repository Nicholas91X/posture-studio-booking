import type { Metadata } from "next";
import { Suspense } from "react";
import { Loading } from "@/components/Notice";
import { RequireCustomer } from "@/components/RequireCustomer";
import { ProfileClient } from "./ProfileClient";

export const metadata: Metadata = { title: "Profilo" };

export default function ProfilePage() {
  return (
    <Suspense fallback={<Loading />}>
      <RequireCustomer>
        <ProfileClient />
      </RequireCustomer>
    </Suspense>
  );
}
