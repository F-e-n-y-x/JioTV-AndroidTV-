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
 * TRUST_PROXY → Fastify `trustProxy`. Default true: the server normally sits behind Cloudflare and/or
 * Caddy, and must honour X-Forwarded-Proto/Host/For to build correct https:// URLs and to rate-limit
 * the real client IP. Accepts true/false, a hop count ("1"), or a comma list of IPs/CIDRs. Set it to
 * false when the port is exposed directly to the internet (forwarded headers would be spoofable).
 */
export function parseTrustProxy(raw: string | undefined): boolean | number | string | string[] {
  const v = (raw ?? "").trim();
  if (!v) return true;
  if (/^(true|yes|on)$/i.test(v)) return true;
  if (/^(false|no|off|0)$/i.test(v)) return false;
  if (/^\d+$/.test(v)) return Number(v);
  return v.includes(",") ? v.split(",").map((s) => s.trim()).filter(Boolean) : v;
}

/** Runtime configuration, read once from the environment. */
export const config = {
  port: Number(process.env.PORT ?? 8080),
  httpsPort: Number(process.env.HTTPS_PORT ?? 8443),
  host: process.env.HOST ?? "0.0.0.0",
  serverToken: process.env.JTV_SERVER_TOKEN ?? "",
  adminPassword: process.env.ADMIN_PASSWORD ?? "",
  // Master key that unlocks the settings/admin dashboard. When set (e.g. in .env), the dashboard is
  // ALWAYS gated — even if "no password" was chosen — and this key unlocks it.
  masterKey: process.env.MASTER_KEY ?? "",
  dataDir: path.resolve(process.env.DATA_DIR ?? "./data"),
  trustProxy: parseTrustProxy(process.env.TRUST_PROXY),
};

/** Shared Jio API constants (mirrors the Android app's JioApiClient). */
export const jio = {
  USER_AGENT: "okhttp/4.12.0",
  APP_NAME: "RJIL_JioTV",
  OS: "android",
  DEVICE_TYPE: "phone",
  HOST: "jiotvapi.media.jio.com",
};
