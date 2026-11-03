import path from "node:path";
import Database from "better-sqlite3";
import { config } from "../config";
import type { AuthData } from "../jio/types";
import { ensureDataDirWritable } from "./dataDir";

/**
 * Tiny SQLite-backed store. v1 holds a single shared Jio account (id = 1) plus its mobile number and
 * the last-refresh timestamp. Multi-account is a Phase 3 extension (add a profile id).
 */

ensureDataDirWritable();
const db = new Database(path.join(config.dataDir, "jtv.sqlite"));
db.pragma("journal_mode = WAL");

db.exec(`
  CREATE TABLE IF NOT EXISTS credentials (
    id           INTEGER PRIMARY KEY CHECK (id = 1),
    mobile       TEXT,
    ssoToken     TEXT,
    authToken    TEXT,
    crmid        TEXT,
    uniqueId     TEXT,
    deviceId     TEXT,
    userId       TEXT,
    updated_at   INTEGER
  );
  CREATE TABLE IF NOT EXISTS favorites (
    channel_id TEXT PRIMARY KEY
  );
  CREATE TABLE IF NOT EXISTS access_codes (
    code       TEXT PRIMARY KEY,
    name       TEXT,
    created_at INTEGER
  );
`);

// Migration: add refreshToken to existing credential stores (required by the refresh endpoint).
try { db.exec("ALTER TABLE credentials ADD COLUMN refreshToken TEXT"); } catch { /* already exists */ }

export interface StoredCredentials extends AuthData {
  mobile: string;
  updatedAt: number;
}

const selectStmt = db.prepare("SELECT * FROM credentials WHERE id = 1");
const upsertStmt = db.prepare(`
  INSERT INTO credentials (id, mobile, ssoToken, authToken, refreshToken, crmid, uniqueId, deviceId, userId, updated_at)
  VALUES (1, @mobile, @ssoToken, @authToken, @refreshToken, @crmid, @uniqueId, @deviceId, @userId, @updatedAt)
  ON CONFLICT(id) DO UPDATE SET
    mobile=excluded.mobile, ssoToken=excluded.ssoToken, authToken=excluded.authToken,
    refreshToken=excluded.refreshToken, crmid=excluded.crmid, uniqueId=excluded.uniqueId,
    deviceId=excluded.deviceId, userId=excluded.userId, updated_at=excluded.updated_at
`);
const clearStmt = db.prepare("DELETE FROM credentials WHERE id = 1");

export function getStoredCredentials(): StoredCredentials | null {
  const row = selectStmt.get() as any;
  if (!row || !row.ssoToken) return null;
  return {
    mobile: row.mobile ?? "",
    ssoToken: row.ssoToken ?? "",
    authToken: row.authToken ?? "",
    refreshToken: row.refreshToken ?? "",
    crmid: row.crmid ?? "",
    uniqueId: row.uniqueId ?? "",
    deviceId: row.deviceId ?? "",
    userId: row.userId ?? "",
    updatedAt: row.updated_at ?? 0,
  };
}

/** Persists credentials, stamping updatedAt with the caller-provided epoch millis. */
export function saveCredentials(auth: AuthData, mobile: string, nowMs: number): void {
  upsertStmt.run({ ...auth, mobile, updatedAt: nowMs });
}

/** Updates only the tokens (keeps mobile), stamping updatedAt. Used by the refresh scheduler. */
export function updateTokens(auth: AuthData, nowMs: number): void {
  const existing = getStoredCredentials();
  saveCredentials(auth, existing?.mobile ?? "", nowMs);
}

export function clearCredentials(): void {
  clearStmt.run();
}

// ── Favorites (shared across all TVs + the web player) ──
const favAll = db.prepare("SELECT channel_id FROM favorites");
const favHas = db.prepare("SELECT 1 FROM favorites WHERE channel_id = ?");
const favAdd = db.prepare("INSERT OR IGNORE INTO favorites (channel_id) VALUES (?)");
const favDel = db.prepare("DELETE FROM favorites WHERE channel_id = ?");

