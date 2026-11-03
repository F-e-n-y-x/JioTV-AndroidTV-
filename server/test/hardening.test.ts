import { test } from "node:test";
import assert from "node:assert/strict";
import Fastify from "fastify";
import rateLimit from "@fastify/rate-limit";
import { redactUrl } from "../src/util/redact";
import { baseUrl } from "../src/util/baseUrl";
import { normalizeCode, generateCode, CODE_MIN } from "../src/util/code";
import { parseTrustProxy, DEFAULT_TRUST_PROXY } from "../src/config";
import { rateLimitKey } from "../src/util/clientIp";

test("redactUrl masks every code= query parameter", () => {
  assert.equal(redactUrl("/playlist.m3u?code=ABC123&epg=1"), "/playlist.m3u?code=[redacted]&epg=1");
  assert.equal(redactUrl("/seg?cid=1&code=XY&u=https%3A%2F%2Fx"), "/seg?cid=1&code=[redacted]&u=https%3A%2F%2Fx");
  assert.equal(redactUrl("/live/1.m3u8?CODE=abc"), "/live/1.m3u8?CODE=[redacted]");
  assert.equal(redactUrl("/api/status"), "/api/status");
  assert.equal(redactUrl("/x?barcode=1"), "/x?barcode=1");
});

test("parseTrustProxy", () => {
  assert.equal(parseTrustProxy(undefined), DEFAULT_TRUST_PROXY);
  assert.equal(parseTrustProxy(""), DEFAULT_TRUST_PROXY);
  assert.equal(parseTrustProxy("true"), true);
  assert.equal(parseTrustProxy("false"), false);
  assert.equal(parseTrustProxy("0"), false);
  assert.equal(parseTrustProxy("2"), 2);
  assert.equal(parseTrustProxy("127.0.0.1"), "127.0.0.1");
  assert.deepEqual(parseTrustProxy("10.0.0.0/8, 172.16.0.0/12"), ["10.0.0.0/8", "172.16.0.0/12"]);
});

test("client IP: proxy headers count only from a loopback/private peer by default", async () => {
  const app = Fastify({ trustProxy: parseTrustProxy(undefined) });
  const key = rateLimitKey(parseTrustProxy(undefined));
  app.get("/k", async (req) => ({ ip: req.ip, key: key(req) }));
  const hit = async (remoteAddress: string, headers: Record<string, string>) =>
    (await app.inject({ url: "/k", remoteAddress, headers })).json() as { ip: string; key: string };
  const spoof = { "cf-connecting-ip": "6.6.6.6", "x-forwarded-for": "7.7.7.7" };
  // Direct internet client: both headers ignored, keyed on the socket address.
  assert.deepEqual(await hit("203.0.113.9", spoof), { ip: "203.0.113.9", key: "203.0.113.9" });
  // cloudflared / caddy / docker on loopback or a private network: honoured.
  for (const peer of ["127.0.0.1", "::1", "10.0.0.2", "172.18.0.1", "192.168.1.5", "fd00::1"]) {
    assert.deepEqual(await hit(peer, spoof), { ip: "7.7.7.7", key: "6.6.6.6" }, peer);
  }
  assert.deepEqual(await hit("10.0.0.2", { "x-forwarded-for": "7.7.7.7" }), { ip: "7.7.7.7", key: "7.7.7.7" });
  await app.close();
  // Explicit overrides keep working.
  const req = (peer: string) => ({ headers: { "cf-connecting-ip": "6.6.6.6" }, ip: peer, socket: { remoteAddress: peer } }) as any;
  assert.equal(rateLimitKey(false)(req("127.0.0.1")), "127.0.0.1");
  assert.equal(rateLimitKey(true)(req("203.0.113.9")), "6.6.6.6");
  assert.equal(rateLimitKey("203.0.113.0/24")(req("203.0.113.9")), "6.6.6.6");
  assert.equal(rateLimitKey("203.0.113.0/24")(req("10.0.0.2")), "10.0.0.2");
});

test("baseUrl honours X-Forwarded-Proto/Host only with trustProxy", async () => {
  for (const trustProxy of [true, false]) {
    const app = Fastify({ trustProxy });
    app.get("/b", async (req) => baseUrl(req));
    const res = await app.inject({
      url: "/b",
      headers: { host: "internal:8080", "x-forwarded-proto": "https", "x-forwarded-host": "jtv.example.com" },
    });
    assert.equal(res.body, trustProxy ? "https://jtv.example.com" : "http://internal:8080");
    await app.close();
  }
});

test("new access codes need 6+ characters", () => {
  assert.equal(CODE_MIN, 6);
  assert.equal(normalizeCode("abcd"), null);
  assert.equal(normalizeCode("abc12"), null);
  assert.equal(normalizeCode(" abc123 "), "ABC123");
  assert.equal(generateCode(4).length, 6);
});

test("route-scoped rate limit returns 429 after the limit", async () => {
  const app = Fastify();
  await app.register(rateLimit, { global: false });
  app.post("/login", { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } }, async () => ({ ok: true }));
  app.get("/seg", async () => "ok");
  await app.ready();
  const codes: number[] = [];
  for (let i = 0; i < 11; i++) codes.push((await app.inject({ method: "POST", url: "/login" })).statusCode);
  assert.deepEqual(codes.slice(0, 10), Array(10).fill(200));
  assert.equal(codes[10], 429);
  for (let i = 0; i < 20; i++) assert.equal((await app.inject({ url: "/seg" })).statusCode, 200);
  await app.close();
});
