import proxyAddr from "@fastify/proxy-addr";
import type { FastifyRequest } from "fastify";

/**
 * Rate-limit key: CF-Connecting-IP only when the TCP peer is a proxy we trust (same rule Fastify uses
 * for X-Forwarded-For — a hop count trusts no peer there either); otherwise req.ip. A direct internet
 * client can't rotate the header to dodge the login / access-code limits.
 */
export function rateLimitKey(trustProxy: boolean | number | string | string[]) {
  const trusts =
    trustProxy === true ? () => true
    : trustProxy === false || typeof trustProxy === "number" ? () => false
    : proxyAddr.compile(typeof trustProxy === "string" ? trustProxy.split(",").map((s) => s.trim()) : trustProxy);
  return (req: Pick<FastifyRequest, "headers" | "ip" | "socket">): string => {
    const cf = req.headers["cf-connecting-ip"];
    const peer = req.socket.remoteAddress ?? "";
    return (typeof cf === "string" && cf && peer && trusts(peer, 0) && cf) || req.ip;
  };
}