export function getFavorites(): string[] {
  return (favAll.all() as Array<{ channel_id: string }>).map((r) => r.channel_id);
}

/** Toggles a favorite and returns the new state (true = now favorited). */
export function toggleFavorite(channelId: string): boolean {
  if (favHas.get(channelId)) {
    favDel.run(channelId);
    return false;
  }
  favAdd.run(channelId);
  return true;
}

/** Replaces the whole favorites set (used by a TV pushing its local set up). */
export function setFavorites(ids: string[]): void {
  const tx = db.transaction((list: string[]) => {
    db.prepare("DELETE FROM favorites").run();
    for (const id of list) favAdd.run(id);
  });
  tx(ids);
}

// ── TV access codes (named, short; any valid code lets a TV pull credentials) ──
export interface AccessCode {
  code: string;
  name: string;
  createdAt: number;
}

const codeAll = db.prepare("SELECT code, name, created_at FROM access_codes ORDER BY created_at");
const codeHasStmt = db.prepare("SELECT 1 FROM access_codes WHERE code = ?");
const codeAdd = db.prepare("INSERT INTO access_codes (code, name, created_at) VALUES (?, ?, ?)");
const codeDel = db.prepare("DELETE FROM access_codes WHERE code = ?");

export function listCodes(): AccessCode[] {
  return (codeAll.all() as Array<{ code: string; name: string; created_at: number }>).map((r) => ({
    code: r.code,
    name: r.name,
    createdAt: r.created_at,
  }));
}

export function hasCode(code: string): boolean {
  return !!code && !!codeHasStmt.get(code);
}

export function addCode(name: string, code: string, nowMs: number): void {
  codeAdd.run(code, name, nowMs);
}

export function deleteCode(code: string): void {
  codeDel.run(code);
}

// ── Learned stream modes (what the proxy found on a channel's first live play) ──
// "hls" = Jio's non-DRM Fallback HLS works; "drm" = only the Widevine DASH works. Persisted so
// `/playlist.m3u?drm=hide` can leave out Widevine-only channels across restarts.
db.exec(`
  CREATE TABLE IF NOT EXISTS stream_modes (
    channel_id TEXT PRIMARY KEY,
    mode       TEXT NOT NULL CHECK (mode IN ('hls', 'drm')),
    updated_at INTEGER
  );
`);

export type StreamMode = "hls" | "drm";
const modeUpsert = db.prepare(`
  INSERT INTO stream_modes (channel_id, mode, updated_at) VALUES (?, ?, ?)
  ON CONFLICT(channel_id) DO UPDATE SET mode = excluded.mode, updated_at = excluded.updated_at
`);
const modeDrmIds = db.prepare("SELECT channel_id FROM stream_modes WHERE mode = 'drm'");
const modeGet = db.prepare("SELECT mode FROM stream_modes WHERE channel_id = ?");

/** Records the stream mode learned for a live channel (no write when it's unchanged). */
export function saveStreamMode(channelId: string, mode: StreamMode, nowMs: number): void {
  const row = modeGet.get(channelId) as { mode: string } | undefined;
  if (row?.mode === mode) return;
  modeUpsert.run(channelId, mode, nowMs);
}

export function getStreamMode(channelId: string): StreamMode | null {
  const row = modeGet.get(channelId) as { mode: StreamMode } | undefined;
  return row?.mode ?? null;
}

/** Channel ids known to need Widevine (their non-DRM HLS was dead or missing when last played). */
export function getDrmOnlyChannelIds(): Set<string> {
  return new Set((modeDrmIds.all() as Array<{ channel_id: string }>).map((r) => r.channel_id));
}

/** Checkpoints the WAL and closes the database (graceful shutdown). */
export function closeDb(): void {
  try { db.close(); } catch { /* already closed */ }
}
