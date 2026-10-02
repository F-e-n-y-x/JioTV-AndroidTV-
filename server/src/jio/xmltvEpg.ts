import zlib from "node:zlib";
import type { EpgProgram } from "./epg";

/**
 * Optional XMLTV EPG source. Downloads an .xml or .xml.gz guide, parses <programme> elements within a
 * time window, and maps them by the `channel` attribute (which for JioTV XMLTV = the Jio channel_id).
 * Used when the EPG mode is "xmltv"; otherwise the native per-channel Jio EPG is used.
 */

let cache = new Map<string, EpgProgram[]>();
let lastSync = 0;
let status: "idle" | "downloading" | "parsing" | "ok" | string = "idle";

function parseXmltvDate(s: string): number {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:\s*([+-]\d{4}))?/.exec(s.trim());
  if (!m) return 0;
  let ms = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
  if (m[7]) {
    const sign = m[7][0] === "-" ? -1 : 1;
    ms -= sign * (+m[7].slice(1, 3) * 60 + +m[7].slice(3, 5)) * 60000;
  }
  return ms;
}

function decodeXml(s: string): string {
  return s
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'").replace(/&amp;/g, "&")
    .trim();
}

export async function refreshXmltv(url: string): Promise<void> {
  status = "downloading";
  try {
    const res = await fetch(url, { headers: { "User-Agent": "Mozilla/5.0" }, signal: AbortSignal.timeout(120_000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    let buf = Buffer.from(await res.arrayBuffer());
    if (buf[0] === 0x1f && buf[1] === 0x8b) buf = zlib.gunzipSync(buf); // gzip magic bytes
    const text = buf.toString("utf8");

    status = "parsing";
    const map = new Map<string, EpgProgram[]>();
    const now = Date.now();
    const past = now - 3 * 3600_000;
    const future = now + 24 * 3600_000;
    const re = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g;
    let mm: RegExpExecArray | null;
    while ((mm = re.exec(text))) {
      const attrs = mm[1], body = mm[2];
      const ch = /channel="([^"]+)"/.exec(attrs)?.[1];
      if (!ch) continue;
      const start = parseXmltvDate(/start="([^"]+)"/.exec(attrs)?.[1] ?? "");
      const stop = parseXmltvDate(/stop="([^"]+)"/.exec(attrs)?.[1] ?? "");
      if (!start || !stop || stop < past || start > future) continue;
      const title = decodeXml(/<title[^>]*>([\s\S]*?)<\/title>/.exec(body)?.[1] ?? "");
      if (!title) continue;
      const description = decodeXml(/<desc[^>]*>([\s\S]*?)<\/desc>/.exec(body)?.[1] ?? "");
      const arr = map.get(ch) ?? [];
      arr.push({ title, description, startMs: start, stopMs: stop });
      map.set(ch, arr);
    }
    for (const arr of map.values()) arr.sort((a, b) => a.startMs - b.startMs);
    cache = map;
    lastSync = Date.now();
    status = "ok";
  } catch (e) {
    status = `error: ${(e as Error).message}`;
    throw e;
  }
}

export function getXmltvEpg(channelId: string): EpgProgram[] {
  return cache.get(channelId) ?? [];
}

export function xmltvStatus() {
  return { status, lastSync, channels: cache.size };
}
