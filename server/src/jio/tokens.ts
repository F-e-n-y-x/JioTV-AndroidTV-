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
