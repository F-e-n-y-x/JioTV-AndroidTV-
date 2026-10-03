/** Masks the access code in a URL before it reaches the logs (`?code=ABC123` → `?code=[redacted]`). */
export function redactUrl(url: string): string {
  return url.replace(/([?&]code=)[^&#]*/gi, "$1[redacted]");
}

/**
 * Header / JSON / query keys whose values are secrets. Matched case-insensitively as `key: value`,
 * `key=value` or `"key":"value"`. Keep in sync with the app's `crash/Redact.kt`.
 */
const SECRET_KEYS = [
  "authorization", "proxy-authorization", "cookie", "set-cookie",
  "ssotoken", "accesstoken", "access_token", "authtoken", "refreshtoken", "refresh_token",
  "subscriberid", "crmid", "uniqueid", "userid", "deviceid", "lbcookie",
  "token", "code", "accesscode", "password", "otp", "mobile", "x-api-key", "appkey",
];
const KEY_RE = new RegExp(
  `(["']?\\b(?:${SECRET_KEYS.map((k) => k.replace(/-/g, "\\-")).join("|")})\\b["']?\\s*[:=]\\s*["']?)(?:Bearer\\s+)?[^\\s"',;&}\\]]+`,
  "gi"
);

/**
 * Strips tokens and other auth data from free text (crash reports, logs): Akamai `__hdnea__` tokens,
 * `Authorization` / `Cookie` / Jio credential headers and JSON fields, `Bearer` tokens, JWTs and
 * Indian mobile numbers. The app redacts before uploading; the server does it again on receipt.
 */
export function redactSecrets(text: string): string {
  return String(text ?? "")
    .replace(/(__hdnea__=)[^\s"'&;,]+/gi, "$1[redacted]")
    .replace(/(\bBearer\s+)[A-Za-z0-9._~+/=-]+/g, "$1[redacted]")
    .replace(/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}(?:\.[A-Za-z0-9_-]+)?/g, "[redacted-jwt]")
    .replace(KEY_RE, "$1[redacted]")
    .replace(/(?<![\d.])(?:\+?91[- ]?)?[6-9]\d{9}(?![\d.])/g, "[redacted-phone]");
}
