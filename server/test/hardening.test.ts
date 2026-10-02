import { test } from "node:test";
import assert from "node:assert/strict";
import Fastify from "fastify";
import rateLimit from "@fastify/rate-limit";
import { redactUrl } from "../src/util/redact";
import { baseUrl } from "../src/util/baseUrl";
import { normalizeCode, generateCode, CODE_MIN } from "../src/util/code";
import { parseTrustProxy } from "../src/config";

test("redactUrl masks every code= query parameter", () => {
  assert.equal(redactUrl("/playlist.m3u?code=ABC123&epg=1"), "/playlist.m3u?code=[redacted]&epg=1");
  assert.equal(redactUrl("/seg?cid=1&code=XY&u=https%3A%2F%2Fx"), "/seg?cid=1&code=[redacted]&u=https%3A%2F%2Fx");
  assert.equal(redactUrl("/live/1.m3u8?CODE=abc"), "/live/1.m3u8?CODE=[redacted]");
  assert.equal(redactUrl("/api/status"), "/api/status");
  assert.equal(redactUrl("/x?barcode=1"), "/x?barcode=1");
});

test("parseTrustProxy", () => {
  assert.equal(parseTrustProxy(undefined), true);
  assert.equal(parseTrustProxy(""), true);
  assert.equal(parseTrustProxy("true"), true);
  assert.equal(parseTrustProxy("false"), false);
  assert.equal(parseTrustProxy("0"), false);
  assert.equal(parseTrustProxy("2"), 2);
  assert.equal(parseTrustProxy("127.0.0.1"), "127.0.0.1");
  assert.deepEqual(parseTrustProxy("10.0.0.0/8, 172.16.0.0/12"), ["10.0.0.0/8", "172.16.0.0/12"]);
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
