"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { signInPath } from "@/lib/navigation";
import { isCustomer, useSession } from "@/lib/session";
import { Loading, Notice } from "./Notice";

// The pages of a signed-in customer: whoever is not signed in goes to the sign-in, and then comes back here, with
// the same query (e.g. ?pagamento=completato from Stripe). A first sign-in without names goes to finish them.
export function RequireCustomer({ children }: { children: ReactNode }) {
  const { session, signOut } = useSession();
  const router = useRouter();
  const pathname = usePathname();
  const search = useSearchParams().toString();
  const here = search === "" ? pathname : `${pathname}?${search}`;

  const mustSignIn =
    session.state === "anonymous" || (session.state === "signed-in" && session.role === "CUSTOMER" && session.profileIncomplete);

  useEffect(() => {
    if (mustSignIn) {
      router.replace(signInPath(here));
    }
  }, [mustSignIn, here, router]);

  if (session.state === "loading" || mustSignIn) {
    return <Loading />;
  }
  // an admin or a practitioner on the customer's site: the profile (with its "Esci") is not theirs, so the way out
  // is here, or they would stay signed in with no button to leave
  if (!isCustomer(session)) {
    return (
      <Notice tone="warning">
        <p>Questa sezione è per i clienti. Il tuo account è di un professionista o dell&apos;amministrazione: il pannello arriverà presto.</p>
        <button type="button" className="button button-secondary" onClick={() => void signOut()}>
          Esci
        </button>
      </Notice>
    );
  }
  return <>{children}</>;
}
