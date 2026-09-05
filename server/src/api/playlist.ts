import zlib from "node:zlib";
import { Readable } from "node:stream";
import { randomUUID } from "node:crypto";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { getChannels, type Channel } from "../jio/channels";
import { getNativeEpg } from "../jio/epg";
import { getPlaybackInfo, proxyUpstream, rewriteManifest } from "../proxy/streamProxy";
import { getFavorites, hasCode } from "../store/db";
import { isAuthEnabled, envServerToken, getEpgConfig } from "../store/settings";

/**
 * Public (access-code authenticated) endpoints for external IPTV players — VLC, TiviMate, OTT
 * Navigator, Kodi. Unlike the browser player (cookie/admin session) these are reachable with a `?code=`
 * query param so a TV app or player with no cookie jar can use them. When server auth is disabled the
 * code is not required. Only the non-DRM (HLS) channels are servable here — DRM/Widevine channels can't
 * be decrypted by generic players, so `/live` returns 415 for them.
 */

/** True when the request carries a valid access code (or auth is disabled entirely). */
function codeOk(req: FastifyRequest): boolean {
  if (!isAuthEnabled()) return true;
  const code = String((req.query as Record<string, unknown>).code ?? "").trim();
  const env = envServerToken();
  return (!!env && code === env) || hasCode(code) || hasCode(code.toUpperCase());
}

function requireCode(req: FastifyRequest, reply: FastifyReply, done: () => void) {
  if (!codeOk(req)) {
    reply.code(401).send({ error: "Invalid or missing access code — append ?code=YOURCODE" });
    return;
  }
  done();
}

/** Absolute base URL of this server as the client reached it (honours host + scheme). */
function baseUrl(req: FastifyRequest): string {
  const host = req.headers.host ?? `localhost`;
  return `${req.protocol}://${host}`;
}

function splitCsv(v: unknown): Set<string> {
  const s = String(v ?? "").trim();
  if (!s) return new Set();
  return new Set(s.split(",").map((x) => x.trim().toLowerCase()).filter(Boolean));
}

