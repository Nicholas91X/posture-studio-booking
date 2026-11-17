"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { brand } from "@/brand";
import { useSession, type Role } from "@/lib/session";

type NavLink = { href: string; label: string };

// the customer's site (step 12)
const SITE: readonly NavLink[] = [
  { href: "/", label: "Prenota" },
  { href: "/prenotazioni", label: "Le mie sedute" },
  { href: "/crediti", label: "Crediti" },
  { href: "/profilo", label: "Profilo" },
];

// the panel (step 13): what each role sees. Hidden is not forbidden: the backend decides (D-051)
const PANEL: Record<Exclude<Role, "CUSTOMER">, readonly NavLink[]> = {
  PRACTITIONER: [
    { href: "/pannello", label: "Agenda" },
    { href: "/pannello/richieste", label: "Richieste" },
    { href: "/pannello/prenota", label: "Prenota" },
    { href: "/pannello/clienti", label: "Clienti" },
    { href: "/pannello/altro", label: "Altro" },
  ],
  ADMIN: [
    { href: "/pannello/professionisti", label: "Professionisti" },
    { href: "/pannello/conti", label: "Conti" },
    { href: "/pannello/altro", label: "Altro" },
  ],
};

// the sections of "Altro" light it up too
const UNDER_ALTRO = ["/pannello/orari", "/pannello/catalogo", "/pannello/impostazioni"];

function isCurrent(pathname: string, href: string): boolean {
  if (href === "/") {
    return pathname === "/" || pathname.startsWith("/professionisti");
  }
  if (href === "/pannello") {
    return pathname === "/pannello" || pathname.startsWith("/pannello/sedute");
  }
  if (href === "/pannello/altro") {
    return pathname === href || UNDER_ALTRO.some((section) => pathname.startsWith(section));
  }
  return pathname.startsWith(href);
}

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { session } = useSession();
  const inPanel = pathname.startsWith("/pannello");
  const panelRole = session.state === "signed-in" && session.role !== "CUSTOMER" ? session.role : null;
  // in the panel, the links of the role that is signed in; none while the session is unknown or for a customer
  const items = inPanel ? (panelRole === null ? [] : PANEL[panelRole]) : SITE;

  const links = items.map((link) => (
    <Link key={link.href} href={link.href} aria-current={isCurrent(pathname, link.href) ? "page" : undefined}>
      {link.label}
    </Link>
  ));

  return (
    <>
      <header className="app-header">
        <div className="app-header-inner">
          <Link href={inPanel ? "/pannello" : "/"} className="brand">
            <Image src="/logo-mark.png" alt="" width={36} height={36} priority />
            <span>{inPanel ? "Pannello" : brand.name}</span>
          </Link>
          <nav className="top-nav" aria-label="Sezioni">
            {links}
          </nav>
          {session.state === "anonymous" && !pathname.startsWith("/accedi") ? (
            <Link href={`/accedi?torna=${encodeURIComponent(pathname)}`} className="small">
              Accedi
            </Link>
          ) : null}
        </div>
      </header>
      <main>{children}</main>
      {items.length > 0 ? (
        <nav className="bottom-nav" aria-label="Sezioni" style={{ gridTemplateColumns: `repeat(${items.length}, 1fr)` }}>
          {links}
        </nav>
      ) : null}
    </>
  );
}
