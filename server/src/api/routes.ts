import type { FastifyInstance, FastifyReply } from "fastify";
import { sendOtp, verifyOtp } from "../jio/auth";
import {
  getStoredCredentials, saveCredentials, clearCredentials,
  listCodes, addCode, deleteCode, hasCode,
} from "../store/db";
import {
  isAdminConfigured, isDashboardLocked, verifyAdminPassword, setAdminPassword, disableAuth,
} from "../store/settings";
import { generateCode, normalizeCode, CODE_MIN, CODE_MAX } from "../util/code";
import { hasCert, generateCert } from "../https";
import { config } from "../config";
import { refreshNow } from "../refresh";
import { sessions, requireAdmin, requireServerToken } from "./auth";
import { randomBytes } from "node:crypto";

function startSession(reply: FastifyReply) {
  const sid = randomBytes(24).toString("hex");
  sessions.add(sid);
  // `secure` only when the browser reached us over https (directly, or via a trusted proxy's
  // X-Forwarded-Proto) — a plain-http LAN install must still be able to sign in.
  const secure = reply.request.protocol === "https";
  reply.setCookie("admin_session", sid, { httpOnly: true, sameSite: "lax", path: "/", maxAge: 60 * 60 * 24 * 7, secure });
}

/** Per-IP limits for brute-forceable / Jio-touching endpoints (see server.ts for the plugin setup). */
const LOGIN_LIMIT = { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } };
const OTP_SEND_LIMIT = { config: { rateLimit: { max: 5, timeWindow: "1 minute" } } };
const REFRESH_LIMIT = { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } };

/** Create a starter code so a TV can connect right after setup. */
function ensureDefaultCode() {
  if (listCodes().length === 0) addCode("Default", generateCode(6), Date.now());
}

