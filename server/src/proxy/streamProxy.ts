import { getStreamData, GeturlAuthError, type StreamData, type CatchupParams } from "../jio/stream";
import type { AuthData } from "../jio/types";
import { extractHdneaToken, extractTokenExpiryEpochSec } from "../jio/hdnea";
import { refreshTokens } from "../jio/tokens";
import { getStoredCredentials, updateTokens } from "../store/db";

interface CachedStream {
  key: string;
  data: StreamData;
  hdnea: string;
  expiresAtMs: number;
}

interface DeadCacheEntry {
  error: Error;
  expiresAtMs: number;
}

const HDNEA_MARKER = "__hdnea__=";
const cache = new Map<string, CachedStream>();
const deadCache = new Map<string, DeadCacheEntry>();
const DEAD_CACHE_TTL_MS = 60_000; // 60 seconds cooldown for 404/403/down channels

/**
 * A playback key is either "channelId" (live) or "cu.<base64url-json>" (catch-up), where the JSON is
 * `{c:channelId, s:srno, p:programId, b:beginMs, e:endMs, t:showtime}` — everything getStreamData needs
 * to re-resolve a past show's VOD stream on demand (e.g. after a token refresh).
 */
function parseKey(key: string): { channelId: string; catchup?: CatchupParams } {
  if (key.startsWith("cu.")) {
    try {
      const b64 = key.slice(3).replace(/-/g, "+").replace(/_/g, "/");
      const j = JSON.parse(Buffer.from(b64, "base64").toString("utf8"));
      return {
        channelId: String(j.c),
        catchup: { srno: String(j.s), programId: String(j.p ?? ""), beginMs: Number(j.b), endMs: Number(j.e), showtime: String(j.t ?? "") },
      };
    } catch { /* malformed — treat as a live channelId */ }
  }
  return { channelId: key };
}

// Per-channel decision (this process): "hls" = the non-DRM Fallback is alive; "drm" = it's dead (404),
// use the Widevine DASH instead. Decided once (first play) then reused so we skip the extra check.
const streamMode = new Map<string, "hls" | "drm">();

/** A manifest URL is "alive" if it returns 2xx and looks like a real HLS/DASH manifest (not a 404 page).
 *  Some channels (e.g. Zee TV HD) hand back a token-signed URL whose CDN path doesn't actually exist. */
async function masterAlive(url: string, headers: Record<string, string>): Promise<boolean> {
  try {
    const res = await fetch(url, { headers });
    if (res.status < 200 || res.status >= 300) return false;
    const t = (await res.text()).trimStart();
    return t.startsWith("#EXTM3U") || t.startsWith("<?xml") || t.startsWith("<MPD");
  } catch {
    return false;
  }
}

async function resolve(key: string): Promise<CachedStream> {
  const dead = deadCache.get(key);
  if (dead && Date.now() < dead.expiresAtMs) {
    throw dead.error;
  }

  const { channelId, catchup } = parseKey(key);
  const creds = getStoredCredentials();
  if (!creds) throw new Error("No active login on the server — sign in on the Account page first.");
  const cachedMode = catchup ? undefined : streamMode.get(channelId);
  const opts = catchup ? { catchup } : { preferDrm: cachedMode === "drm" };

  const markDead = (err: Error) => {
    deadCache.set(key, { error: err, expiresAtMs: Date.now() + DEAD_CACHE_TTL_MS });
    return err;
  };

  // A 403 from geturl is per-channel: Jio blocks it for this account (delisted / licensing) even though
  // it's still in the channel list. It's NOT a token or plan issue and no refresh fixes it.
  const blocked = () =>
    markDead(new Error("Jio blocked this channel (HTTP 403) — it isn't available on your account right now even though it's listed. A few channels are like this regardless of plan; if there's an HD version of the same channel, try that."));
  let activeCreds: AuthData = creds;
  let data: StreamData;
  try {
    data = await getStreamData(channelId, creds, opts);
  } catch (e) {
    if (e instanceof GeturlAuthError) {
      if (e.status === 403) throw blocked();
      // 401/419 usually means the SSO token went stale — refresh once and retry (like the app).
      const refreshed = await refreshTokens(creds);
      updateTokens(refreshed, Date.now());
      activeCreds = refreshed;
      try {
        data = await getStreamData(channelId, refreshed, opts);
      } catch (e2) {
        if (e2 instanceof GeturlAuthError && e2.status === 403) throw blocked();
        throw e2;
      }
    } else {
      throw e;
    }
  }

  // First live play of a channel: verify the URL Jio gave us actually resolves. Some channels hand back
  // a token-signed URL whose CDN path 404s. If the non-DRM HLS is dead, try the DRM DASH; if BOTH are
  // dead the channel is simply down on Jio's side, so say so plainly instead of failing to parse a 404.
  if (!catchup && cachedMode === undefined && data.streamUrl) {
    const down = () =>
      markDead(new Error("This channel is temporarily unavailable on Jio's servers (its stream URL returns 404). It's a Jio-side outage for this channel, not a login/plan issue — try again later or pick another channel."));
    if (!data.isMpd) {
      if (!(await masterAlive(data.streamUrl, data.streamHeaders))) {
        // HLS Fallback is dead — try the Widevine DASH.
        const drm = await getStreamData(channelId, activeCreds, { preferDrm: true });
        if (drm.isMpd && drm.streamUrl && (await masterAlive(drm.streamUrl, drm.streamHeaders))) {
          data = drm; streamMode.set(channelId, "drm");
        } else {
          throw down();
        }
      } else {
        streamMode.set(channelId, "hls");
      }
    } else if (!(await masterAlive(data.streamUrl, data.streamHeaders))) {
      throw down();
    } else {
      streamMode.set(channelId, "drm");
    }
  }

  deadCache.delete(key);
  const hdnea = extractHdneaToken(data.streamUrl);
  const expSec = extractTokenExpiryEpochSec(hdnea);
  const expiresAtMs = expSec > 0 ? expSec * 1000 : Date.now() + 90_000;
  const entry: CachedStream = { key, data, hdnea, expiresAtMs };
  cache.set(key, entry);
  return entry;
}

