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
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
  },
};

export default config;
