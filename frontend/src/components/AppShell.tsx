"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { brand } from "@/brand";
import { useSession } from "@/lib/session";

const LINKS = [
  { href: "/", label: "Prenota" },
  { href: "/prenotazioni", label: "Le mie sedute" },
  { href: "/crediti", label: "Crediti" },
  { href: "/profilo", label: "Profilo" },
] as const;

function isCurrent(pathname: string, href: string): boolean {
  return href === "/" ? pathname === "/" || pathname.startsWith("/professionisti") : pathname.startsWith(href);
}

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { session } = useSession();

  const links = LINKS.map((link) => (
    <Link key={link.href} href={link.href} aria-current={isCurrent(pathname, link.href) ? "page" : undefined}>
      {link.label}
    </Link>
  ));

  return (
    <>
      <header className="app-header">
        <div className="app-header-inner">
          <Link href="/" className="brand">
            <Image src="/logo-mark.png" alt="" width={36} height={36} priority />
            <span>{brand.name}</span>
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
      <nav className="bottom-nav" aria-label="Sezioni">
        {links}
      </nav>
    </>
  );
}
