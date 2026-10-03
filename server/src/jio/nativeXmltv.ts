import fs from "node:fs";
import path from "node:path";
import { createGzip } from "node:zlib";
import { once } from "node:events";
import { config } from "../config";
import { getChannels, type Channel } from "./channels";
import { fetchOffset, type EpgProgram } from "./epg";

/**
 * Full XMLTV guide (channels + programmes) built from Jio's native per-channel EPG, for IPTV players
 * (TiviMate, Kodi, OTT Navigator) that read `/epg.xml`. Before this, native mode served channels only,
 * so those players showed an empty guide.
 *
 * Building it means ~3 requests per channel (yesterday, today, tomorrow), so it runs in the background
 * with limited concurrency, and is rebuilt every 6 h — but only while players keep asking for it (it
 * starts on the first request, and stops after a day without one).
 *
 * Memory: the XML is never held in memory. Each channel's programmes are written through
 * `zlib.createGzip()` straight to `DATA_DIR/epg.xml.gz.tmp` as soon as they're fetched, and the file is
 * renamed over `epg.xml.gz` when complete (readers mid-download keep the old file's inode). The routes
 * stream that file; `/epg.xml` gunzips on the fly only for clients that don't accept gzip.
 */
const OFFSETS = [-1, 0, 1];
const CONCURRENCY = 12;
const REBUILD_EVERY_MS = 6 * 60 * 60 * 1000;
const IDLE_STOP_MS = 24 * 60 * 60 * 1000;

let builtAt = -1; // -1 = not checked on disk yet
let building: Promise<void> | null = null;
let lastRequestAt = 0;
let timer: NodeJS.Timeout | null = null;

export function nativeXmltvPath(): string {
  return path.join(config.dataDir, "epg.xml.gz");
}

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

function programmeXml(c: Channel, p: EpgProgram): string {
  return (
    `<programme start="${xmltvTime(p.startMs)}" stop="${xmltvTime(p.stopMs)}" channel="${xmlEscape(c.id)}">` +
    `<title>${xmlEscape(p.title)}</title>` +
    (p.description ? `<desc>${xmlEscape(p.description)}</desc>` : "") +
    `<category>${xmlEscape(c.group)}</category>` +
    (p.poster ? `<icon src="${xmlEscape(p.poster)}"/>` : "") +
    (p.episodeNum ? `<episode-num system="onscreen">${p.episodeNum}</episode-num>` : "") +
    (p.rating ? `<rating><value>${xmlEscape(p.rating)}</value></rating>` : "") +
    `</programme>`
  );
}

/** Fetches + de-dupes one channel's programmes over [OFFSETS]. */
async function fetchChannelProgrammes(channelId: string): Promise<EpgProgram[]> {
  const parts: EpgProgram[][] = [];
  for (const o of OFFSETS) parts.push(await fetchOffset(channelId, o));
  const seen = new Set<string>();
  return parts.flat()
    .filter((p) => { const k = `${p.startMs}|${p.title}`; if (seen.has(k)) return false; seen.add(k); return true; })
    .sort((a, b) => a.startMs - b.startMs);
}

/**
 * Writes a gzipped XMLTV document to [outPath], fetching programmes with [concurrency] workers and
 * streaming each channel's block as soon as it arrives (backpressure-aware). Returns the programme
 * count. Exported for tests.
 */
export async function writeXmltvGz(
  channels: Channel[],
  fetchProgrammes: (channelId: string) => Promise<EpgProgram[]>,
  outPath: string,
  concurrency = CONCURRENCY
): Promise<number> {
  const gzip = createGzip();
  const file = fs.createWriteStream(outPath);
  gzip.pipe(file);
  const fileDone = new Promise<void>((resolve, reject) => {
    file.on("finish", resolve);
    file.on("error", reject);
    gzip.on("error", reject);
  });

  // Every write goes through one chain so blocks never interleave, and waits for 'drain' when needed.
  let chain: Promise<void> = Promise.resolve();
  const write = (s: string) => {
    chain = chain.then(async () => {
      if (!gzip.write(s)) await once(gzip, "drain");
    });
    return chain;
  };

  await write('<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="JTV Server">\n');
  // XMLTV wants every <channel> before the first <programme>.
  for (const c of channels) {
    await write(`<channel id="${xmlEscape(c.id)}"><display-name>${xmlEscape(c.name)}</display-name><icon src="${xmlEscape(c.logoUrl)}"/></channel>\n`);
  }

  let count = 0;
  let next = 0;
  async function worker() {
    while (next < channels.length) {
      const c = channels[next++];
      const progs = await fetchProgrammes(c.id);
      if (!progs.length) continue;
      count += progs.length;
      await write(progs.map((p) => programmeXml(c, p)).join("\n") + "\n");
    }
  }
  try {
    await Promise.all(Array.from({ length: Math.max(1, concurrency) }, worker));
    await write("</tv>\n");
    gzip.end();
    await fileDone;
  } catch (e) {
    gzip.destroy();
    file.destroy();
    throw e;
  }
  return count;
}

async function build(): Promise<void> {
  const started = Date.now();
  const channels = await getChannels();
  const out = nativeXmltvPath();
  const tmp = `${out}.tmp`;
  let count: number;
  try {
    count = await writeXmltvGz(channels, fetchChannelProgrammes, tmp);
  } catch (e) {
    fs.rmSync(tmp, { force: true });
    throw e;
  }
  // Don't replace a good guide with an empty one if Jio's EPG was unreachable this round.
  if (count === 0 && fs.existsSync(out)) {
    fs.rmSync(tmp, { force: true });
    console.warn("[epg] native guide rebuild returned no programmes; keeping the previous guide");
    return;
  }
  fs.renameSync(tmp, out);
  builtAt = Date.now();
  const kb = Math.round(fs.statSync(out).size / 1024);
  console.log(`[epg] native guide built: ${channels.length} channels, ${count} programmes, ${kb} KB gz in ${Math.round((builtAt - started) / 1000)}s`);
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
  timer.unref();
}

/**
 * Path of the built guide (`epg.xml.gz`) if there is one, else null. Either way, a (re)build is started
 * in the background when the guide is missing or stale; callers serve a channel-only guide meanwhile.
 * A guide left on disk by a previous run is reused (its age comes from the file's mtime).
 */
export function getNativeXmltvFile(): string | null {
  lastRequestAt = Date.now();
  ensureSchedule();
  const file = nativeXmltvPath();
  if (builtAt < 0) {
    try { builtAt = fs.statSync(file).mtimeMs; } catch { builtAt = 0; }
  }
  const exists = builtAt > 0 && fs.existsSync(file);
  if (!exists || Date.now() - builtAt > REBUILD_EVERY_MS * 2) void rebuild();
  return exists ? file : null;
}
