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
}

// JioTV language-id → name (well-known ids).
const JIO_LANG: Record<string, string> = {
  "1": "Hindi", "2": "Marathi", "3": "Punjabi", "4": "Urdu", "5": "Tamil", "6": "English",
  "7": "Malayalam", "8": "Telugu", "9": "Bengali", "10": "Kannada", "11": "Oriya",
  "12": "Gujarati", "13": "Assamese", "14": "Nepali", "15": "French", "16": "Bhojpuri",
  "17": "Kokborok", "18": "Odia", "19": "Rajasthani", "23": "Arabic",
};

const CHANNEL_TTL_MS = 24 * 60 * 60 * 1000;
let cache: { at: number; channels: Channel[] } | null = null;

async function fetchDictionary(): Promise<Record<string, string>> {
  try {
    const res = await fetch(
      "https://jiotvapi.cdn.jio.com/apis/v1.3/dictionary/dictionary?langId=6",
      { headers: { "User-Agent": jio.USER_AGENT } }
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
    const res = await fetch(url, { headers: { "User-Agent": jio.USER_AGENT } });
    if (!res.ok) return;
    const json = (await res.json()) as any;
    const result: any[] = json.result ?? [];
    for (const c of result) {
      const id = Number(c.channel_id);
      if (!id || out.has(id)) continue;
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
      });
    }
  } catch {
    /* ignore individual page failures */
  }
}

/** Fetches + merges the v1.4 and v3.1 channel lists (mirrors JioApiClient.getMobileChannelList). */
export async function getChannels(force = false): Promise<Channel[]> {
  if (!force && cache && Date.now() - cache.at < CHANNEL_TTL_MS) return cache.channels;

  const categoryMap = await fetchDictionary();
  const merged = new Map<number, Channel>();
  await fetchChannelPage(
    "https://jiotvapi.cdn.jio.com/apis/v1.4/getMobileChannelList/get/?langId=6&devicetype=phone&os=android&usertype=JIO&version=422",
    categoryMap, merged
  );
  await fetchChannelPage(
    "https://jiotvapi.cdn.jio.com/apis/v3.1/getMobileChannelList/get/?langId=6&os=android&devicetype=phone&usertype=JIO&version=422",
    categoryMap, merged
  );

  if (merged.size === 0) {
    if (cache) return cache.channels; // serve stale on a transient failure
    return [];
  }
  const channels = [...merged.values()].sort((a, b) => a.channelNumber - b.channelNumber);
  cache = { at: Date.now(), channels };
  return channels;
}
