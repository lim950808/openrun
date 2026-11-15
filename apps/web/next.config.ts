import path from "node:path";
import type { NextConfig } from "next";

/**
 * Same-origin API (ADR-0008 §1): the browser calls `/api/*`, Next rewrites to API_BASE_URL. Rewrites are resolved at
 * `next build` and baked into the standalone server, so set API_BASE_URL when building (the Dockerfile takes it as a
 * build arg).
 */
const apiBaseUrl = process.env.API_BASE_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  output: "standalone",
  // This app is its own npm root; without this Next may pick a lockfile above the repo and nest the standalone output.
  outputFileTracingRoot: path.join(__dirname),
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiBaseUrl}/api/:path*` }];
  },
};

export default nextConfig;
