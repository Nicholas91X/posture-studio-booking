import type { NextConfig } from "next";

// Where the backend is, for development and the end-to-end tests only. In production Caddy sends /api/* to the
// backend and the rest here (D-017): this rewrite does the same job on a developer's computer, so the browser
// sees one origin, and the session cookie and the CSRF token behave as they will behind Caddy (D-085).
const backend = process.env.BACKEND_URL ?? "http://localhost:8080";

const config: NextConfig = {
  // a self-contained server for the Docker image of step 14
  output: "standalone",
  // the end-to-end tests run their own server next to the developer's: each in its own folder (playwright.config.ts)
  distDir: process.env.NEXT_DIST_DIR ?? ".next",
  poweredByHeader: false,
  // development only: the site is also opened on 127.0.0.1, which has cookies of its own (on localhost the cookies of
  // other projects can pass the backend's 8 KB of headers: docs/avvio.md). Without this, Next refuses the hot reload
  allowedDevOrigins: ["127.0.0.1"],
  // The service worker of the panel (step 13): never cached by the browser, so a new version arrives at the next
  // visit; and the manifest with its type, for the "add to Home screen" of the phones
  async headers() {
    return [
      {
        source: "/sw.js",
        headers: [
          { key: "Content-Type", value: "application/javascript; charset=utf-8" },
          { key: "Cache-Control", value: "no-cache, no-store, must-revalidate" },
        ],
      },
      { source: "/pannello.webmanifest", headers: [{ key: "Content-Type", value: "application/manifest+json" }] },
    ];
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
  },
};

export default config;
