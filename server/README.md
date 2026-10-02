# JTV Server (companion)

Self-hostable **credential broker + streaming proxy + web player + M3U provider** for the JTV
Android TV app — and a full JioTV experience in any browser.

- **Log in once, everywhere** — stores your Jio login and refreshes the tokens centrally; every TV
  pulls current credentials instead of logging in separately. Re-login only ever happens on the server.
- **Watch in the browser** — a built-in web player streams channels through the server (fresh token
  injected server-side), with a channel grid, TV guide, catch-up, favourites and a language filter.
- **Use any IPTV player** — generate an **M3U playlist** (with EPG + catch-up) for VLC, TiviMate,
  OTT Navigator, Kodi, …
- **Self-host** — one Docker image (Node/TypeScript API + React web UI), `docker compose up`.

## Playback: what works where

| Stream type | Web player | External players (M3U) | Notes |
|---|---|---|---|
| **Non-DRM HLS** (most channels) | ✅ over **plain HTTP** | ✅ | Played with **hls.js** (software AES-128), so no HTTPS/Web-Crypto needed. The AES key is fetched with the license headers the key host expects. |
| **DRM DASH / Widevine** (some premium) | ✅ over **HTTPS** only | ❌ | Browser Widevine needs a secure context — use the HTTPS URL. Generic players can't decrypt Widevine. |

The web player uses **hls.js** for non-DRM HLS (works on `http://<lan-ip>`) and **Shaka Player** for
DRM DASH. Every manifest/segment/key is proxied through the server so the browser never talks to the
Jio CDN directly and never sees an expired token.

## Quick start (Docker) — no `.env` needed

```bash
cd server
docker compose up -d --build
```

Then set everything up **in the browser** (nothing to edit on disk):

1. Open `http://<host>:8080`. On first run either **set an admin password** or choose
   **“Continue without a password”** (open on your LAN). Saved to `data/config.json`.
2. **Account** tab → **Send OTP → Verify** with your Jio number (one time). All TVs pick this up.
3. **TV access codes** → add a short code per device.
4. On each TV: **Settings → Sign-in Method → Connect to JTV Proxy Server**, enter the server URL +
   a code. Add as many TVs as you like — no per-device login.

> DRM channels in the **browser** need HTTPS. The server also listens on `https://<host>:8443` with a
> self-signed cert (accept the one-time warning), or use the optional Caddy profile for a real domain:
> edit `Caddyfile`, then `docker compose --profile https up -d`.

## Upgrading: data volume permissions

The image now runs as the unprivileged **`node` user (uid/gid 1000)** instead of root. A **new**
named volume gets the right owner automatically. A volume (or bind-mounted folder) created by an
**older image** still holds root-owned files (`jtv.sqlite`, `config.json`, `tls-*.pem`), and the
server refuses to start with a message listing them. Fix it **once**, then start the new image:

```bash
docker stop jtv-server
# named volume (compose default; Portainer stacks prefix it, e.g. jtv_jtv-data — check `docker volume ls`)
docker run --rm -v jtv-data:/app/data alpine chown -R 1000:1000 /app/data
# bind mount instead of a volume
sudo chown -R 1000:1000 /path/to/data
```

In Portainer: stop the stack, run the `docker run … chown` line above from the host shell (or a
one-off `alpine` container in the Portainer UI with the volume mounted at `/app/data` and the
command `chown -R 1000:1000 /app/data`), then pull/redeploy the stack.

## Reverse proxy (Cloudflare / Caddy)

`TRUST_PROXY` (default `true`) makes the server honour `X-Forwarded-Proto/Host/For`, so playlist and
segment links come out as `https://your.domain/…` and rate limits see the real client IP (Cloudflare's
`CF-Connecting-IP` is used when present). It also accepts a hop count or a list of proxy IPs/CIDRs.
Set `TRUST_PROXY=false` if port 8080 is reachable directly from the internet with no proxy in front.

Rate limits (per client IP): admin login / setup / OTP verify / `/api/credentials` 10 per minute,
OTP send 5/min, `/api/refresh` and `/api/admin/refresh` 10/min, `/playlist.m3u` 30/min. Streaming
(`/live`, `/seg`, `/api/proxy`) is not limited. Access codes are masked (`code=[redacted]`) in logs,
and Docker logs rotate at 3 × 10 MB.

