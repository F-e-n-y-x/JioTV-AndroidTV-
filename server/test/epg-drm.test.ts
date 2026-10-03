import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import type { Channel } from "../src/jio/channels";
import type { EpgProgram } from "../src/jio/epg";

// db.ts opens SQLite in DATA_DIR at import time, so point it at a throwaway dir first.
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "jtv-test-"));
process.env.DATA_DIR = tmpDir;

const ch = (id: string, name: string, extra: Partial<Channel> = {}): Channel => ({
  id, name, logoUrl: `https://x/${id}.png`, group: "News", language: "Hindi", isDrm: false, channelNumber: Number(id), ...extra,
});

/** Minimal well-formedness check: every open tag is closed in order (no XML parser dependency). */
function assertWellFormed(xml: string) {
  const stack: string[] = [];
  const re = /<(\/?)([a-zA-Z][\w-]*)([^>]*?)(\/?)>/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(xml))) {
    const [, close, name, , self] = m;
    if (self) continue;
    if (close) assert.equal(stack.pop(), name, `mismatched </${name}>`);
    else stack.push(name);
  }
  assert.deepEqual(stack, [], "unclosed tags");
  // No raw '&' that isn't an entity.
  assert.ok(!/&(?!amp;|lt;|gt;|quot;|apos;)/.test(xml), "unescaped &");
}

test("writeXmltvGz streams a gzip file that gunzips to valid XMLTV", async () => {
  const { writeXmltvGz } = await import("../src/jio/nativeXmltv");
  const channels = Array.from({ length: 40 }, (_, i) => ch(String(100 + i), i === 3 ? "A & B <HD>" : `Ch ${i}`));
  const t0 = Date.UTC(2026, 9, 2, 0, 0, 0);
  const fetchProgrammes = async (id: string): Promise<EpgProgram[]> => {
    await new Promise((r) => setTimeout(r, Number(id) % 5)); // finish out of order
    if (id === "105") return []; // a channel with no guide
    return Array.from({ length: 30 }, (_, k) => ({
      title: k === 0 ? `News & "Views" ${id}` : `Show ${k}`,
      description: k % 2 ? "Desc with <tags> and \u0001 control" : "",
      startMs: t0 + k * 1800_000,
      stopMs: t0 + (k + 1) * 1800_000,
      poster: k === 1 ? "https://img/a.jpg?x=1&y=2" : undefined,
      episodeNum: k === 2 ? 7 : undefined,
      rating: k === 3 ? "UA" : undefined,
    }));
  };
  const out = path.join(tmpDir, "epg.xml.gz");
  const count = await writeXmltvGz(channels, fetchProgrammes, out, 12);
  assert.equal(count, 39 * 30);

  const xml = zlib.gunzipSync(fs.readFileSync(out)).toString("utf8");
  assert.ok(xml.startsWith('<?xml version="1.0" encoding="UTF-8"?>'));
  assert.ok(xml.trimEnd().endsWith("</tv>"));
  assertWellFormed(xml.replace(/^<\?xml[^>]*\?>/, ""));
  assert.equal((xml.match(/<channel /g) ?? []).length, 40);
  assert.equal((xml.match(/<programme /g) ?? []).length, 39 * 30);
  // All channels come before the first programme.
  assert.ok(xml.lastIndexOf("<channel ") < xml.indexOf("<programme "));
  assert.ok(xml.includes("<display-name>A &amp; B &lt;HD&gt;</display-name>"));
  assert.ok(xml.includes('<icon src="https://img/a.jpg?x=1&amp;y=2"/>'));
  assert.ok(xml.includes('<episode-num system="onscreen">7</episode-num>'));
  assert.ok(xml.includes("<rating><value>UA</value></rating>"));
  assert.ok(!xml.includes("\u0001"));
});

test("drm=hide hides channels the proxy learned are Widevine-only", async () => {
  const { filterPlaylistChannels } = await import("../src/api/playlistFilter");
  const db = await import("../src/store/db");
  db.saveStreamMode("2", "drm", 1);
  db.saveStreamMode("3", "hls", 1);
  db.saveStreamMode("4", "drm", 1);
  db.saveStreamMode("4", "hls", 2); // HLS came back: no longer hidden
  assert.deepEqual([...db.getDrmOnlyChannelIds()], ["2"]);
  assert.equal(db.getStreamMode("4"), "hls");

  const all = [ch("1", "a"), ch("2", "b"), ch("3", "c"), ch("4", "d"), ch("5", "e", { isDrm: true })];
  const base = { langs: new Set<string>(), groups: new Set<string>(), favs: null, drmOnlyIds: db.getDrmOnlyChannelIds() };
  assert.deepEqual(filterPlaylistChannels(all, { ...base, hideDrm: true }).map((c) => c.id), ["1", "3", "4"]);
  assert.deepEqual(filterPlaylistChannels(all, { ...base, hideDrm: false }).map((c) => c.id), ["1", "2", "3", "4", "5"]);
  // Other filters still apply alongside it.
  assert.deepEqual(
    filterPlaylistChannels(all, { ...base, hideDrm: true, favs: new Set(["2", "3"]) }).map((c) => c.id),
    ["3"]
  );
});

test("acceptsGzip honours q=0", async () => {
  const { acceptsGzip } = await import("../src/api/playlist");
  assert.equal(acceptsGzip("gzip, deflate, br"), true);
  assert.equal(acceptsGzip("br;q=1.0, gzip;q=0.8"), true);
  assert.equal(acceptsGzip("gzip;q=0"), false);
  assert.equal(acceptsGzip("identity"), false);
  assert.equal(acceptsGzip(undefined), false);
  assert.equal(acceptsGzip("*"), true);
});
