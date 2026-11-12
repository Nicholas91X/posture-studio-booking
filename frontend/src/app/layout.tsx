import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { brand } from "@/brand";
import { AppShell } from "@/components/AppShell";
import { SessionProvider } from "@/lib/session";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: brand.name, template: `%s · ${brand.shortName}` },
  description: "Prenota la tua seduta, paga online o con un credito, gestisci le tue sedute.",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#1f2224" },
  ],
};

// Every page is a shell built on the server, with the data read by the browser through /api (D-085)
export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="it">
      <body>
        <SessionProvider>
          <AppShell>{children}</AppShell>
        </SessionProvider>
      </body>
    </html>
  );
}
