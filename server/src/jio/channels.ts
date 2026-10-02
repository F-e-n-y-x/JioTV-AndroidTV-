import { jio } from "../config";

export interface Channel {
  id: string;
  name: string;
  logoUrl: string;
  group: string;
  language: string;
  isDrm: boolean;
  isCatchup?: boolean;
  channelNumber: number;
  /** Jio's set-top-box channel number (`stbChannelNumber`) when the list carries one → M3U tvg-chno. */
  stbNumber?: number;
  /** v3.1 `is_premium`. */
  isPremium?: boolean;
  /** v3.1 `plan_type` ("free", "premium", "guest"...). */
  planType?: string;
}

/**
 * Jio's current channel list is **v3.1** (~1200 ids, with stbChannelNumber / is_premium / plan_type).
 * The older **v1.4** list has ~100 extra ids, and almost all are dead: Zee GEC/movie channels Jio
 * dropped (403/404), STB duplicates, test feeds (PlusTest2, RedBull Cam01-03), the old Sony YAY 3507.
 *
 * v3.1 wins on shared ids; a v1.4-only id is kept ONLY if it is listed here. These are the Zee regional
 * channels that v1.5.5 made play via the HLS fallback (README changelog v1.5.5, commit f5e8a53).
 * Keep in sync with the app's `android/.../data/ChannelSources.kt`.
 */
export const V14_ONLY_ALLOWLIST: ReadonlyMap<number, string> = new Map([
  [413, "Zee Cinemalu"],
  [414, "Zee Yuva"],
  [625, "Zee Bangla"],
  [628, "Zee Tamil"],
  [722, "Zee Sarthak"],
  [1691, "Zee Classic"],
  [3476, "Zee Bangla Sonar"],
]);

const V31_URL =
  "https://jiotvapi.cdn.jio.com/apis/v3.1/getMobileChannelList/get/?langId=6&os=android&devicetype=phone&usertype=JIO&version=422";
const V14_URL =
  "https://jiotvapi.cdn.jio.com/apis/v1.4/getMobileChannelList/get/?langId=6&devicetype=phone&os=android&usertype=JIO&version=422";

/** v3.1 first (wins on shared ids), then only the allow-listed v1.4-only ids. */
export function mergeChannelLists<T>(v31: Map<number, T>, v14: Map<number, T>): Map<number, T> {
  const out = new Map(v31);
  for (const [id, c] of v14) {
    if (!out.has(id) && V14_ONLY_ALLOWLIST.has(id)) out.set(id, c);
  }
  return out;
}

// JioTV language-id → name (well-known ids).
const JIO_LANG: Record<string, string> = {
  // Jio's languageIdMapping (apis/v1.3/dictionary/dictionary); the old table had 10 ids wrong.
  "1": "Hindi", "2": "Marathi", "3": "Punjabi", "4": "Urdu", "5": "Bengali", "6": "English",
  "7": "Malayalam", "8": "Tamil", "9": "Gujarati", "10": "Odia", "11": "Telugu",
  "12": "Bhojpuri", "13": "Kannada", "14": "Assamese", "15": "Nepali", "16": "French",
};

const CHANNEL_TTL_MS = 24 * 60 * 60 * 1000;
let cache: { at: number; channels: Channel[] } | null = null;

async function fetchDictionary(): Promise<Record<string, string>> {
  try {
    const res = await fetch(
      "https://jiotvapi.cdn.jio.com/apis/v1.3/dictionary/dictionary?langId=6",
      { headers: { "User-Agent": jio.USER_AGENT }, signal: AbortSignal.timeout(15_000) }
    );
    if (!res.ok) return {};
    const json = (await res.json()) as any;
    return json.channelCategoryMapping ?? {};
  } catch {
    return {};
  }
}

async function fetchChannelPage(url: string, categoryMap: Record<string, string>, out: Map<number, Channel>) {
  try {
    const res = await fetch(url, { headers: { "User-Agent": jio.USER_AGENT }, signal: AbortSignal.timeout(15_000) });
    if (!res.ok) return;
    const json = (await res.json()) as any;
    const result: any[] = json.result ?? [];
    for (const c of result) {
      const id = Number(c.channel_id);
      if (!id) continue;
      const stb = Number(c.stbChannelNumber);
      if (out.has(id)) continue;
      const isDrm = c.isDrm === true || String(c.isDrm) === "true" || c.streamType === "mpd";
      const isCatchup = c.isCatchupAvailable === true || String(c.isCatchupAvailable) === "true";
      out.set(id, {
        id: String(id),
        name: c.channel_name || "Unknown",
        logoUrl: `https://jiotvimages.cdn.jio.com/dare_images/images/${c.logoUrl ?? ""}`,
        group: categoryMap[String(c.channelCategoryId)] ?? "Other",
        language: JIO_LANG[String(c.channelLanguageId)] ?? "Other",
        isDrm,
        isCatchup,
        channelNumber: id,
        ...(Number.isInteger(stb) && stb > 0 ? { stbNumber: stb } : {}),
        ...(c.is_premium === undefined ? {} : { isPremium: c.is_premium === true || String(c.is_premium) === "true" }),
        ...(typeof c.plan_type === "string" && c.plan_type ? { planType: c.plan_type } : {}),
      });
    }
  } catch {
    /* ignore individual page failures */
  }
}

/** Fetches v3.1 + v1.4 in parallel and merges them (mirrors the app's ChannelSources.merge). */
export async function getChannels(force = false): Promise<Channel[]> {
  if (!force && cache && Date.now() - cache.at < CHANNEL_TTL_MS) return cache.channels;

  const categoryMap = await fetchDictionary();
  const v31 = new Map<number, Channel>();
  const v14 = new Map<number, Channel>();
  await Promise.all([
    fetchChannelPage(V31_URL, categoryMap, v31),
    fetchChannelPage(V14_URL, categoryMap, v14),
  ]);
  const merged = mergeChannelLists(v31, v14);

  if (merged.size === 0) {
    if (cache) return cache.channels; // serve stale on a transient failure
    return [];
  }
  const channels = [...merged.values()].sort((a, b) => a.channelNumber - b.channelNumber);
  cache = { at: Date.now(), channels };
  return channels;
}
