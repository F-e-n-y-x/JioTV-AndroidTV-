import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import Fastify from "fastify";
import rateLimit from "@fastify/rate-limit";

// db.ts / settings open DATA_DIR at import time, so point it at a throwaway dir first.
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "jtv-test-"));

const CODE = "ABC123";

/** Jio's channel list answers with two channels; everything else (extra-channel sources…) 404s. */
function mockJio() {
  const real = globalThis.fetch;
  globalThis.fetch = (async (url: string) => {
    const u = String(url);
    if (/dictionary/.test(u)) return new Response("{}");
    if (/jiotvapi\.cdn\.jio\.com/.test(u)) {
      return new Response(JSON.stringify({
        result: [
          { channel_id: 1, channel_name: "Clear News", logoUrl: "1.png", channelCategoryId: 1, channelLanguageId: 1 },
          { channel_id: 2, channel_name: "Locked Sports", logoUrl: "2.png", channelCategoryId: 1, channelLanguageId: 1 },
        ],
      }));
    }
    return new Response("nope", { status: 404 });
  }) as typeof fetch;
  return () => { globalThis.fetch = real; };
}

async function makeApp() {
  const settings = await import("../src/store/settings");
  const db = await import("../src/store/db");
  settings.setAdminPassword("test-password-1"); // auth ON: player routes need a code
  if (!db.hasCode(CODE)) db.addCode("tv", CODE, Date.now());
  db.saveStreamMode("2", "drm", Date.now()); // channel 2 learned as Widevine-only
  const { registerPlaylistRoutes } = await import("../src/api/playlist");
  const app = Fastify();
  app.addContentTypeParser("*", { parseAs: "buffer" }, (_req, body, done) => done(null, body));
  await app.register(rateLimit, { global: false });
  await registerPlaylistRoutes(app);
  await app.ready();
  return app;
}

