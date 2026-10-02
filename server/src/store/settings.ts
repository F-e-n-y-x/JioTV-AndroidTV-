import fs from "node:fs";
import path from "node:path";
import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import { config } from "../config";

/**
 * File-backed server config so login/setup is done in the browser — no .env editing required
 * (same philosophy as jiotv_go's credentials file). Persists the admin password hash + the TV access
 * token to `<DATA_DIR>/config.json`. Env vars (ADMIN_PASSWORD / JTV_SERVER_TOKEN) still work as
 * overrides for advanced/headless setups and take precedence over the file.
 */

interface FileConfig {
  adminPasswordHash?: string;
  // When true, the user explicitly chose "no password" — the dashboard and TV endpoints are open.
  authDisabled?: boolean;
  // EPG source: "native" (per-channel Jio EPG) or "xmltv" (a downloaded XMLTV guide).
  epgMode?: "native" | "xmltv";
  epgUrl?: string;
}

const DEFAULT_EPG_URL = "https://avkb.short.gy/epg.xml.gz";

export function getEpgConfig(): { mode: "native" | "xmltv"; url: string } {
  const c = load();
  return { mode: c.epgMode ?? "native", url: c.epgUrl ?? DEFAULT_EPG_URL };
}

export function setEpgConfig(mode: "native" | "xmltv", url: string): void {
  const c = load();
  c.epgMode = mode;
  c.epgUrl = url || DEFAULT_EPG_URL;
  save(c);
}

const file = path.join(config.dataDir, "config.json");
let cache: FileConfig | null = null;

function load(): FileConfig {
  if (cache) return cache;
  let text: string;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch (err) {
    // No config yet (first run) is normal. Anything else (permissions, I/O) must not silently wipe the
    // admin password and re-open first-run setup to whoever reaches the port first.
    if ((err as NodeJS.ErrnoException).code === "ENOENT") return (cache = {});
    throw err;
  }
  try {
    cache = JSON.parse(text) as FileConfig;
  } catch {
    // Corrupted (e.g. a write from an older version was cut off): fall back to the last good copy.
    try {
      cache = JSON.parse(fs.readFileSync(`${file}.bak`, "utf8")) as FileConfig;
      console.warn(`[settings] ${file} was unreadable; using ${file}.bak`);
    } catch {
      throw new Error(`${file} is not valid JSON and no usable .bak exists — fix or restore it, then restart.`);
    }
  }
  return cache;
}

function save(c: FileConfig): void {
  fs.mkdirSync(config.dataDir, { recursive: true });
  // Write a temp file and rename it over the real one: a crash or power cut mid-write can then never
  // leave a half-written config.json behind. Keep the previous version as config.json.bak.
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(c, null, 2), { mode: 0o600 });
  if (fs.existsSync(file)) {
    try { fs.copyFileSync(file, `${file}.bak`); } catch {}
  }
  fs.renameSync(tmp, file);
  cache = c;
}

function hashPassword(pw: string): string {
  const salt = randomBytes(16);
  const hash = scryptSync(pw, salt, 64);
  return `scrypt$${salt.toString("hex")}$${hash.toString("hex")}`;
}

function verifyHash(pw: string, stored: string): boolean {
  const [alg, saltHex, hashHex] = stored.split("$");
  if (alg !== "scrypt" || !saltHex || !hashHex) return false;
  const expected = Buffer.from(hashHex, "hex");
  const actual = scryptSync(pw, Buffer.from(saltHex, "hex"), expected.length);
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

function eqConst(a: string, b: string): boolean {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

/** True once setup is done — a master key / password exists (env/file) OR the user chose "no password".
 *  A configured MASTER_KEY counts as configured, so the setup wizard is skipped and the login shows. */
export function isAdminConfigured(): boolean {
  const f = load();
  return !!(config.masterKey || config.adminPassword || f.adminPasswordHash || f.authDisabled);
}

/**
 * Whether the TV credential endpoints (requireServerToken) enforce a code. An env ADMIN_PASSWORD forces
 * it on; otherwise it's on only when a file password exists and the user hasn't chosen "no password".
 * NOTE: the MASTER_KEY intentionally does NOT force this on — it only locks the settings dashboard (see
 * [isDashboardLocked]) — so adding a master key never breaks TVs that pull credentials.
 */
export function isAuthEnabled(): boolean {
  if (config.adminPassword) return true;
  const f = load();
  if (f.authDisabled) return false;
  return !!f.adminPasswordHash;
}

/** Whether the settings/admin DASHBOARD requires a login. The MASTER_KEY locks it even when the TV
 *  endpoints are open, so "not just anyone can open settings" without affecting TV access. */
export function isDashboardLocked(): boolean {
  return !!config.masterKey || isAuthEnabled();
}

/** Accepts any valid credential to unlock the dashboard: the master key, the env password, or the
 *  file password. */
export function verifyAdminPassword(pw: string): boolean {
  if (config.masterKey && eqConst(pw, config.masterKey)) return true;
  if (config.adminPassword && eqConst(pw, config.adminPassword)) return true;
  const h = load().adminPasswordHash;
  return h ? verifyHash(pw, h) : false;
}

/** Sets a password (turns auth ON). */
export function setAdminPassword(pw: string): void {
  const c = load();
  c.adminPasswordHash = hashPassword(pw);
  c.authDisabled = false;
  save(c);
}

/** Turns auth OFF — open dashboard + open TV endpoint (home-LAN convenience). */
export function disableAuth(): void {
  const c = load();
  c.authDisabled = true;
  delete c.adminPasswordHash;
  save(c);
}

/** Optional env-provided TV token (advanced/headless). "" when unset. TVs normally use access codes. */
export function envServerToken(): string {
  return config.serverToken || "";
}