/** Escapes a value for an M3U attribute (`key="value"`). */
function attr(v: string): string {
  return v.replace(/"/g, "'");
}

export async function registerPlaylistRoutes(app: FastifyInstance): Promise<void> {
  // ── M3U playlist for external players ──
  app.get("/playlist.m3u", { preHandler: requireCode }, async (req, reply) => {
    const q = req.query as Record<string, string | undefined>;
    const code = (q.code ?? "").trim();
    const langs = splitCsv(q.lang);
    const groups = splitCsv(q.group);
    const onlyFav = q.fav === "1" || q.fav === "true";
    const includeEpg = q.epg === "1" || q.epg === "true";
    const enableCatchup = q.catchup === "1" || q.catchup === "true";
    const hideDrm = q.drm === "hide" || q.drm === "0" || q.drm === "false";
    const quality = String(q.quality ?? "auto").toLowerCase();

    const all = await getChannels();
    const favs = onlyFav ? new Set(getFavorites()) : null;
    const channels = all.filter((c) => {
      if (langs.size && !langs.has(c.language.toLowerCase())) return false;
      if (groups.size && !groups.has(c.group.toLowerCase())) return false;
      if (favs && !favs.has(c.id)) return false;
      if (hideDrm && c.isDrm) return false;
      return true;
    });

    const base = baseUrl(req);
    const codeQ = code ? `&code=${encodeURIComponent(code)}` : "";
    const qualityQ = quality && quality !== "auto" ? `&q=${encodeURIComponent(quality)}` : "";

    const lines: string[] = [];
    const header = includeEpg
      ? `#EXTM3U url-tvg="${base}/epg.xml${code ? `?code=${encodeURIComponent(code)}` : ""}"`
      : "#EXTM3U";
    lines.push(header);

    for (const c of channels) {
      const streamUrl = `${base}/live/${encodeURIComponent(c.id)}.m3u8?ts=1${codeQ}${qualityQ}`;
      const catchupAttr = enableCatchup
        ? `catchup="default" catchup-days="7" catchup-source="${base}/live/${encodeURIComponent(c.id)}.m3u8?utc={utc}&lutc={lutc}${codeQ}${qualityQ}" `
        : "";
      lines.push(
        `#EXTINF:-1 tvg-id="${attr(c.id)}" tvg-name="${attr(c.name)}" tvg-logo="${attr(c.logoUrl)}" ` +
          `group-title="${attr(c.group)}" tvg-language="${attr(c.language)}" ${catchupAttr},${c.name}`
      );
      lines.push(streamUrl);
    }

    reply.header("content-type", "audio/x-mpegurl; charset=utf-8");
    reply.header("content-disposition", 'attachment; filename="jtv-playlist.m3u"');
    return reply.send(lines.join("\n") + "\n");
  });

  // ── Resolve one channel to a playable (proxied) HLS manifest for external players ──
  app.get<{ Params: { id: string } }>("/live/:id", { preHandler: requireCode }, async (req, reply) => {
    const rawId = req.params.id.replace(/\.(m3u8|mpd|ts)$/i, "");
    const q = req.query as Record<string, unknown>;
    const code = String(q.code ?? "").trim();
    const maxHeight = qualityToHeight(String(q.q ?? ""));
    const utcRaw = String(q.utc ?? q.lutc ?? "").trim();

    let key = rawId;
    if (utcRaw) {
      // Catch-up replay requested via IPTV {utc} parameter
      const utcNum = Number(utcRaw);
      const utcMs = Number.isFinite(utcNum) ? (utcNum > 1e11 ? utcNum : utcNum * 1000) : Date.parse(utcRaw);
      if (Number.isFinite(utcMs) && utcMs > 0) {
        try {
          const epg = await getNativeEpg(rawId, { fullCatchup: true });
          const prog = epg.find((p) => utcMs >= p.startMs && utcMs < p.stopMs);
          if (prog && prog.srno) {
            const cu = {
              c: rawId,
              s: prog.srno,
              p: prog.showId ?? "",
              b: prog.startMs,
              e: prog.stopMs,
              t: prog.showtime ?? "",
            };
            const b64 = Buffer.from(JSON.stringify(cu), "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
            key = `cu.${b64}`;
          } else {
            const cu = {
              c: rawId,
              s: randomUUID(),
              p: "",
              b: utcMs,
              e: utcMs + 3600_000,
              t: "",
            };
            const b64 = Buffer.from(JSON.stringify(cu), "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
            key = `cu.${b64}`;
          }
        } catch {
          /* fallback to live */
        }
      }
    }

    try {
      const info = await getPlaybackInfo(key);
      if (info.isMpd) {
        return reply
          .code(415)
          .send("# This channel is DRM (Widevine) and cannot be played in external players. Use the web player or the TV app.");
      }
      const upstream = await proxyUpstream(key, info.manifestUrl);
      const ct = upstream.headers.get("content-type") ?? "application/vnd.apple.mpegurl";
      const text = await upstream.text();
      const codeQ = code ? `&code=${encodeURIComponent(code)}` : "";
      const segPrefix = `${baseUrl(req)}/seg?cid=${encodeURIComponent(key)}${codeQ}&u=`;
      const rewritten = rewriteManifest(text, ct, info.manifestUrl, segPrefix, maxHeight) ?? text;
      reply.header("content-type", "application/vnd.apple.mpegurl");
      return reply.send(rewritten);
    } catch (err) {
      return reply.code(502).send(`# ${(err as Error).message}`);
    }
  });

  // ── Segment / nested-manifest proxy for external players (code-authed twin of /api/proxy) ──
  app.get<{ Querystring: { cid?: string; u?: string } }>("/seg", { preHandler: requireCode }, async (req, reply) => {
    const { cid, u } = req.query;
    const code = String((req.query as Record<string, unknown>).code ?? "").trim();
    if (!cid || !u) return reply.code(400).send({ error: "cid and u are required" });
    try {
      const target = decodeURIComponent(u);
      const isManifestUrl = /\.(mpd|m3u8)(\?|$)/i.test(target);
      const upstream = await proxyUpstream(cid, target, isManifestUrl ? undefined : (req.headers["range"] as string | undefined));
      const contentType = upstream.headers.get("content-type") ?? "";

      const looksManifest =
        isManifestUrl ||
        contentType.toLowerCase().includes("mpegurl") ||
        contentType.toLowerCase().includes("dash+xml");
      if (looksManifest) {
        const text = await upstream.text();
        const codeQ = code ? `&code=${encodeURIComponent(code)}` : "";
        const rewritten = rewriteManifest(text, contentType, target, `${baseUrl(req)}/seg?cid=${encodeURIComponent(cid)}${codeQ}&u=`);
        // Always 200 — full rewritten document (a pass-through 206 without Content-Range breaks players).
        reply.code(200);
        reply.header("content-type", contentType || "application/vnd.apple.mpegurl");
        return reply.send(rewritten ?? text);
      }

      reply.code(upstream.status);
      for (const h of ["content-type", "content-length", "accept-ranges", "content-range"]) {
        const v = upstream.headers.get(h);
        if (v) reply.header(h, v);
      }
      if (!upstream.body) return reply.send();
      return reply.send(Readable.fromWeb(upstream.body as any));
    } catch (err) {
      return reply.code(502).send({ error: (err as Error).message });
    }
  });

  // ── XMLTV guide for external players ──
  app.get("/epg.xml", { preHandler: requireCode }, async (req, reply) => {
    reply.header("content-type", "application/xml; charset=utf-8");
    const cfg = getEpgConfig();
    if (cfg.mode === "xmltv" && cfg.url) {
      try {
        return reply.send(await fetchXmltv(cfg.url));
      } catch {
        /* fall through to the channel-only guide */
      }
    }
    // Native mode (or a failed XMLTV download): emit a channel-only guide so players still map
    // names + logos. (Per-programme native EPG for every channel is too many requests to do here.)
    return reply.send(await channelOnlyXmltv());
  });
}

function qualityToHeight(q: string): number | undefined {
  const n = Number(String(q).replace(/[^\d]/g, ""));
  return Number.isFinite(n) && n > 0 ? n : undefined;
}

// 30-minute cache of the downloaded XMLTV text so repeated player refreshes don't re-download.
let xmltvCache: { at: number; text: string } | null = null;
async function fetchXmltv(url: string): Promise<string> {
  if (xmltvCache && Date.now() - xmltvCache.at < 30 * 60 * 1000) return xmltvCache.text;
  const res = await fetch(url, { headers: { "User-Agent": "Mozilla/5.0" } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  let buf = Buffer.from(await res.arrayBuffer());
  if (buf[0] === 0x1f && buf[1] === 0x8b) buf = zlib.gunzipSync(buf);
  const text = buf.toString("utf8");
  xmltvCache = { at: Date.now(), text };
  return text;
}

function xmlEscape(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

async function channelOnlyXmltv(): Promise<string> {
  const channels = await getChannels();
  const parts = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="JTV Server">'];
  for (const c of channels as Channel[]) {
    parts.push(
      `<channel id="${xmlEscape(c.id)}"><display-name>${xmlEscape(c.name)}</display-name>` +
        `<icon src="${xmlEscape(c.logoUrl)}"/></channel>`
    );
  }
  parts.push("</tv>");
  return parts.join("\n");
}
