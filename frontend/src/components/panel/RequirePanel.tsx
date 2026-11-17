"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { signInPath } from "@/lib/navigation";
import { homeOf, useSession, type Role } from "@/lib/session";
import { Loading, Notice } from "../Notice";

// The pages of the panel (step 13), for one role each: PRACTITIONER for their own agenda, customers and catalog,
// ADMIN for the practitioners and the payment accounts. The panel only hides: the backend answers 401/403 anyway
// (D-051). Not signed in: to the sign-in, and back here. The admin on a practitioner's page goes to their own home;
// a practitioner on the admin's pages, or a customer anywhere here, reads that it is not theirs.
export type PanelRole = Exclude<Role, "CUSTOMER">;

export function RequirePanel({ role, children }: { role: PanelRole | readonly PanelRole[]; children: ReactNode }) {
  const roles: readonly PanelRole[] = typeof role === "string" ? [role] : role;
  const { session, signOut } = useSession();
  const router = useRouter();
  const pathname = usePathname();
  const search = useSearchParams().toString();
  const here = search === "" ? pathname : `${pathname}?${search}`;

  const anonymous = session.state === "anonymous";
  const adminOnPractitionerPage = session.state === "signed-in" && session.role === "ADMIN" && !roles.includes("ADMIN");

  useEffect(() => {
    if (anonymous) {
      router.replace(signInPath(here));
    } else if (adminOnPractitionerPage) {
      router.replace(homeOf("ADMIN"));
    }
  }, [anonymous, adminOnPractitionerPage, here, router]);

  if (session.state === "loading" || anonymous || adminOnPractitionerPage) {
    return <Loading />;
  }
  if (session.role === "CUSTOMER") {
    return (
      <Notice tone="warning">
        <p>Il pannello è per i professionisti dello studio.</p>
        <div className="row">
          <Link href="/" className="button">
            Vai alle prenotazioni
          </Link>
          <button type="button" className="button button-secondary" onClick={() => void signOut()}>
            Esci
          </button>
        </div>
      </Notice>
    );
  }
  if (!roles.includes(session.role)) {
    return (
      <Notice tone="warning">
        <p>Questa sezione è per l&apos;amministrazione dello studio.</p>
        <Link href={homeOf(session.role)} className="button">
          Torna al pannello
        </Link>
      </Notice>
    );
  }
  return <>{children}</>;
}