/** Returns cached stream data, re-resolving a fresh __hdnea__ token ~15s before it expires. */
export async function getStream(key: string): Promise<CachedStream> {
  const c = cache.get(key);
  if (c && Date.now() < c.expiresAtMs - 15_000) return c;
  return resolve(key);
}

/** Rewrites the `__hdnea__` query token in a Jio URL to the freshest one. */
function withFreshToken(url: string, hdnea: string): string {
  if (!hdnea) return url;
  const i = url.indexOf(HDNEA_MARKER);
  return i >= 0 ? url.slice(0, i + HDNEA_MARKER.length) + hdnea : url;
}

/**
 * Proxies one upstream request (manifest or segment) with a fresh token + the stream headers, so the
 * browser never talks to the Jio CDN directly (defeats CORS) and never sees an expired token.
 */
export async function proxyUpstream(
  channelId: string,
  targetUrl: string,
  rangeHeader?: string
): Promise<Response> {
  const c = await getStream(channelId);
  const url = withFreshToken(targetUrl, c.hdnea);
  // The AES-128 key for the non-DRM "Fallback" HLS lives on tv.media.jio.com and authenticates like
  // the Widevine license server (ssoToken/Accesstoken/crmid…), NOT like the CDN (which uses the
  // __hdnea__ cookie). Sending stream headers there yields a 403 and playback fails to decrypt, so use
  // the license headers for key requests instead.
  const isKey = /\.pkey(\?|$)/i.test(targetUrl) || /aes128\.key/i.test(targetUrl) || /(^|\/\/)tv\.media\.jio\.com\//i.test(targetUrl);
  const headers: Record<string, string> = { ...(isKey ? c.data.licenseHeaders : c.data.streamHeaders) };
  if (!isKey && c.hdnea) headers["Cookie"] = HDNEA_MARKER + c.hdnea;
  if (rangeHeader) headers["Range"] = rangeHeader;
  return fetch(url, { headers });
}

/** Forwards a Widevine license challenge to Jio's license server with the correct headers. */
export async function proxyLicense(channelId: string, challenge: Buffer): Promise<Buffer> {
  const c = await getStream(channelId);
  if (!c.data.licenseUrl) throw new Error("Channel has no DRM license URL");
  const res = await fetch(c.data.licenseUrl, {
    method: "POST",
    headers: c.data.licenseHeaders,
    body: new Uint8Array(challenge),
  });
  if (!res.ok) throw new Error(`License request failed (HTTP ${res.status})`);
  return Buffer.from(await res.arrayBuffer());
}

/**
 * Drops HLS master-playlist variants whose vertical resolution exceeds `maxHeight` (e.g. 720),
 * keeping the `#EXT-X-STREAM-INF` tag together with its URI line. If every variant is above the cap
 * we keep the single lowest one so the playlist is never left empty. Non-variant lines pass through.
 */
