import assert from "node:assert/strict";
import test from "node:test";
import {
  initExtraChannels,
  getExtraChannels,
  isExtraChannel,
  getExtraChannelSync,
  isTokenExpired,
} from "../src/jio/extraChannels";
import { getChannels } from "../src/jio/channels";

test("extra channels initialization and lookup", async () => {
  initExtraChannels();
  const channels = await getExtraChannels();
  assert.ok(channels.length > 20, "Should have more than 20 extra channels loaded");

  // Verify Sony Ten 1 HD
  assert.equal(isExtraChannel("162"), true);
  const sony162 = getExtraChannelSync("162");
  assert.ok(sony162);
  assert.equal(sony162.channelNumber, 162);
  assert.equal(sony162.group, "Sports");
  assert.ok(sony162.streamUrl.includes(".m3u8"));

  // Verify Zee TV HD
  assert.equal(isExtraChannel("167"), true);
  const zee167 = getExtraChannelSync("167");
  assert.ok(zee167);
  assert.equal(zee167.channelNumber, 167);
  assert.equal(zee167.isMpd, true);

  // Verify Star Sports 1 HD
  assert.equal(isExtraChannel("1106"), true);
  const star1106 = getExtraChannelSync("1106");
  assert.ok(star1106);
  assert.equal(star1106.channelNumber, 1106);
  assert.equal(star1106.group, "Sports");

  // Verify SET HD
  assert.equal(isExtraChannel("291"), true);
  const set291 = getExtraChannelSync("291");
  assert.ok(set291);
  assert.equal(set291.channelNumber, 291);
  assert.equal(set291.group, "Entertainment");
});

test("isTokenExpired logic", () => {
  const expiredUrl = "https://example.com/index.mpd?__hdnea__=st=1000~exp=1500~acl=/*";
  assert.equal(isTokenExpired(expiredUrl), true);

  const futureEpoch = Math.floor(Date.now() / 1000) + 10000;
  const freshUrl = `https://example.com/index.mpd?__hdnea__=st=1000~exp=${futureEpoch}~acl=/*`;
  assert.equal(isTokenExpired(freshUrl), false);

  assert.equal(isTokenExpired("https://example.com/plain.m3u8"), false);
});
