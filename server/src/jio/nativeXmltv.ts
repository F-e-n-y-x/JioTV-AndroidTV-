import { gzipSync } from "node:zlib";
import { getChannels } from "./channels";
import { fetchOffset, type EpgProgram } from "./epg";

/**
 * Full XMLTV guide (channels + programmes) built from Jio's native per-channel EPG, for IPTV players
 * (TiviMate, Kodi, OTT Navigator) that read `/epg.xml`. Before this, native mode served channels only,
 * so those players showed an empty guide.
 *
 * Building it means ~3 requests per channel (yesterday, today, tomorrow), so it runs in the background
 * with limited concurrency, is cached, and is rebuilt every 6 h — but only while players keep asking
 * for it (it starts on the first request, and stops after a day without one).
 */
const OFFSETS = [-1, 0, 1];
const CONCURRENCY = 6;
const REBUILD_EVERY_MS = 6 * 60 * 60 * 1000;
const IDLE_STOP_MS = 24 * 60 * 60 * 1000;

let xml: string | null = null;
let gz: Buffer | null = null;
let builtAt = 0;
let building: Promise<void> | null = null;
let lastRequestAt = 0;
let timer: NodeJS.Timeout | null = null;

export function xmlEscape(s: string): string {
  return String(s ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&apos;")
    // XML 1.0 forbids most control characters; Jio descriptions occasionally contain them.
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "");
}

/** XMLTV timestamp: "YYYYMMDDHHmmss +0000". */
function xmltvTime(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}${p(d.getUTCSeconds())} +0000`;
}

async function build(): Promise<void> {
  const started = Date.now();
  const channels = await getChannels();
  const programmes = new Map<string, EpgProgram[]>();
  let next = 0;
  async function worker() {
    while (next < channels.length) {
      const c = channels[next++];
      const parts: EpgProgram[][] = [];
      for (const o of OFFSETS) parts.push(await fetchOffset(c.id, o));
      const seen = new Set<string>();
      programmes.set(
        c.id,
        parts.flat()
          .filter((p) => { const k = `${p.startMs}|${p.title}`; if (seen.has(k)) return false; seen.add(k); return true; })
          .sort((a, b) => a.startMs - b.startMs)
      );
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));

  const out: string[] = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="JTV Server">'];
  for (const c of channels) {
    out.push(`<channel id="${xmlEscape(c.id)}"><display-name>${xmlEscape(c.name)}</display-name><icon src="${xmlEscape(c.logoUrl)}"/></channel>`);
  }
  let count = 0;
  for (const c of channels) {
    for (const p of programmes.get(c.id) ?? []) {
      count++;
      out.push(
        `<programme start="${xmltvTime(p.startMs)}" stop="${xmltvTime(p.stopMs)}" channel="${xmlEscape(c.id)}">` +
          `<title>${xmlEscape(p.title)}</title>` +
          (p.description ? `<desc>${xmlEscape(p.description)}</desc>` : "") +
          `<category>${xmlEscape(c.group)}</category></programme>`
      );
    }
  }
  out.push("</tv>");
  // Don't replace a good guide with an empty one if Jio's EPG was unreachable this round.
  if (count === 0 && xml) {
    console.warn("[epg] native guide rebuild returned no programmes; keeping the previous guide");
    return;
  }
  xml = out.join("\n");
  gz = gzipSync(xml);
  builtAt = Date.now();
  console.log(`[epg] native guide built: ${channels.length} channels, ${count} programmes in ${Math.round((builtAt - started) / 1000)}s`);
}

function rebuild(): Promise<void> {
  if (!building) {
    building = build()
      .catch((e) => console.warn("[epg] native guide build failed:", (e as Error).message))
      .finally(() => { building = null; });
  }
  return building;
}

function ensureSchedule(): void {
  if (timer) return;
  timer = setInterval(() => {
    if (Date.now() - lastRequestAt > IDLE_STOP_MS) {
      if (timer) clearInterval(timer);
      timer = null;
      return;
    }
    void rebuild();
  }, REBUILD_EVERY_MS);
}

/**
 * The native guide if it's built, else null (a build is started in the background; callers serve a
 * channel-only guide meanwhile).
 */
export function getNativeXmltv(format: "xml" | "gz"): string | Buffer | null {
  lastRequestAt = Date.now();
  ensureSchedule();
  if (!xml || Date.now() - builtAt > REBUILD_EVERY_MS * 2) void rebuild();
  return format === "gz" ? gz : xml;
}
