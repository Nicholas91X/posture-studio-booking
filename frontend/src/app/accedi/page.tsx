import type { Metadata } from "next";
import { Suspense } from "react";
import { Loading } from "@/components/Notice";
import { SignInClient } from "./SignInClient";

export const metadata: Metadata = { title: "Accedi" };

export default function SignInPage() {
  return (
    <Suspense fallback={<Loading />}>
      <SignInClient />
    </Suspense>
  );
}
