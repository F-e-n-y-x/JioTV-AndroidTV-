import { jio } from "../config";

export interface EpgProgram {
  title: string;
  description: string;
  startMs: number;
  stopMs: number;
  // Catch-up identifiers (from the native Jio EPG) — needed to resolve a past show's VOD stream.
  srno?: string;
  showId?: string;
  showtime?: string;
  catchup?: boolean;
}

// Offsets: offset 0 is today, negative offsets (-1..-7) are past days (catch-up), positive (1..2) are forward.
const DEFAULT_OFFSETS = [-2, -1, 0, 1];
const FULL_CATCHUP_OFFSETS = [-7, -6, -5, -4, -3, -2, -1, 0, 1];
const TTL_MS = 30 * 60 * 1000;
const cache = new Map<string, { at: number; programs: EpgProgram[] }>();

async function fetchOffset(channelId: string, offset: number): Promise<EpgProgram[]> {
  try {
    const url = `https://jiotvapi.cdn.jio.com/apis/v1.3/getepg/get?offset=${offset}&channel_id=${encodeURIComponent(channelId)}&langId=6`;
    const res = await fetch(url, {
      headers: {
        "User-Agent": jio.USER_AGENT,
        appname: jio.APP_NAME,
        os: jio.OS,
        devicetype: jio.DEVICE_TYPE,
      },
    });
    if (!res.ok) return [];
    const json = (await res.json()) as any;
    return (json.epg ?? []).map((o: any) => ({
      title: o.showname ?? "",
      description: o.description ?? "",
      startMs: Number(o.startEpoch ?? 0),
      stopMs: Number(o.endEpoch ?? 0),
      srno: o.srno != null ? String(o.srno) : undefined,
      showId: o.showId ?? undefined,
      showtime: o.showtime ?? undefined,
      catchup: !!o.isCatchupAvailable,
    })).filter((p: EpgProgram) => p.title && p.startMs > 0 && p.stopMs > 0);
  } catch {
    return [];
  }
}

/** Native Jio EPG for one channel — merges day-offsets (yesterday…+1d or full 7d catch-up), de-duped, cached 30 min. */
export async function getNativeEpg(channelId: string, opts: { fullCatchup?: boolean } = {}): Promise<EpgProgram[]> {
  const cacheKey = `${channelId}:${opts.fullCatchup ? "full" : "def"}`;
  const c = cache.get(cacheKey);
  if (c && Date.now() - c.at < TTL_MS) return c.programs;

  const offsets = opts.fullCatchup ? FULL_CATCHUP_OFFSETS : DEFAULT_OFFSETS;
  const parts = await Promise.all(offsets.map((o) => fetchOffset(channelId, o)));
  const now = Date.now();
  const past = opts.fullCatchup ? now - 8 * 24 * 3600_000 : now - 52 * 3600_000;
  const future = now + 36 * 3600_000;
  const seen = new Set<string>();
  const programs = parts
    .flat()
    .filter((p) => p.stopMs > past && p.startMs < future)
    .filter((p) => { const k = `${p.startMs}|${p.title}`; if (seen.has(k)) return false; seen.add(k); return true; })
    .sort((a, b) => a.startMs - b.startMs);

  cache.set(cacheKey, { at: Date.now(), programs });
  return programs;
}
