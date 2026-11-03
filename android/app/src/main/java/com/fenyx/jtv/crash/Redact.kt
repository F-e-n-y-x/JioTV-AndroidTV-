package com.fenyx.jtv.crash

/**
 * Strips tokens and other auth data from problem-report text before it is stored, shared or uploaded:
 * Akamai `__hdnea__` tokens, `Authorization` / `Cookie` / Jio credential headers and JSON fields,
 * `Bearer` tokens, JWTs, access codes and Indian mobile numbers.
 *
 * Mirrors `redactSecrets` in `server/src/util/redact.ts` (the server redacts again on receipt).
 */
object Redact {

    private val SECRET_KEYS = listOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie",
        "ssotoken", "accesstoken", "access_token", "authtoken", "refreshtoken", "refresh_token",
        "subscriberid", "crmid", "uniqueid", "userid", "deviceid", "lbcookie",
        "token", "code", "accesscode", "password", "otp", "mobile", "x-api-key", "appkey",
    )

    private val HDNEA = Regex("(__hdnea__=)[^\\s\"'&;,]+", RegexOption.IGNORE_CASE)
    private val BEARER = Regex("(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]+)?")
    private val KEYS = Regex(
        "([\"']?\\b(?:" + SECRET_KEYS.joinToString("|") +
            ")\\b[\"']?\\s*[:=]\\s*[\"']?)(?:Bearer\\s+)?[^\\s\"',;&\\}\\]]+",
        RegexOption.IGNORE_CASE
    )
    private val PHONE = Regex("(?<![\\d.])(?:\\+?91[- ]?)?[6-9]\\d{9}(?![\\d.])")

    fun secrets(text: String): String = text
        .replace(HDNEA, "$1[redacted]")
        .replace(BEARER, "$1[redacted]")
        .replace(JWT, "[redacted-jwt]")
        .replace(KEYS, "$1[redacted]")
        .replace(PHONE, "[redacted-phone]")
}
