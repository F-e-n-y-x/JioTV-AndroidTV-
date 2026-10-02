import path from "node:path";
import Fastify, { type FastifyInstance } from "fastify";
import cookie from "@fastify/cookie";
import fastifyStatic from "@fastify/static";
import rateLimit from "@fastify/rate-limit";
import { config } from "./config";
import { registerRoutes } from "./api/routes";
import { registerPlayRoutes } from "./api/play";
import { registerPlaylistRoutes } from "./api/playlist";
import { startRefreshScheduler, stopRefreshScheduler } from "./refresh";
import { isAdminConfigured } from "./store/settings";
import { ensureCert } from "./https";
import { redactUrl } from "./util/redact";
import { closeDb } from "./store/db";

/** Builds a fully-wired Fastify instance (used for both the HTTP and HTTPS listeners). */
async function buildApp(extra?: Record<string, unknown>): Promise<FastifyInstance> {
  // `extra` may carry an `https` option; cast because that changes Fastify's inferred server type.
  // maxParamLength default is 100 — too short for the base64 catch-up key in `/api/play/:id`.
  const app = Fastify({
    logger: {
      level: "info",
      // Same fields as Fastify's default request serializer, but with `?code=` masked: access codes
      // ride in the query string of /playlist.m3u, /live, /epg.xml and must not end up in the logs.
      serializers: {
        req: (req) => ({
          method: req.method,
          url: redactUrl(req.url),
          host: req.host,
          remoteAddress: req.ip,
          remotePort: req.socket?.remotePort,
        }),
      },
    },
    // Behind Cloudflare / Caddy: honour X-Forwarded-Proto/Host/For (see config.trustProxy).
    trustProxy: config.trustProxy as any,
    bodyLimit: 8_388_608,
    maxParamLength: 2000,
    ...(extra as any),
  }) as unknown as FastifyInstance;

  // Widevine license challenges arrive as raw binary — Shaka may send them with an unexpected (or no)
  // content-type, so parse ANY non-JSON body as a Buffer.
  app.addContentTypeParser("*", { parseAs: "buffer" }, (_req, body, done) => done(null, body));

  // Tolerate an EMPTY application/json body. Browsers (fetch) often send DELETE / bodyless POSTs with
  // `Content-Type: application/json` and no body; Fastify's built-in JSON parser 400s on that, which is
  // what blocked deleting TV access codes from the dashboard. Empty → {}, otherwise parse normally.
  app.addContentTypeParser("application/json", { parseAs: "string" }, (_req, body, done) => {
    const s = (body as string).trim();
    if (!s) return done(null, {});
    try {
      done(null, JSON.parse(s));
    } catch {
      const err = new Error("Invalid JSON body") as Error & { statusCode?: number };
      err.statusCode = 400;
      done(err, undefined);
    }
  });

  await app.register(cookie);

  // Rate limiting is opt-in per route (`config.rateLimit`): login / OTP / credentials / refresh /
  // playlist. Streaming routes (/live, /seg, /api/proxy) are deliberately NOT limited — a player makes
  // many requests a minute. Behind Cloudflare the client IP comes from CF-Connecting-IP (set by
  // Cloudflare itself); otherwise from req.ip (which honours X-Forwarded-For when trustProxy is on).
  await app.register(rateLimit, {
    global: false,
    keyGenerator: (req) => {
      const cf = config.trustProxy ? req.headers["cf-connecting-ip"] : undefined;
      return (typeof cf === "string" && cf) || req.ip;
    },
  });
  await registerRoutes(app);
  await registerPlayRoutes(app);
  await registerPlaylistRoutes(app);

  const webRoot = path.join(__dirname, "..", "web", "dist");
  await app.register(fastifyStatic, {
    root: webRoot,
    prefix: "/",
    // Hashed assets can cache forever; index.html must never cache so a rebuild is picked up.
    setHeaders: (res, filePath) => {
      if (filePath.endsWith(".html")) res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    },
  });

  // SPA fallback: any non-API route serves index.html so client routing works.
  app.setNotFoundHandler((req, reply) => {
    if (req.url.startsWith("/api")) return reply.code(404).send({ error: "Not found" });
    return reply.sendFile("index.html");
  });
  return app;
}

/** Every listener we started, so a SIGTERM/SIGINT can close them all. */
const apps: FastifyInstance[] = [];
let shuttingDown = false;

/**
 * Graceful stop for `docker stop` / Portainer redeploys (SIGTERM) and Ctrl-C (SIGINT): stop accepting
 * connections, let in-flight requests finish (app.close), close SQLite cleanly, then exit. A 10 s
 * backstop exits anyway if a long-lived stream keeps a connection open (Docker kills at 10 s too).
 */
async function shutdown(signal: string): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`[shutdown] ${signal} received, closing…`);
  setTimeout(() => {
    console.warn("[shutdown] timed out waiting for connections, exiting");
    process.exit(0);
  }, 10_000).unref();
  stopRefreshScheduler();
  await Promise.allSettled(apps.map((a) => a.close()));
  closeDb();
  process.exit(0);
}
process.once("SIGTERM", () => void shutdown("SIGTERM"));
process.once("SIGINT", () => void shutdown("SIGINT"));

async function main() {
  if (!isAdminConfigured()) {
    console.log("ℹ  First run — open the web UI to create an admin password (no .env editing needed).");
  }

  startRefreshScheduler();

  // HTTP
  try {
    const http = await buildApp();
    apps.push(http);
    await http.listen({ port: config.port, host: config.host });
    console.log(`JTV server (HTTP)  → http://${config.host}:${config.port}`);
  } catch (err) {
    console.error("Failed to start HTTP server:", err);
    process.exit(1);
  }

  // HTTPS (self-signed) — needed for browser Widevine/DRM off-localhost. Non-fatal if it can't bind.
  try {
    const { key, cert } = ensureCert();
    const https = await buildApp({ https: { key, cert } });
    apps.push(https);
    await https.listen({ port: config.httpsPort, host: config.host });
    console.log(`JTV server (HTTPS) → https://${config.host}:${config.httpsPort}  (self-signed; accept the warning)`);
  } catch (err) {
    console.warn("HTTPS listener not started:", (err as Error).message);
  }
}

void main();