test("drm=1 playlist: Widevine entry has KODIPROP lines, proxied URLs, no Jio tokens", async () => {
  const restore = mockJio();
  const app = await makeApp();
  try {
    const res = await app.inject({ url: `/playlist.m3u?code=${CODE}&drm=1`, headers: { host: "tv.lan:29180" } });
    assert.equal(res.statusCode, 200);
    const lines = res.body.split("\n");
    const i2 = lines.findIndex((l) => l.includes('tvg-id="2"'));
    assert.deepEqual(lines.slice(i2 - 4, i2 + 2), [
      "#KODIPROP:inputstream=inputstream.adaptive",
      "#KODIPROP:inputstream.adaptive.manifest_type=mpd",
      "#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha",
      `#KODIPROP:inputstream.adaptive.license_key=http://tv.lan:29180/license/2?code=${CODE}|Content-Type=application/octet-stream|R{SSM}|`,
      lines[i2],
      `http://tv.lan:29180/live/2.mpd?ts=1&code=${CODE}`,
    ]);
    // The clear channel stays a plain HLS entry.
    const i1 = lines.findIndex((l) => l.includes('tvg-id="1"'));
    assert.ok(!lines[i1 - 1].startsWith("#KODIPROP"));
    assert.equal(lines[i1 + 1], `http://tv.lan:29180/live/1.m3u8?ts=1&code=${CODE}`);
    assert.doesNotMatch(res.body, /hdnea|ssoToken|Accesstoken|crmid|jio\.com\/.*\.mpd/i);

    // Widevine entries are the default; drm=plain gives the old list (clearkey extras may still be .mpd).
    const dflt = await app.inject({ url: `/playlist.m3u?code=${CODE}`, headers: { host: "tv.lan:29180" } });
    assert.equal(dflt.body, res.body);
    const plain = await app.inject({ url: `/playlist.m3u?code=${CODE}&drm=plain`, headers: { host: "tv.lan:29180" } });
    assert.doesNotMatch(plain.body, /widevine|\/license\//);
    assert.match(plain.body, /\/live\/2\.m3u8\?ts=1/);
  } finally {
    restore();
    await app.close();
  }
});

test("DRM player routes need the access code", async () => {
  const restore = mockJio();
  const app = await makeApp();
  try {
    assert.equal((await app.inject({ url: "/playlist.m3u?drm=1" })).statusCode, 401);
    assert.equal((await app.inject({ url: "/live/2.mpd" })).statusCode, 401);
    assert.equal((await app.inject({ url: "/live/2.mpd?code=WRONG1" })).statusCode, 401);
    const lic = (url: string, payload = Buffer.from([1, 2, 3])) =>
      app.inject({ method: "POST", url, payload, headers: { "content-type": "application/octet-stream" } });
    assert.equal((await lic("/license/2")).statusCode, 401);
    assert.equal((await lic("/license/2?code=WRONG1")).statusCode, 401);
    assert.equal((await lic(`/license/2?code=${CODE}`, Buffer.alloc(0))).statusCode, 400);
    assert.equal((await app.inject({ url: "/dash/WRONG1/2/seg-1.m4s" })).statusCode, 401);
    assert.equal((await app.inject({ url: "/dash/-/2/seg-1.m4s" })).statusCode, 401);
    // A valid code gets past auth (no Jio login here, so the upstream step fails with 502, not 401).
    assert.equal((await app.inject({ url: `/dash/${CODE}/2/seg-1.m4s` })).statusCode, 502);
  } finally {
    restore();
    await app.close();
  }
});

test("license route is rate-limited", async () => {
  const app = await makeApp();
  try {
    const codes: number[] = [];
    for (let i = 0; i < 61; i++) codes.push((await app.inject({ method: "POST", url: "/license/2" })).statusCode);
    assert.equal(codes[59], 401);
    assert.equal(codes[60], 429);
  } finally {
    await app.close();
  }
});

test("rewriteMpdForPlayer points segments at the proxy and drops Location", async () => {
  const { rewriteMpdForPlayer } = await import("../src/proxy/streamProxy");
  const mpdUrl = "https://jiotvmblive.cdn.jio.com/bpk-tv/Ch/output/index.mpd?__hdnea__=st=1~exp=2~hmac=SECRET";
  const proxyDir = "http://tv.lan:29180/dash/ABC123/2/";
  const noBase = '<?xml version="1.0"?><MPD type="dynamic"><Location>https://jiotvmblive.cdn.jio.com/x.mpd</Location><Period/></MPD>';
  const a = rewriteMpdForPlayer(noBase, mpdUrl, proxyDir);
  assert.match(a, /<MPD type="dynamic"><BaseURL>http:\/\/tv\.lan:29180\/dash\/ABC123\/2\/<\/BaseURL>/);
  assert.doesNotMatch(a, /Location|SECRET|jio\.com/);

  const relBase = "<MPD><BaseURL>dash/</BaseURL><Period/></MPD>";
  assert.equal(rewriteMpdForPlayer(relBase, mpdUrl, proxyDir), `<MPD><BaseURL>${proxyDir}dash/</BaseURL><Period/></MPD>`);
  const absBase = "<MPD><BaseURL>https://jiotvmblive.cdn.jio.com/bpk-tv/Ch/output/dash/</BaseURL></MPD>";
  assert.equal(rewriteMpdForPlayer(absBase, mpdUrl, proxyDir), `<MPD><BaseURL>${proxyDir}dash/</BaseURL></MPD>`);
});

test("redactUrl masks the code in /dash paths", async () => {
  const { redactUrl } = await import("../src/util/redact");
  assert.equal(redactUrl("/dash/ABC123/2/dash/seg-1.m4s"), "/dash/[redacted]/2/dash/seg-1.m4s");
  assert.equal(redactUrl("/seg?cid=2&code=ABC123&u=x"), "/seg?cid=2&code=[redacted]&u=x");
});