function capVariants(lines: string[], maxHeight: number): string[] {
  const heightOf = (tag: string) => {
    const m = /RESOLUTION=\d+x(\d+)/i.exec(tag);
    return m ? Number(m[1]) : 0;
  };
  // First pass: is any variant at/below the cap?
  let anyKept = false;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
      const h = heightOf(lines[i]);
      if (!h || h <= maxHeight) { anyKept = true; break; }
    }
  }
  // If all variants are above the cap, find the smallest to keep as a fallback.
  let smallest = Infinity;
  if (!anyKept) {
    for (const l of lines) if (l.trim().startsWith("#EXT-X-STREAM-INF")) smallest = Math.min(smallest, heightOf(l) || Infinity);
  }
  const out: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    if (t.startsWith("#EXT-X-STREAM-INF")) {
      const h = heightOf(lines[i]);
      const keep = anyKept ? (!h || h <= maxHeight) : h === smallest;
      const uri = i + 1 < lines.length ? lines[i + 1] : "";
      if (keep) { out.push(lines[i]); if (uri) out.push(uri); }
      i++; // skip the URI line either way (it belongs to this variant)
      continue;
    }
    if (t.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
      // Trick-play (keyframe) streams carry their URI inline; drop the ones above the cap too.
      const h = heightOf(lines[i]);
      if (h && h > maxHeight) continue;
      out.push(lines[i]);
      continue;
    }
    out.push(lines[i]);
  }
  return out;
}

/**
 * Rewrites a manifest so the browser player resolves media correctly through the proxy:
 *  - **DASH (.mpd):** inject an absolute top-level `<BaseURL>` (the real manifest directory) when one
 *    isn't already present, so relative SegmentTemplate media resolves to absolute Jio URLs — which
 *    the Shaka request filter then routes back through `/api/proxy`.
 *  - **HLS (.m3u8):** rewrite every segment / key / variant URI to an absolute `/api/proxy?...` link,
 *    resolved against the playlist URL.
 * Returns the rewritten text, or null if this body isn't a manifest we should touch.
 */
export function rewriteManifest(
  body: string,
  contentType: string,
  requestedUrl: string,
  segPrefix: string,
  maxHeight?: number
): string | null {
  const ct = contentType.toLowerCase();
  const isMpd = ct.includes("dash+xml") || body.trimStart().startsWith("<");
  const isHls = ct.includes("mpegurl") || body.trimStart().startsWith("#EXTM3U");
  const baseDir = requestedUrl.slice(0, requestedUrl.lastIndexOf("/") + 1);
  const proxyPrefix = segPrefix; // caller supplies the full prefix ending in "u="

  if (isMpd) {
    if (/<BaseURL>\s*https?:\/\//i.test(body)) return body; // already absolute
    return body.replace(/(<MPD\b[^>]*>)/i, `$1<BaseURL>${baseDir}</BaseURL>`);
  }

  if (isHls) {
    const abs = (u: string) => {
      try {
        return new URL(u, requestedUrl).toString();
      } catch {
        return u;
      }
    };
    const wrap = (u: string) => proxyPrefix + encodeURIComponent(abs(u));
    const lines = maxHeight && maxHeight > 0 ? capVariants(body.split("\n"), maxHeight) : body.split("\n");
    return lines
      .map((line) => {
        const t = line.trim();
        if (t === "") return line;
        if (t.startsWith("#")) {
          // Rewrite URI="..." attributes (EXT-X-KEY / MAP / MEDIA / I-FRAME-STREAM-INF).
          return line.replace(/URI="([^"]+)"/g, (_m, u) => `URI="${wrap(u)}"`);
        }
        return wrap(t); // a segment or variant-playlist line
      })
      .join("\n");
  }

  return null;
}

/** The real manifest URL + DRM flags the web player needs to configure Shaka. `key` is the cid. */
export async function getPlaybackInfo(key: string) {
  const c = await getStream(key);
  // "PayWall" in the URL = not subscribed. (A plain "Fallback" is a real non-DRM stream — entitled.)
  const entitled = !/paywall/i.test(c.data.streamUrl);
  return {
    channelId: key,
    isMpd: c.data.isMpd,
    manifestUrl: c.data.streamUrl,
    hasDrm: c.data.isMpd && c.data.licenseUrl.length > 0,
    entitled,
  };
}
