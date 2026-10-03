// Thin API client for the JTV server. All requests are same-origin and rely on the admin session cookie.

export interface Channel {
  id: string;
  name: string;
  logoUrl: string;
  group: string;
  language: string;
  isDrm: boolean;
  channelNumber: number;
}

export interface PlaybackInfo {
  channelId: string;
  isMpd: boolean;
  manifestUrl: string;
  hasDrm: boolean;
  entitled: boolean;
}

export interface EpgProgram {
  title: string;
  description: string;
  startMs: number;
  stopMs: number;
  srno?: string;
  showId?: string;
  showtime?: string;
  catchup?: boolean;
}

export interface AccessCode {
  code: string;
  name: string;
  createdAt: number;
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  // Only advertise a JSON content-type when we actually send a body. A bodyless request (e.g. DELETE)
  // with `Content-Type: application/json` makes Fastify reject the empty body with a 400 — which is why
  // deleting access codes from the dashboard failed while curl (no content-type) worked.
  const headers: Record<string, string> = { ...((init?.headers as Record<string, string>) ?? {}) };
  if (init?.body != null) headers["Content-Type"] = "application/json";
  const res = await fetch(path, { ...init, headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((data as any).error ?? `HTTP ${res.status}`);
  return data as T;
}

/** Crash / ANR report uploaded by a TV app (already redacted). */
export interface ProblemReport {
  id: string; kind: string; at: number; receivedAt: number;
  appVersion: string; device: string; android: string; text: string;
}

export const api = {
  status: () => req<{ ok: boolean; hasCredentials: boolean }>("/api/status"),
  setupState: () => req<{ needsSetup: boolean; authEnabled: boolean }>("/api/setup/state"),
  setup: (body: { password?: string; disableAuth?: boolean }) =>
    req<{ ok: boolean }>("/api/setup", { method: "POST", body: JSON.stringify(body) }),
  setPassword: (password: string) => req("/api/admin/set-password", { method: "POST", body: JSON.stringify({ password }) }),
  disableAuth: () => req("/api/admin/disable-auth", { method: "POST", body: "{}" }),
  codes: () => req<{ codes: AccessCode[] }>("/api/admin/codes"),
  addCode: (body: { name: string; code?: string; length?: number }) =>
    req<{ code: string; name: string }>("/api/admin/codes", { method: "POST", body: JSON.stringify(body) }),
  deleteCode: (code: string) => req(`/api/admin/codes/${encodeURIComponent(code)}`, { method: "DELETE" }),
  reports: () => req<{ reports: ProblemReport[] }>("/api/admin/reports"),
  deleteReport: (id: string) => req(`/api/admin/reports/${encodeURIComponent(id)}`, { method: "DELETE" }),
  https: () => req<{ httpsPort: number; hasCert: boolean }>("/api/admin/https"),
  regenerateHttps: () => req<{ ok: boolean; note: string }>("/api/admin/https/regenerate", { method: "POST", body: "{}" }),
  epgConfig: () => req<{ mode: "native" | "xmltv"; url: string; status: string; lastSync: number; channels: number }>("/api/admin/epg"),
  setEpgConfig: (mode: "native" | "xmltv", url: string) => req("/api/admin/epg", { method: "POST", body: JSON.stringify({ mode, url }) }),
  refreshEpg: () => req<{ ok: boolean; status: string; channels: number }>("/api/admin/epg/refresh", { method: "POST", body: "{}" }),
  adminStatus: () => req<{ loggedIn: boolean; mobile: string; updatedAt: number; crmid: string; userId: string; uniqueId: string; deviceId: string; hasRefreshToken: boolean }>("/api/admin/status"),
  login: (password: string) => req("/api/admin/login", { method: "POST", body: JSON.stringify({ password }) }),
  logout: () => req("/api/admin/logout", { method: "POST", body: "{}" }),
  sendOtp: (mobile: string) => req("/api/login/otp/send", { method: "POST", body: JSON.stringify({ mobile }) }),
  verifyOtp: (mobile: string, otp: string) =>
    req("/api/login/otp/verify", { method: "POST", body: JSON.stringify({ mobile, otp }) }),
  refresh: () => req("/api/admin/refresh", { method: "POST", body: "{}" }),
  logoutJio: () => req("/api/admin/logout-jio", { method: "POST", body: "{}" }),
  channels: () => req<{ channels: Channel[] }>("/api/channels"),
  playInfo: (id: string) => req<PlaybackInfo>(`/api/play/${encodeURIComponent(id)}`),
  epg: (id: string) => req<{ programs: EpgProgram[] }>(`/api/epg/${encodeURIComponent(id)}`),
  favorites: () => req<{ ids: string[] }>("/api/favorites"),
  toggleFavorite: (id: string) =>
    req<{ favorited: boolean }>(`/api/favorites/${encodeURIComponent(id)}/toggle`, { method: "POST", body: "{}" }),
};
