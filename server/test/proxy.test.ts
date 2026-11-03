import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// db.ts opens SQLite in DATA_DIR at import time, so point it at a throwaway dir first.
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "jtv-test-"));

/** Replaces global fetch for one test; records every URL + headers it was asked for. */
function mockFetch(answer: (url: string) => Response) {
  const calls: { url: string; headers: Record<string, string> }[] = [];
  const real = globalThis.fetch;
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    calls.push({ url: String(url), headers: (init?.headers ?? {}) as Record<string, string> });
    return answer(String(url));
  }) as typeof fetch;
  return { calls, restore: () => { globalThis.fetch = real; } };
}

test("Jio channel: proxy refuses a non-Jio host (no credentials leak)", async () => {
  const { proxyUpstream } = await import("../src/proxy/streamProxy");
  const { isExtraChannel } = await import("../src/jio/extraChannels");
  assert.equal(isExtraChannel("143"), false);
  const f = mockFetch(() => new Response("x"));
  try {
    for (const u of ["https://evil.workers.dev/x", "https://a.pages.dev/x", "https://d1.cloudfront.net/x", "https://raw.githubusercontent.com/x"]) {
      await assert.rejects(proxyUpstream("143", u), /untrusted URL/);
    }
    assert.equal(f.calls.length, 0);
  } finally { f.restore(); }
});

test("extra channel: proxy fetches its own third-party host with its own headers", async () => {
  const f = mockFetch(() => new Response("#EXTM3U\n", { status: 200 }));
  const { initExtraChannels, getExtraChannelSync, stopExtraChannelsScheduler } = await import("../src/jio/extraChannels");
  const { proxyUpstream } = await import("../src/proxy/streamProxy");
  try {
    initExtraChannels();
    const ch = getExtraChannelSync("162");
    assert.ok(ch && !/(^|\.)jio\.com$/.test(new URL(ch.streamUrl).hostname));
    f.calls.length = 0;
    const res = await proxyUpstream("162", ch.streamUrl);
    assert.equal(res.status, 200);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].url, ch.streamUrl);
    assert.ok(!Object.keys(f.calls[0].headers).some((h) => /ssotoken|accesstoken|crmid/i.test(h)));
  } finally { stopExtraChannelsScheduler(); f.restore(); }
});

test("fetchJio: a credentialed (Jio) request never follows a redirect off Jio hosts", async () => {
  const { fetchJio } = await import("../src/proxy/streamProxy");
  const f = mockFetch((url) =>
    url.includes("jio.com")
      ? new Response(null, { status: 302, headers: { location: "https://evil.workers.dev/steal" } })
      : new Response("ok")
  );
  try {
    await assert.rejects(fetchJio("https://jiotvpllive.cdn.jio.com/a.m3u8", { headers: { ssoToken: "s" } }, true), /untrusted URL/);
    assert.deepEqual(f.calls.map((c) => c.url), ["https://jiotvpllive.cdn.jio.com/a.m3u8"]);
    // Extra-channel requests (no Jio creds) may still hop to an allowed third-party CDN.
    f.calls.length = 0;
    const res = await fetchJio("https://jiotvpllive.cdn.jio.com/a.m3u8", { headers: {} }, false);
    assert.equal(await res.text(), "ok");
    assert.equal(f.calls.length, 2);
  } finally { f.restore(); }
});
