import fs from "node:fs";
import path from "node:path";

/**
 * Minimal .env loader (no dependency): reads KEY=VALUE lines from a `.env` in the working directory so
 * settings like MASTER_KEY work without editing shell/systemd env. Real environment variables always
 * win over the file. Runs at import time, before `config` is built.
 */
function loadDotEnv(): void {
  // Look in the working directory AND next to the server files, so it works regardless of where the
  // process is launched from (npm start, pm2, systemd, docker). First file found wins; real env vars
  // still override the file below.
  const candidates = [
    path.resolve(process.cwd(), ".env"),
    path.resolve(__dirname, "..", ".env"),   // <server>/.env when running from dist/ or src/
  ];
  for (const p of candidates) {
    let txt: string;
    try { txt = fs.readFileSync(p, "utf8"); } catch { continue; }
    for (const line of txt.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
      if (!m) continue; // skips blanks and `# comments`
      let val = m[2].trim();
      if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
        val = val.slice(1, -1);
      }
      if (process.env[m[1]] === undefined) process.env[m[1]] = val;
    }
    break; // stop at the first .env found
  }
}
loadDotEnv();

/**
 * TRUST_PROXY → Fastify `trustProxy`. The server normally sits behind Cloudflare (cloudflared) and/or
 * Caddy, and must honour X-Forwarded-Proto/Host/For to build correct https:// URLs and to rate-limit
 * the real client IP. Default: trust those headers only from loopback / link-local / private (RFC1918,
 * ULA) peers — where cloudflared, Caddy and Docker proxies sit — so a direct internet client can't
 * spoof them. Accepts true (trust anyone), false, a hop count ("1"), or a comma list of IPs/CIDRs.
 */
export const DEFAULT_TRUST_PROXY = "loopback,linklocal,uniquelocal";
export function parseTrustProxy(raw: string | undefined): boolean | number | string | string[] {
  const v = (raw ?? "").trim();
  if (!v) return DEFAULT_TRUST_PROXY;
  if (/^(true|yes|on)$/i.test(v)) return true;
  if (/^(false|no|off|0)$/i.test(v)) return false;
  if (/^\d+$/.test(v)) return Number(v);
  return v.includes(",") ? v.split(",").map((s) => s.trim()).filter(Boolean) : v;
}

/** Runtime configuration, read once from the environment. */
export const config = {
  port: Number(process.env.PORT ?? 29180),
  httpsPort: Number(process.env.HTTPS_PORT ?? 8443),
  host: process.env.HOST ?? "0.0.0.0",
  serverToken: process.env.JTV_SERVER_TOKEN ?? "",
  adminPassword: process.env.ADMIN_PASSWORD ?? "",
  // Master key that unlocks the settings/admin dashboard. When set (e.g. in .env), the dashboard is
  // ALWAYS gated — even if "no password" was chosen — and this key unlocks it.
  masterKey: process.env.MASTER_KEY ?? "",
  dataDir: path.resolve(process.env.DATA_DIR ?? "./data"),
  trustProxy: parseTrustProxy(process.env.TRUST_PROXY),
  // LAN discovery (see discovery.ts): fixed port the app scans; 0 turns it off. The main port (default
  // the same 29180, so one port does everything) answers it too.
  discoveryPort: Number(process.env.DISCOVERY_PORT ?? 29180),
  // Name shown in the app's list of servers, and the port the app should use (the host side of a
  // Docker port mapping, when it differs from PORT).
  serverName: process.env.SERVER_NAME ?? "",
  publicPort: Number(process.env.PUBLIC_PORT ?? process.env.PORT ?? 29180),
};

/** Shared Jio API constants (mirrors the Android app's JioApiClient). */
export const jio = {
  USER_AGENT: "okhttp/4.12.0",
  APP_NAME: "RJIL_JioTV",
  OS: "android",
  DEVICE_TYPE: "phone",
  HOST: "jiotvapi.media.jio.com",
};
