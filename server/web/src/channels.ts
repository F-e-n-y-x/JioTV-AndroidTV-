// Client-side channel helpers: one cached channel list per page load, language families (a port of
// the app's ChannelLanguage.collapse), recent channels and the preferred feed language.
import { api, type Channel } from "./api";

/** One language feed of a family ("History TV18 HD Hindi"). `lang` is a display name. */
export interface Variant { lang: string; channel: Channel }
/** A channel card: the representative feed plus, for a language family, all its feeds. */
export type Card = Channel & { variants?: Variant[] };

const LANG: Record<string, string> = {
  hindi: "Hindi", english: "English", eng: "English", tamil: "Tamil", telugu: "Telugu", kannada: "Kannada",
  malayalam: "Malayalam", bengali: "Bengali", bangla: "Bengali", marathi: "Marathi", gujarati: "Gujarati",
  punjabi: "Punjabi", odia: "Odia", oriya: "Odia", assamese: "Assamese", bhojpuri: "Bhojpuri", urdu: "Urdu",
  nepali: "Nepali", konkani: "Konkani", sindhi: "Sindhi",
};
const QUALITY = new Set(["HD", "SD", "FHD", "UHD", "4K"]);
// Only these categories hold dubs of one feed; regional channels elsewhere (Zee Tamil, TV9 Telugu) never merge.
const DUB_CATEGORIES = new Set(["sports", "infotainment", "kids", "lifestyle"]);

/** "Star Sports 1 Hindi HD" -> base "Star Sports 1 HD", lang "Hindi"; no language token -> lang null. */
export function detect(name: string): { base: string; lang: string | null } {
  const parts = name.trim().split(/\s+/);
  const quality = parts.length > 1 && QUALITY.has(parts[parts.length - 1].toUpperCase()) ? parts.pop()! : null;
  const lang = parts.length > 1 ? LANG[parts[parts.length - 1].toLowerCase()] : undefined;
  if (!lang) return { base: name.trim(), lang: null };
  parts.pop();
  return { base: [...parts, ...(quality ? [quality] : [])].join(" "), lang };
}

/** Merges dub families into one card, sorted by channel number like the app. */
export function collapse(channels: Channel[]): Card[] {
  const buckets = new Map<string, { ch: Channel; lang: string | null }[]>();
  for (const ch of channels) {
    const d = detect(ch.name);
    const key = `${d.base.toLowerCase()}|${ch.group}`;
    (buckets.get(key) ?? buckets.set(key, []).get(key)!).push({ ch, lang: d.lang });
  }
  const cards: Card[] = [];
  for (const members of buckets.values()) {
    const tagged = members.filter((m) => m.lang).length;
    if (members.length < 2 || tagged < 2 || !DUB_CATEGORIES.has(members[0].ch.group.toLowerCase())) {
      cards.push(...members.map((m) => m.ch));
      continue;
    }
    // The plain feed is named by Jio's language field, so it isn't listed as a second "English".
    const variants = members
      .map((m) => ({ lang: m.lang ?? m.ch.language ?? "Default", channel: m.ch, plain: !m.lang }))
      .sort((a, b) => Number(b.plain) - Number(a.plain) || a.lang.localeCompare(b.lang))
      .map(({ lang, channel }) => ({ lang, channel }));
    const rep = members.find((m) => !m.lang)?.ch ?? members.reduce((a, b) => (b.ch.channelNumber < a.ch.channelNumber ? b : a)).ch;
    cards.push({ ...rep, name: detect(rep.name).base, variants });
  }
  return cards.sort((a, b) => (a.channelNumber || 1e9) - (b.channelNumber || 1e9) || a.name.localeCompare(b.name));
}

let cache: Promise<{ all: Channel[]; cards: Card[] }> | null = null;
/** Channels for this page load (one /api/channels call shared by every page). */
export function loadChannels(): Promise<{ all: Channel[]; cards: Card[] }> {
  cache ??= api.channels().then((r) => ({ all: r.channels, cards: collapse(r.channels) }));
  cache.catch(() => { cache = null; }); // a failed load is retried next time
  return cache;
}

/** The card a channel id belongs to (the id may be any feed of a family). */
export function cardFor(cards: Card[], id: string): Card | undefined {
  return cards.find((c) => c.id === id || c.variants?.some((v) => v.channel.id === id));
}

/** Languages a card can play in, for the language filter. */
export const cardLanguages = (c: Card): string[] => (c.variants ? c.variants.map((v) => v.lang) : [c.language]);

function read<T>(key: string, fallback: T): T { try { return JSON.parse(localStorage.getItem(key) ?? "") as T; } catch { return fallback; } }
function write(key: string, v: unknown) { try { localStorage.setItem(key, JSON.stringify(v)); } catch {} }

/** Feed to play for a card: the last language the viewer picked, when the family has it. */
export function feedFor(c: Card): string {
  const pref = read<string>("prefFeedLang", "");
  return c.variants?.find((v) => v.lang === pref)?.channel.id ?? c.id;
}
export const setPrefFeedLang = (lang: string) => write("prefFeedLang", lang);

const RECENT_MAX = 12;
export const readRecent = (): string[] => read<string[]>("recent", []);
export function pushRecent(cardId: string) { write("recent", [cardId, ...readRecent().filter((x) => x !== cardId)].slice(0, RECENT_MAX)); }
