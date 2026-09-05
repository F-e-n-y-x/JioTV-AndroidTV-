import { randomUUID } from "node:crypto";
import { jio } from "../config";
import { jioRequest } from "./http";
import type { AuthData } from "./types";

export interface StreamData {
  streamUrl: string;
  licenseUrl: string;
  isMpd: boolean;
  streamHeaders: Record<string, string>;
  licenseHeaders: Record<string, string>;
}

/** Thrown for auth failures (401/403/419) so the caller can refresh + retry. */
export class GeturlAuthError extends Error {
  constructor(public status: number) {
    super(`geturl failed (HTTP ${status})`);
  }
}

/** Catch-up (VOD replay) parameters, taken from the EPG programme. begin/end are epoch MILLISECONDS. */
export interface CatchupParams {
  srno: string;
  programId: string;
  beginMs: number;
  endMs: number;
  showtime: string;
}

/**
 * Resolves a channel to a playable stream + DRM info (mirrors JioApiClient.getStreamUrl, incl. the
 * exact header set + casing from the Kodi plugin / Android app — casing matters, see http.ts).
 * @param opts.catchup present → replay a past show (stream_type=Catchup with srno/programId/begin/end/
 *   showtime, exactly like the Kodi plugin). Returns a CLEAR VOD HLS from Jio's catch-up CDN.
 */
export async function getStreamData(
  channelId: string,
  auth: AuthData,
  opts: { catchup?: CatchupParams; preferDrm?: boolean } = {}
): Promise<StreamData> {
  const enc = encodeURIComponent;
  let body: string;
  if (opts.catchup) {
    const cu = opts.catchup;
    // begin/end MUST be in milliseconds (Jio echoes them into the catch-up URL's begin= timestamp).
    body =
      `stream_type=Catchup&channel_id=${enc(channelId)}&srno=${enc(cu.srno)}` +
      `&programId=${enc(cu.programId)}&begin=${cu.beginMs}&end=${cu.endMs}&showtime=${enc(cu.showtime)}`;
  } else {
    body = `stream_type=Live&channel_id=${enc(channelId)}`;
  }

  const res = await jioRequest({
    method: "POST",
    url: "https://jiotvapi.media.jio.com/playback/apis/v1.1/geturl",
    headers: {
      Host: jio.HOST,
      Appkey: "NzNiMDhlYzQyNjJm",
      Devicetype: jio.DEVICE_TYPE,
      Os: jio.OS,
      Deviceid: auth.deviceId,
      Osversion: "13",
      Dm: "Google Pixel 5",
      Uniqueid: auth.deviceId,
      Usergroup: "tvYR7NSNn7rymo3F",
      Languageid: "6",
      Userid: auth.userId,
      Sid: "892898ba-f9de-4572-b6c2-e717b0ad",
      Crmid: auth.crmid,
      Isott: "false",
      Channel_id: channelId,
      Langid: "6",
      ssoToken: auth.ssoToken,
      Accesstoken: auth.authToken,
      Subscriberid: auth.crmid,
      analyticsId: auth.deviceId,
      Lbcookie: "1",
      Versioncode: "422",
      "user-agent": jio.USER_AGENT,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body,
  });

  if (res.status === 401 || res.status === 403 || res.status === 419) throw new GeturlAuthError(res.status);
  if (res.status < 200 || res.status >= 300) throw new Error(`geturl failed (HTTP ${res.status})`);
  const json = JSON.parse(res.text) as any;

  // Jio often returns BOTH a plain HLS (`result`) and a DRM DASH (`mpd`). Prefer the non-DRM HLS —
  // it plays in a browser with no HTTPS and no Widevine. Fall back to DRM DASH only when there's no
  // HLS. (The Android TV app keeps its own MPD-first logic; this only affects the web player/proxy.)
  const hls = (json.result ?? "").trim();
  const mpd = json.mpd;
  const hasMpd = !!(mpd && mpd.result);
  // "PayWall" = not subscribed (dead). A plain "Fallback" HLS is the real NON-DRM stream that plays
  // over HTTP without Widevine — so we must NOT reject it (that was the bug that forced DRM DASH).
  const paywall = (u: string) => /paywall/i.test(u);
  const hlsIsReal = !!hls && !paywall(hls);
  let streamUrl: string;
  let isMpd: boolean;
  let licenseUrl = "";
  if (opts.preferDrm && hasMpd) {
    // The proxy asked for DRM DASH — the non-DRM HLS "Fallback" for this channel is dead (404), so use
    // the Widevine DASH (needs HTTPS + a CDM; L1 channels only play on the TV app).
    streamUrl = mpd.result ?? ""; isMpd = true; licenseUrl = mpd.key ?? "";
  } else if (hlsIsReal) {
    // Real non-DRM HLS — plays in a browser with no HTTPS/Widevine.
    streamUrl = hls; isMpd = false;
  } else if (hasMpd) {
    // No usable HLS but a real DASH stream (usually DRM). Needs HTTPS + Widevine (L1 on TV).
    streamUrl = mpd.result ?? ""; isMpd = true; licenseUrl = mpd.key ?? "";
  } else {
    // Only a paywall/fallback HLS (or nothing) — surfaces the "not in your plan" message.
    streamUrl = hls; isMpd = false;
  }

  let cookieStr = "";
  if (streamUrl.includes("__hdnea__")) {
    cookieStr = "__hdnea__" + streamUrl.split("__hdnea__")[1];
  }

  const licenseHeaders: Record<string, string> = {
    "User-Agent": "PlayTV/1.0",
    appName: jio.APP_NAME,
    "x-platform": jio.OS,
    os: jio.OS,
    devicetype: jio.DEVICE_TYPE,
    osVersion: "13",
    srno: randomUUID(),
    channelid: channelId,
    usergroup: "tvYR7NSNn7rymo3F",
    versionCode: "422",
    "Content-Type": "application/octet-stream",
    Accept: "*/*",
    ssoToken: auth.ssoToken,
    Accesstoken: auth.authToken,
    userId: auth.userId,
    uniqueId: auth.uniqueId,
    crmid: auth.crmid,
    deviceid: auth.deviceId,
  };
  if (cookieStr) licenseHeaders["Cookie"] = cookieStr;

  const streamHeaders: Record<string, string> = {
    "User-Agent": "plaYtv/7.1.8 (Linux;Android 8.1.0) ExoPlayerLib/2.11.7",
    ssoToken: auth.ssoToken,
    userId: auth.userId,
    uniqueId: auth.uniqueId,
    crmid: auth.crmid,
    deviceid: auth.deviceId,
    devicetype: jio.DEVICE_TYPE,
    os: "B2G",
    osversion: "2.5",
    versioncode: "353",
  };
  if (cookieStr) streamHeaders["Cookie"] = cookieStr;

  return { streamUrl, licenseUrl, isMpd, streamHeaders, licenseHeaders };
}
