import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";
import type { ReactNode } from "react";
import { brand } from "@/brand";
import { AppShell } from "@/components/AppShell";
import { SessionProvider } from "@/lib/session";
import "./globals.css";

// The heading face of the studio's site (D-086), served by us: no request to Google Fonts from the customer's phone.
// Latin subset only (Italian needs nothing more), variable weight 400–900, SIL Open Font License
const playfair = localFont({
  src: "./fonts/PlayfairDisplay-latin.woff2",
  weight: "400 900",
  display: "swap",
  variable: "--font-playfair",
});

export const metadata: Metadata = {
  title: { default: brand.name, template: `%s · ${brand.shortName}` },
  description: "Prenota la tua seduta, paga online o con un credito, gestisci le tue sedute.",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#fffdf8" },
    { media: "(prefers-color-scheme: dark)", color: "#262019" },
  ],
};

// Every page is a shell built on the server, with the data read by the browser through /api (D-085)
export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="it" className={playfair.variable}>
      <body>
        <SessionProvider>
          <AppShell>{children}</AppShell>
        </SessionProvider>
      </body>
    </html>
  );
}
