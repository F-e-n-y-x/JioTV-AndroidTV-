import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { redactSecrets } from "../src/util/redact";

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "jtv-reports-"));
process.env.DATA_DIR = tmpDir;

test("redactSecrets strips tokens, auth headers and phone numbers", () => {
  const raw = [
    "GET https://jiotvmblive.cdn.jio.com/x/index.m3u8?minrate=1&__hdnea__=st=1790000000~exp=1790003600~acl=/*~hmac=abc123 failed",
    "Authorization: Bearer abc.def.ghi",
    "ssoToken=AQIC5wM2LY4Sfcz; crmid=1234567890",
    '{"authToken":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.sig","refreshToken":"r-123"}',
    "accesstoken: xyz789",
    "Cookie: __hdnea__=foo",
    "mobile 9876543210 and +91 9123456789",
    "at com.fenyx.jtv.ui.player.TvPlayerScreen.kt:123",
  ].join("\n");
  const out = redactSecrets(raw);
  for (const secret of ["hmac=abc123", "abc.def.ghi", "AQIC5wM2LY4Sfcz", "1234567890", "eyJhbGci", "r-123", "xyz789", "foo", "9876543210", "9123456789"]) {
    assert.ok(!out.includes(secret), `leaked ${secret}\n${out}`);
  }
  assert.ok(out.includes("__hdnea__=[redacted]"));
  assert.ok(out.includes("TvPlayerScreen.kt:123"), "stack frames survive");
  assert.ok(out.includes("index.m3u8?minrate=1"), "the rest of the URL survives");
});

test("reports: stored redacted, newest first, only the last 50 kept", async () => {
  const { saveReport, listReports, MAX_REPORTS, deleteReport } = await import("../src/store/reports");
  assert.equal(saveReport({ text: "  " }, 1), null);
  assert.equal(saveReport("nope", 1), null);
  for (let i = 0; i < MAX_REPORTS + 5; i++) {
    saveReport({ kind: "crash", at: 1000 + i, appVersion: "2.0", device: "Box", android: "10", text: `boom ${i} ssoToken=SECRET${i}` }, 1_000_000 + i);
  }
  const all = listReports();
  assert.equal(all.length, MAX_REPORTS);
  assert.equal(all[0].text.startsWith(`boom ${MAX_REPORTS + 4}`), true);
  assert.ok(all.every((r) => !r.text.includes("SECRET")));
  assert.equal(fs.readdirSync(path.join(tmpDir, "reports")).length, MAX_REPORTS);
  deleteReport(all[0].id);
  assert.equal(listReports().length, MAX_REPORTS - 1);
  deleteReport("../../etc/passwd"); // ignored
});