export async function registerRoutes(app: FastifyInstance): Promise<void> {
  // ── Public health (no secrets) ──
  app.get("/api/status", async () => {
    const c = getStoredCredentials();
    return { ok: true, hasCredentials: !!c };
  });

  // ── First-run setup (browser, no .env needed) ──
  // Tells the SPA whether to show the setup wizard, and whether login is required.
  app.get("/api/setup/state", async () => ({
    needsSetup: !isAdminConfigured(),
    // Drives whether the SPA shows the login gate — locked when a master key is set (or a password).
    authEnabled: isDashboardLocked(),
  }));

  // First run only. Either set a password, or choose "no password" (disableAuth) for an open LAN box.
  app.post("/api/setup", { ...LOGIN_LIMIT }, async (req, reply) => {
    if (isAdminConfigured()) return reply.code(403).send({ error: "Already set up" });
    const { password, disableAuth: noAuth } = (req.body ?? {}) as { password?: string; disableAuth?: boolean };
    if (noAuth) {
      disableAuth();
    } else {
      if (!password || password.length < 4) {
        return reply.code(400).send({ error: "Choose a password of at least 4 characters (or pick no password)" });
      }
      setAdminPassword(password);
    }
    ensureDefaultCode();
    startSession(reply);
    return { ok: true };
  });

  // Toggle auth later from the dashboard.
  app.post("/api/admin/set-password", { ...LOGIN_LIMIT, preHandler: requireAdmin }, async (req, reply) => {
    const { password } = (req.body ?? {}) as { password?: string };
    if (!password || password.length < 4) {
      return reply.code(400).send({ error: "Choose a password of at least 4 characters" });
    }
    setAdminPassword(password);
    startSession(reply); // keep the current browser signed in under the new password
    return { ok: true };
  });

  app.post("/api/admin/disable-auth", { preHandler: requireAdmin }, async () => {
    disableAuth();
    return { ok: true };
  });

  // ── Admin auth ──
  app.post("/api/admin/login", { ...LOGIN_LIMIT }, async (req, reply) => {
    const { password } = (req.body ?? {}) as { password?: string };
    if (!isAdminConfigured()) return reply.code(409).send({ error: "Not set up yet" });
    if (!password || !verifyAdminPassword(password)) {
      return reply.code(401).send({ error: "Wrong password" });
    }
    startSession(reply);
    return { ok: true };
  });

  app.post("/api/admin/logout", async (req, reply) => {
    const sid = (req.cookies as Record<string, string | undefined>)?.admin_session;
    if (sid) sessions.delete(sid);
    reply.clearCookie("admin_session", { path: "/", secure: req.protocol === "https" });
    return { ok: true };
  });

  // ── Admin: account status + setup ──
  app.get("/api/admin/status", { preHandler: requireAdmin }, async () => {
    const c = getStoredCredentials();
    return {
      loggedIn: !!c,
      mobile: c?.mobile ?? "",
      updatedAt: c?.updatedAt ?? 0,
      // Non-secret account identifiers (safe to show the account owner); tokens are never exposed.
      crmid: c?.crmid ?? "",
      userId: c?.userId ?? "",
      uniqueId: c?.uniqueId ?? "",
      deviceId: c?.deviceId ?? "",
      hasRefreshToken: !!c?.refreshToken,
    };
  });

  // ── TV access codes (named, 6–12 for new codes; a TV connects with any one of these) ──
  app.get("/api/admin/codes", { preHandler: requireAdmin }, async () => ({ codes: listCodes() }));

  app.post("/api/admin/codes", { preHandler: requireAdmin }, async (req, reply) => {
    const { name, code, length } = (req.body ?? {}) as { name?: string; code?: string; length?: number };
    let finalCode: string;
    if (code && code.trim()) {
      const norm = normalizeCode(code);
      if (!norm) return reply.code(400).send({ error: `Code must be ${CODE_MIN}–${CODE_MAX} letters/digits` });
      if (hasCode(norm)) return reply.code(409).send({ error: "That code already exists" });
      finalCode = norm;
    } else {
      do { finalCode = generateCode(length ?? 6); } while (hasCode(finalCode));
    }
    addCode((name ?? "").trim() || "TV", finalCode, Date.now());
    return { code: finalCode, name: (name ?? "").trim() || "TV" };
  });

  app.delete<{ Params: { code: string } }>(
    "/api/admin/codes/:code",
    { preHandler: requireAdmin },
    async (req) => { deleteCode(req.params.code); return { ok: true }; }
  );

  // ── HTTPS (self-signed) info + regenerate ──
  app.get("/api/admin/https", { preHandler: requireAdmin }, async () => ({
    httpsPort: config.httpsPort,
    hasCert: hasCert(),
  }));
  app.post("/api/admin/https/regenerate", { preHandler: requireAdmin }, async () => {
    generateCert();
    return { ok: true, note: "New certificate written. Restart the server to apply it." };
  });

  app.post("/api/login/otp/send", { ...OTP_SEND_LIMIT, preHandler: requireAdmin }, async (req, reply) => {
    const { mobile } = (req.body ?? {}) as { mobile?: string };
    if (!mobile || mobile.replace(/\D/g, "").length < 10) {
      return reply.code(400).send({ error: "Enter a valid 10-digit mobile number" });
    }
    try {
      await sendOtp(mobile);
      return { ok: true };
    } catch (err) {
      return reply.code(502).send({ error: (err as Error).message });
    }
  });

  app.post("/api/login/otp/verify", { ...LOGIN_LIMIT, preHandler: requireAdmin }, async (req, reply) => {
    const { mobile, otp } = (req.body ?? {}) as { mobile?: string; otp?: string };
    if (!mobile || !otp) return reply.code(400).send({ error: "Mobile and OTP are required" });
    try {
      const auth = await verifyOtp(mobile, otp);
      saveCredentials(auth, mobile, Date.now());
      return { ok: true };
    } catch (err) {
      return reply.code(502).send({ error: (err as Error).message });
    }
  });

  app.post("/api/admin/refresh", { ...REFRESH_LIMIT, preHandler: requireAdmin }, async (_req, reply) => {
    const r = await refreshNow({ force: true });
    if (!r.ok) return reply.code(400).send({ error: r.error ?? "Refresh failed" });
    return { ok: true };
  });

  app.post("/api/admin/logout-jio", { preHandler: requireAdmin }, async () => {
    clearCredentials();
    return { ok: true };
  });

  // ── Machine endpoint: TVs pull the shared credentials here (bearer token) ──
  app.get("/api/credentials", { ...LOGIN_LIMIT, preHandler: requireServerToken }, async (_req, reply) => {
    const c = getStoredCredentials();
    if (!c) return reply.code(404).send({ error: "No active login on the server yet" });
    return {
      ssoToken: c.ssoToken,
      authToken: c.authToken,
      crmid: c.crmid,
      uniqueId: c.uniqueId,
      deviceId: c.deviceId,
      userId: c.userId,
    };
  });

  // ── Machine endpoint: a TV can FORCE a token refresh and get the fresh credentials in one call.
  // Protected by the same access code as /api/credentials so a TV (which only has its code) can use it
  // from the app's "Refresh from Server" button or to self-heal when a stream 401s. This only calls Jio
  // when the stored token is actually close to expiry (see refresh.ts), so many TVs asking at once can't
  // wear out the refresh token. The stored credentials are returned either way.
  app.post("/api/refresh", { ...REFRESH_LIMIT, preHandler: requireServerToken }, async (_req, reply) => {
    const r = await refreshNow();
    const c = getStoredCredentials();
    if (!c) return reply.code(404).send({ error: "No active login on the server yet" });
    return {
      ok: r.ok,
      error: r.ok ? undefined : r.error,
      ssoToken: c.ssoToken,
      authToken: c.authToken,
      crmid: c.crmid,
      uniqueId: c.uniqueId,
      deviceId: c.deviceId,
      userId: c.userId,
    };
  });
}
