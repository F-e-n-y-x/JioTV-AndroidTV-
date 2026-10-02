import { recoverSession, refreshTokens } from "./jio/tokens";
import { getStoredCredentials, updateTokens } from "./store/db";

/**
 * Central token-refresh scheduler. Jio's access token (authToken) lives 12 h; the server refreshes it
 * shortly BEFORE it expires so every TV that pulls `/api/credentials` always gets a valid token.
 *
 * Refreshes are deliberately rare and never overlap. Jio's refresh token is fragile: refreshing on
 * every TV request / channel error (several at once) eventually leaves it "refresh token not found",
 * and from then on only a new OTP sign-in helps (issue #3). So:
 *  - one refresh at a time (single-flight), shared by the scheduler, TVs and the proxy;
 *  - non-forced requests return early while the token still has > REFRESH_LEAD_MS left;
 *  - after Jio REJECTS a refresh, further attempts are paused until the next sign-in.
 *
 * The short-lived per-stream `__hdnea__` token is refreshed per-playback by the proxy, not here.
 */
const CHECK_INTERVAL_MS = 30 * 60 * 1000; // look every 30 min
const REFRESH_LEAD_MS = 2 * 60 * 60 * 1000; // refresh when < 2 h of the 12 h token remain
const FALLBACK_MAX_AGE_MS = 6 * 60 * 60 * 1000; // token without a readable exp: refresh every 6 h

let timer: NodeJS.Timeout | null = null;
let inflight: Promise<{ ok: boolean; error?: string }> | null = null;
/** Jio rejected the refresh token; stop retrying until credentials change (a new sign-in). */
let rejectedForAuthToken: string | null = null;

/** `exp` (ms) of a JWT, or 0 when it can't be read. */
export function jwtExpiryMs(token: string): number {
  try {
    const part = token.split(".")[1] ?? "";
    const json = JSON.parse(Buffer.from(part, "base64url").toString("utf8"));
    return typeof json.exp === "number" ? json.exp * 1000 : 0;
  } catch {
    return 0;
  }
}

function needsRefresh(authToken: string, updatedAt: number, now: number): boolean {
  const exp = jwtExpiryMs(authToken);
  if (exp > 0) return exp - now < REFRESH_LEAD_MS;
  return now - updatedAt > FALLBACK_MAX_AGE_MS;
}

/**
 * Refreshes the stored tokens if needed. `force` skips the "still fresh" check (admin button, or the
 * proxy after Jio answered 401/419), but never the single-flight lock or the rejected-token pause.
 */
export function refreshNow(opts: { force?: boolean } = {}): Promise<{ ok: boolean; error?: string }> {
  if (inflight) return inflight;
  inflight = (async () => {
    const stored = getStoredCredentials();
    if (!stored) return { ok: false, error: "No credentials — sign in to Jio first." };
    if (rejectedForAuthToken && rejectedForAuthToken === stored.authToken) {
      return { ok: false, error: "Jio rejected the saved sign-in. Sign in again on the Account page." };
    }
    if (!opts.force && !needsRefresh(stored.authToken, stored.updatedAt, Date.now())) {
      return { ok: true };
    }
    try {
      const updated = await refreshTokens(stored);
      updateTokens(updated, Date.now());
      console.log("[refresh] tokens refreshed");
      return { ok: true };
    } catch (err) {
      const error = (err as Error).message;
      console.warn("[refresh] failed:", error);
      // A definite "no" from Jio (vs. a network blip): the refresh token is gone. Try to rebuild the
      // session from the SSO token once; if that fails too, stop asking Jio until the next sign-in.
      if (/refresh token|expired|not found|HTTP 4\d\d/i.test(error)) {
        try {
          const recovered = await recoverSession(stored, stored.mobile);
          updateTokens(recovered, Date.now());
          console.log("[refresh] session recovered without a new sign-in");
          return { ok: true };
        } catch (e2) {
          console.warn("[refresh] recovery failed:", (e2 as Error).message);
          rejectedForAuthToken = stored.authToken;
        }
      }
      return { ok: false, error };
    }
  })().finally(() => {
    inflight = null;
  });
  return inflight;
}

export function startRefreshScheduler(): void {
  if (timer) return;
  // Check once shortly after boot, then every 30 min; it only calls Jio when the token is close to expiry.
  setTimeout(() => void refreshNow(), 30_000);
  timer = setInterval(() => void refreshNow(), CHECK_INTERVAL_MS);
}

export function stopRefreshScheduler(): void {
  if (timer) clearInterval(timer);
  timer = null;
}
