import https from "node:https";

/**
 * Case-preserving HTTPS request for the Jio APIs.
 *
 * WHY NOT fetch(): undici/`fetch` lowercases every outgoing header name. Jio's `geturl` and
 * `refreshtoken` endpoints are case-sensitive about auth headers (`ssoToken`, `Accesstoken`, `Crmid`,
 * …) — exactly as the Android app sends them via HttpURLConnection. Lowercased headers get a 403.
 * Node's core `https` preserves the header-name case you pass, matching the app byte-for-byte.
 *
 * We also deliberately send no Accept-Encoding, so responses come back uncompressed (Jio returns
 * identity), avoiding manual gunzip.
 */
export const JIO_REQUEST_TIMEOUT_MS = 15_000;

export function jioRequest(opts: {
  method: string;
  url: string;
  headers: Record<string, string>;
  body?: string;
  timeoutMs?: number;
}): Promise<{ status: number; text: string }> {
  const u = new URL(opts.url);
  const timeoutMs = opts.timeoutMs ?? JIO_REQUEST_TIMEOUT_MS;
  return new Promise((resolve, reject) => {
    let deadline: NodeJS.Timeout | undefined;
    const fail = (err: Error) => {
      clearTimeout(deadline);
      reject(err);
    };
    const timedOut = () => new Error(`Jio request timed out after ${Math.round(timeoutMs / 1000)}s (${u.hostname}${u.pathname})`);
    const req = https.request(
      {
        hostname: u.hostname,
        port: 443,
        path: u.pathname + u.search,
        method: opts.method,
        headers: opts.headers,
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (c) => chunks.push(c));
        res.on("error", fail);
        res.on("aborted", () => fail(new Error(`Jio response aborted (${u.hostname}${u.pathname})`)));
        res.on("end", () => {
          clearTimeout(deadline);
          resolve({ status: res.statusCode ?? 0, text: Buffer.concat(chunks).toString("utf8") });
        });
      }
    );
    req.on("error", fail);
    // No answer (or a stalled body) within the timeout: abort, so callers — incl. the single-flight
    // token refresh — always settle instead of hanging forever on a dead Jio edge. setTimeout covers an
    // idle socket; the hard deadline also covers a response that trickles in forever.
    req.setTimeout(timeoutMs, () => req.destroy(timedOut()));
    deadline = setTimeout(() => {
      const err = timedOut();
      req.destroy(err);
      fail(err);
    }, timeoutMs);
    if (opts.body) req.write(opts.body);
    req.end();
  });
}
