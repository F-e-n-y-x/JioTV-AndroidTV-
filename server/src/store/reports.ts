import fs from "node:fs";
import path from "node:path";
import { randomBytes } from "node:crypto";
import { config } from "../config";
import { redactSecrets } from "../util/redact";

/**
 * Crash / ANR reports sent by the TV app (`POST /api/reports`). Each report is one JSON file in
 * `DATA_DIR/reports/`; only the newest [MAX_REPORTS] are kept. Text is redacted again on receipt.
 */
export const MAX_REPORTS = 50;
const MAX_TEXT = 96 * 1024;

export interface ProblemReport {
  id: string;
  /** "crash" | "anr" | "native" | "exit" — what the app recorded. */
  kind: string;
  /** When it happened on the device (epoch ms). */
  at: number;
  receivedAt: number;
  appVersion: string;
  device: string;
  android: string;
  text: string;
}

function dir(): string {
  const d = path.join(config.dataDir, "reports");
  fs.mkdirSync(d, { recursive: true });
  return d;
}

const str = (v: unknown, max: number) => redactSecrets(String(v ?? "")).slice(0, max);

/** Validates + stores one report; returns it, or null when the body isn't a report. */
export function saveReport(body: unknown, nowMs: number): ProblemReport | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  const text = str(b.text, MAX_TEXT);
  if (!text.trim()) return null;
  const at = Number(b.at);
  const report: ProblemReport = {
    id: `${nowMs}-${randomBytes(4).toString("hex")}`,
    kind: str(b.kind, 16).replace(/[^a-z]/gi, "") || "crash",
    at: Number.isFinite(at) && at > 0 ? at : nowMs,
    receivedAt: nowMs,
    appVersion: str(b.appVersion, 40),
    device: str(b.device, 80),
    android: str(b.android, 40),
    text,
  };
  const d = dir();
  fs.writeFileSync(path.join(d, `${report.id}.json`), JSON.stringify(report));
  prune(d);
  return report;
}

function files(d: string): string[] {
  // Ids start with the receive time, so a name sort is a time sort.
  return fs.readdirSync(d).filter((f) => /^\d+-[0-9a-f]+\.json$/.test(f)).sort();
}

function prune(d: string): void {
  const all = files(d);
  for (const f of all.slice(0, Math.max(0, all.length - MAX_REPORTS))) fs.rmSync(path.join(d, f), { force: true });
}

/** Newest first. */
export function listReports(): ProblemReport[] {
  const d = dir();
  const out: ProblemReport[] = [];
  for (const f of files(d).reverse()) {
    try { out.push(JSON.parse(fs.readFileSync(path.join(d, f), "utf8"))); } catch { /* skip a broken file */ }
  }
  return out;
}

export function deleteReport(id: string): void {
  if (!/^\d+-[0-9a-f]+$/.test(id)) return;
  fs.rmSync(path.join(dir(), `${id}.json`), { force: true });
}