New TV access codes must be **6–12** characters; older 4–5 character codes keep working.

## Web dashboard

- **Channels** — searchable grid, category sidebar with icons, language filter, favourites (⭐, shared
  across all TVs).
- **Guide** — per-channel Now/Next across a category, **paginated (15/page)**, with a language filter.
- **Watch** — embedded 16:9 player with **quality + audio-track** menus, a programme guide, and
  **catch-up** (click a past show to replay it).
- **Account & settings** — Jio account details + token status, browser OTP login, TV access codes,
  **M3U playlist builder**, EPG source, HTTPS info, and password/no-password toggle.

## M3U playlist (external players)

Build a playlist URL under **Account → M3U playlist** with filters for **language, category, max
quality, favourites-only, EPG guide and catch-up**, then paste it into your player (or download the
`.m3u`). The same options work directly on the API:

```
http://<host>:8080/playlist.m3u?code=<accesscode>&lang=Hindi,English&group=News&quality=720&epg=1&catchup=1
```

Only **non-DRM** channels are servable to external players (DRM/Widevine can't be decrypted by
generic apps). EPG works best with an **XMLTV** source selected.

## Local dev (Node 22+)

```bash
cd server
npm install
npm run dev            # tsx watch on :8080 (+ :8443 https)
npm run typecheck      # tsc --noEmit
npm test               # unit tests (node:test via tsx)
npm --prefix web run build   # build the web UI into web/dist
npm run build && npm start
```

## API

Dashboard/web-player endpoints use the **admin session cookie** (open when auth is disabled).
Machine/player endpoints use a **TV access code** (bearer header or `?code=`).

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET  | `/api/status` | none | Health + whether a login exists |
| GET  | `/api/setup/state` | none | First-run + auth state for the SPA |
| POST | `/api/setup` | none (first run) | Set password **or** choose no-password |
| POST | `/api/admin/login` · `/logout` | password / cookie | Dashboard session |
| GET  | `/api/admin/status` | cookie | Login state, mobile, IDs, refresh status |
| POST | `/api/login/otp/send` · `/verify` | cookie | Browser Jio OTP login |
| POST | `/api/admin/refresh` · `/logout-jio` | cookie | Refresh tokens / sign out Jio |
| GET/POST/DELETE | `/api/admin/codes…` | cookie | Manage TV access codes |
| GET/POST | `/api/admin/epg` · `/epg/refresh` | cookie | EPG source (native / XMLTV) |
| GET  | `/api/channels` | cookie | Channel list (name, logo, group, language) |
| GET  | `/api/epg/:id` | cookie | Programme guide for a channel |
| GET  | `/api/play/:id` | cookie | Manifest URL + DRM flags for the player |
| GET  | `/api/proxy` | cookie | Manifest/segment/key proxy (token injected) |
| POST | `/api/play/:id/license` | cookie | Widevine license proxy |
| GET  | `/api/favorites` · `/tv/favorites` | cookie / code | Shared favourites |
| GET  | `/api/credentials` | **code** | **TVs pull the shared AuthData here** |
| GET  | `/playlist.m3u` | **code** | M3U playlist for external players |
| GET  | `/live/:id.m3u8` | **code** | Resolve + proxy a channel (HLS, quality/catch-up) |
| GET  | `/seg` | **code** | Segment/key proxy for external players |
| GET  | `/epg.xml` | **code** | XMLTV guide for external players |

## Layout

```
server/
├─ src/
│  ├─ config.ts            env + Jio constants
│  ├─ jio/                 auth · tokens · stream · epg · xmltvEpg · hdnea · channels · http · types
│  ├─ store/               db.ts (SQLite: credentials, favourites, codes) · settings.ts (config.json)
│  ├─ proxy/streamProxy.ts token injection, manifest rewrite, quality cap, license/key headers
│  ├─ refresh.ts           central token-refresh scheduler
│  ├─ api/                 routes.ts · play.ts (web player) · playlist.ts (M3U/API) · auth.ts
│  ├─ https.ts             self-signed cert (self-hosted HTTPS)
│  └─ server.ts            bootstrap (serves web/ + API on :8080 and :8443)
├─ web/                    React/Vite/Tailwind SPA (hls.js + Shaka), built to web/dist
├─ Dockerfile · docker-compose.yml · Caddyfile
```
