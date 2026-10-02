import { jio } from "../config";
import { jioRequest } from "./http";
import type { AuthData } from "./types";

/**
 * Refreshes the auth/SSO tokens. Jio's refreshtoken endpoint requires the `refreshToken` (captured at
 * login — a DIFFERENT value from authToken) in the JSON body; without it Jio returns
 * "refreshToken field is missing". Returns updated AuthData or throws.
 */
export async function refreshTokens(auth: AuthData): Promise<AuthData> {
  if (!auth.ssoToken) throw new Error("No ssoToken to refresh");
  if (!auth.refreshToken) throw new Error("No refreshToken stored — sign out and sign in again to capture it.");

  const res = await jioRequest({
    method: "POST",
    url: "https://auth.media.jio.com/tokenservice/apis/v1/refreshtoken?langId=6",
    headers: {
      ssotoken: auth.ssoToken,
      // The CURRENT access token is required as a header alongside the refreshToken body field —
      // without it Jio always answers "refresh token has expired" (verified against the live API).
      accesstoken: auth.authToken,
      appName: jio.APP_NAME,
      os: jio.OS,
      devicetype: jio.DEVICE_TYPE,
      deviceId: auth.deviceId,
      uniqueId: auth.uniqueId,
      versionCode: "422",
      "user-agent": jio.USER_AGENT,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      appName: jio.APP_NAME,
      deviceId: auth.deviceId,
      refreshToken: auth.refreshToken,
      uniqueId: auth.uniqueId,
    }),
  });

  if (res.status < 200 || res.status >= 300) {
    // Surface Jio's message (e.g. "refresh token has expired") so the UI can tell the user to re-login.
    let msg = `HTTP ${res.status}`;
    try { msg = JSON.parse(res.text)?.message ?? msg; } catch {}
    throw new Error(`Refresh failed: ${msg}`);
  }
  const json = (res.text ? JSON.parse(res.text) : {}) as Record<string, any>;

  const newAuth = json.authToken ?? "";
  const newSso = json.ssoToken ?? "";
  const newRefresh = json.refreshToken ?? "";
  if (!newAuth && !newSso) throw new Error("Refresh returned no new tokens");

  return {
    ...auth,
    authToken: newAuth || auth.authToken,
    ssoToken: newSso || auth.ssoToken,
    refreshToken: newRefresh || auth.refreshToken,
  };
}

/**
 * Rebuilds a session whose refresh token Jio has rejected ("refresh token not found"), without a new
 * OTP. The SSO token from the original sign-in has no expiry and can still be refreshed, and Jio's
 * `loginotp/exchangetoken` mints a fresh access + refresh token pair from an SSO token and the mobile
 * number (the same call the JioTV apps make after sign-in; better-jiotv-go uses it too). We ask for a
 * persistent refresh token. Only used as a fallback after a rejected refresh. Throws on failure.
 */
export async function recoverSession(auth: AuthData, mobile: string): Promise<AuthData> {
  if (!auth.ssoToken) throw new Error("No ssoToken to recover from");
  if (!mobile) throw new Error("Mobile number unknown — sign in again once so it can be saved.");

  // 1) Refresh the SSO token (works even when the refresh token is dead). Keep the old one on failure.
  let ssoToken = auth.ssoToken;
  const sso = await jioRequest({
    method: "GET",
    url: "https://tv.media.jio.com/apis/v2.0/loginotp/refresh?langId=6",
    headers: {
      devicetype: jio.DEVICE_TYPE,
      versionCode: "422",
      os: jio.OS,
      "user-agent": jio.USER_AGENT,
      ssoToken: auth.ssoToken,
      uniqueid: auth.uniqueId,
      deviceid: auth.deviceId,
    },
  });
  if (sso.status >= 200 && sso.status < 300) {
    try { ssoToken = (JSON.parse(sso.text) as Record<string, any>).ssoToken || ssoToken; } catch {}
  }

  // 2) Exchange the SSO token for a new access/refresh token pair.
  const formatted = mobile.startsWith("+91") ? mobile : `+91${mobile}`;
  const res = await jioRequest({
    method: "POST",
    url: "https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/exchangetoken",
    headers: {
      ssotoken: ssoToken,
      appname: jio.APP_NAME,
      deviceid: auth.deviceId,
      devicetype: jio.DEVICE_TYPE,
      os: jio.OS,
      subscriberid: auth.crmid,
      persistentRefreshToken: "true",
      versionCode: "422",
      "user-agent": jio.USER_AGENT,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ number: Buffer.from(formatted, "utf8").toString("base64") }),
  });
  if (res.status < 200 || res.status >= 300) {
    let msg = `HTTP ${res.status}`;
    try { msg = JSON.parse(res.text)?.message ?? msg; } catch {}
    throw new Error(`Session recovery failed: ${msg}`);
  }
  const json = (res.text ? JSON.parse(res.text) : {}) as Record<string, any>;
  if (!json.authToken) throw new Error("Session recovery returned no access token");
  return {
    ...auth,
    ssoToken,
    authToken: json.authToken,
    refreshToken: json.refreshToken || auth.refreshToken,
    userId: json.userId || auth.userId,
    crmid: json.subscriberId || auth.crmid,
  };
}
