import { Readable } from "node:stream";
import type { FastifyInstance } from "fastify";
import { requireAdmin, requireServerToken } from "./auth";
import { getChannels } from "../jio/channels";
import { getNativeEpg } from "../jio/epg";
import { getXmltvEpg, refreshXmltv, xmltvStatus } from "../jio/xmltvEpg";
import { getEpgConfig, setEpgConfig } from "../store/settings";
import { getFavorites, toggleFavorite, setFavorites } from "../store/db";
import { getPlaybackInfo, proxyUpstream, proxyLicense, rewriteManifest } from "../proxy/streamProxy";

/**
 * Web-player endpoints (guarded by the admin session — the browser player is an admin feature; the
 * TVs play directly and don't use these).
 */
export async function registerPlayRoutes(app: FastifyInstance): Promise<void> {
  app.get("/api/channels", { preHandler: requireAdmin }, async () => ({
    channels: await getChannels(),
  }));

  app.get<{ Params: { id: string } }>(
    "/api/epg/:id",
    { preHandler: requireAdmin },
    async (req) => {
      // Use the XMLTV guide when configured (and it has data for this channel), else native Jio EPG.
      if (getEpgConfig().mode === "xmltv") {
        const x = getXmltvEpg(req.params.id);
        if (x.length) return { programs: x };
      }
      return { programs: await getNativeEpg(req.params.id) };
    }
  );

  // EPG source config + XMLTV refresh.
  app.get("/api/admin/epg", { preHandler: requireAdmin }, async () => ({ ...getEpgConfig(), ...xmltvStatus() }));
  app.post("/api/admin/epg", { preHandler: requireAdmin }, async (req) => {
    const { mode, url } = (req.body ?? {}) as { mode?: "native" | "xmltv"; url?: string };
    setEpgConfig(mode === "xmltv" ? "xmltv" : "native", url ?? "");
    const cfg = getEpgConfig();
    if (cfg.mode === "xmltv") refreshXmltv(cfg.url).catch(() => {}); // fire-and-forget download
    return { ok: true };
  });
  app.post("/api/admin/epg/refresh", { preHandler: requireAdmin }, async (_req, reply) => {
    const cfg = getEpgConfig();
    if (cfg.mode !== "xmltv") return reply.code(400).send({ error: "EPG mode is Native — switch to XMLTV first." });
    try {
      await refreshXmltv(cfg.url);
      return { ok: true, ...xmltvStatus() };
    } catch (e) {
      return reply.code(502).send({ error: (e as Error).message });
    }
  });

  // ── Favorites (shared across TVs + web player) ──
  // Web player (admin session):
  app.get("/api/favorites", { preHandler: requireAdmin }, async () => ({ ids: getFavorites() }));
  app.post<{ Params: { id: string } }>(
    "/api/favorites/:id/toggle",
    { preHandler: requireAdmin },
    async (req) => ({ favorited: toggleFavorite(req.params.id) })
  );
  // TVs (bearer token) — pull the shared set, or replace it with the TV's local set:
  app.get("/api/tv/favorites", { preHandler: requireServerToken }, async () => ({ ids: getFavorites() }));
  app.put<{ Body: { ids?: string[] } }>(
    "/api/tv/favorites",
    { preHandler: requireServerToken },
    async (req) => {
      const ids = Array.isArray(req.body?.ids) ? req.body!.ids!.map(String) : [];
      setFavorites(ids);
      return { ids: getFavorites() };
    }
  );

  // Manifest URL + DRM flags the player feeds to Shaka.
  app.get<{ Params: { id: string } }>(
    "/api/play/:id",
    { preHandler: requireAdmin },
    async (req, reply) => {
      try {
        return await getPlaybackInfo(req.params.id);
      } catch (err) {
        return reply.code(502).send({ error: (err as Error).message });
      }
    }
  );

  // Generic upstream proxy (manifest + segments), token injected server-side.
  app.get<{ Querystring: { cid?: string; u?: string } }>(
    "/api/proxy",
    // Hundreds of segment requests per minute: keep them out of the request log.
    { logLevel: "warn", preHandler: requireAdmin },
    async (req, reply) => {
      const { cid, u } = req.query;
      if (!cid || !u) return reply.code(400).send({ error: "cid and u are required" });
      try {
        const target = decodeURIComponent(u);
        // Don't forward Range for manifests: we buffer + rewrite the whole document, and a partial
        // (206) response would truncate it. Segments still honour Range for seeking.
        const isManifestUrl = /\.(mpd|m3u8)(\?|$)/i.test(target);
        const upstream = await proxyUpstream(cid, target, isManifestUrl ? undefined : (req.headers["range"] as string | undefined));
        const contentType = upstream.headers.get("content-type") ?? "";

        // Manifests are small: buffer + rewrite URLs so the browser resolves media via the proxy.
        const looksManifest =
          isManifestUrl ||
          contentType.toLowerCase().includes("mpegurl") ||
          contentType.toLowerCase().includes("dash+xml");
        if (looksManifest) {
          const text = await upstream.text();
          const rewritten = rewriteManifest(text, contentType, target, `/api/proxy?cid=${encodeURIComponent(cid)}&u=`);
          // Always 200: we return the full rewritten document, so a pass-through 206 (which hls.js
          // rejects when it lacks a Content-Range header) would be incorrect.
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
    }
  );

  // Widevine license proxy (raw challenge in, license bytes out).
  app.post<{ Params: { id: string } }>(
    "/api/play/:id/license",
    { preHandler: requireAdmin },
    async (req, reply) => {
      try {
        const challenge = req.body as Buffer;
        const license = await proxyLicense(req.params.id, challenge);
        reply.header("content-type", "application/octet-stream");
        return reply.send(license);
      } catch (err) {
        return reply.code(502).send({ error: (err as Error).message });
      }
    }
  );
}
