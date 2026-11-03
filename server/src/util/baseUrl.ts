import type { FastifyRequest } from "fastify";

/** Absolute base URL of this server as the client reached it. With `trustProxy` on, Fastify derives
 *  `protocol` / `host` from X-Forwarded-Proto / X-Forwarded-Host, so links are https behind Cloudflare
 *  or Caddy instead of the internal http://. */
export function baseUrl(req: Pick<FastifyRequest, "protocol" | "host">): string {
  return `${req.protocol}://${req.host || "localhost"}`;
}
