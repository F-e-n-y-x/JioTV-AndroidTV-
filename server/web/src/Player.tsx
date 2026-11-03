import { useEffect, useMemo, useRef, useState, useCallback } from "react";
import { useParams, useNavigate } from "react-router-dom";
import shaka from "shaka-player/dist/shaka-player.compiled";
import Hls from "hls.js";
import { api, type Channel, type EpgProgram } from "./api";
import { loadChannels, cardFor, cardLanguages, feedFor, pushRecent, setPrefFeedLang, type Card } from "./channels";
import { useLangFilter } from "./lang";
import { DateTabs, daysFromPrograms, sameDay } from "./dates";

/* ── watch page (player + details + catch-up + channel list) ───────────── */
const typing = (e: KeyboardEvent) => e.ctrlKey || e.metaKey || e.altKey || /^(INPUT|SELECT|TEXTAREA)$/.test((e.target as HTMLElement)?.tagName ?? "");

export function WatchPage() {
  const { id = "" } = useParams();
  const nav = useNavigate();
  const [chs, setChs] = useState<{ all: Channel[]; cards: Card[] }>({ all: [], cards: [] });
  const [langs] = useLangFilter();
  useEffect(() => { loadChannels().then(setChs).catch(() => {}); }, []);
  const card = useMemo(() => cardFor(chs.cards, id), [chs, id]);
  const name = chs.all.find((c) => c.id === id)?.name ?? card?.name ?? `Channel ${id}`;
  useEffect(() => { if (card) pushRecent(card.id); }, [card?.id]);
  // Zapping and the side list follow the Channels page's language filter (the playing channel always stays in).
  const list = useMemo(() => chs.cards.filter((c) => c === card || langs.size === 0 || cardLanguages(c).some((l) => langs.has(l))), [chs, langs, card]);
  const open = useCallback((c: Card) => nav(`/watch/${feedFor(c)}`, { replace: true }), [nav]);

  const [programs, setPrograms] = useState<EpgProgram[]>([]);
  const [cid, setCid] = useState(id);          // live = id; catch-up = `cu.<base64>`
  const [catchTitle, setCatchTitle] = useState<string | null>(null);
  const [playingStart, setPlayingStart] = useState<number | null>(null); // null = live; else the catch-up show's startMs

  useEffect(() => { setCid(id); setCatchTitle(null); setPlayingStart(null); setPrograms([]); api.epg(id).then((r) => setPrograms(r.programs)).catch(() => setPrograms([])); }, [id]);

  const now = Date.now();
  const current = useMemo(() => programs.find((p) => p.startMs <= now && p.stopMs > now), [programs, now]);
  // Like the app: restart only a show that has run 2+ minutes, replayed up to a minute behind live.
  const canRestart = !catchTitle && current && now - current.startMs >= 120_000;

  const playCatchup = (p: EpgProgram, untilMs = p.stopMs) => {
    // Encode everything getStreamData needs for a Catchup request into the playback key.
    const payload = { c: id, s: p.srno ?? "", p: p.showId ?? "", b: p.startMs, e: untilMs, t: p.showtime ?? "" };
    const b64 = btoa(JSON.stringify(payload)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    setCid(`cu.${b64}`); setCatchTitle(p.title); setPlayingStart(p.startMs);
  };
  const goLive = () => { setCid(id); setCatchTitle(null); setPlayingStart(null); };

  // Remote-style keys: ↑/↓ or PgUp/PgDn change channel, digits jump to a channel number.
  const [digits, setDigits] = useState("");
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (typing(e) || !list.length) return;
      const step = e.key === "ArrowUp" || e.key === "PageUp" ? -1 : e.key === "ArrowDown" || e.key === "PageDown" ? 1 : 0;
      if (step) {
        e.preventDefault();
        const i = list.findIndex((c) => c === card);
        open(list[(i + step + list.length) % list.length]);
      } else if (/^[0-9]$/.test(e.key)) setDigits((d) => (d + e.key).slice(-4));
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [list, card, open]);
  useEffect(() => {
    if (!digits) return;
    const t = window.setTimeout(() => {
      const n = Number(digits); setDigits("");
      const hit = chs.cards.find((c) => c.channelNumber === n || c.variants?.some((v) => v.channel.channelNumber === n));
      if (!hit) return;
      const v = hit.variants?.find((x) => x.channel.channelNumber === n);
      nav(`/watch/${v ? v.channel.id : feedFor(hit)}`, { replace: true });
    }, 1200);
    return () => window.clearTimeout(t);
  }, [digits, chs, nav]);

  const [side, setSide] = useState<"guide" | "channels">(() => { try { return localStorage.getItem("watchSide") === "channels" ? "channels" : "guide"; } catch { return "guide"; } });
  const pickSide = (s: "guide" | "channels") => { setSide(s); try { localStorage.setItem("watchSide", s); } catch {} };

  return (
    <div className="p-4 sm:p-6 max-w-7xl mx-auto">
      <button className="btn-ghost btn-sm mb-4" onClick={() => nav("/channels")}>← Channels</button>
      <div className="grid lg:grid-cols-[1fr_360px] gap-6 items-start">
        <div className="min-w-0">
          <PlayerBox cid={cid} title={catchTitle ? `${name} · ${catchTitle}` : name} badge={digits} />
          <div className="card mt-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h3 className="truncate">{card?.channelNumber ? <span className="text-subtle tabular-nums mr-2">{card.channelNumber}</span> : null}{name}</h3>
                {catchTitle ? (
                  <p className="text-muted text-sm mt-1"><span className="badge badge-accent mr-1">CATCH-UP</span>{catchTitle}</p>
                ) : current ? (
                  <p className="text-muted text-sm mt-1"><span className="badge badge-error mr-1">LIVE</span>{current.title} · {fmt(current.startMs)}–{fmt(current.stopMs)}</p>
                ) : (
                  <p className="text-muted text-sm mt-1"><span className="badge badge-error mr-1">LIVE</span>No programme info</p>
                )}
                {current?.description && !catchTitle && <p className="text-subtle text-sm mt-2">{current.description}</p>}
              </div>
              <div className="flex gap-2 shrink-0">
                {canRestart && <button className="btn-secondary btn-sm" onClick={() => playCatchup(current!, Date.now() - 60_000)}>↺ Restart show</button>}
                {catchTitle && <button className="btn-secondary btn-sm" onClick={goLive}>Go live</button>}
              </div>
            </div>
            {card?.variants && (
              <div className="flex flex-wrap items-center gap-1.5 mt-3">
                <span className="text-subtle text-xs mr-1">Language</span>
                {card.variants.map((v) => (
                  <button key={v.channel.id} onClick={() => { setPrefFeedLang(v.lang); nav(`/watch/${v.channel.id}`, { replace: true }); }}
                    className={`badge ${v.channel.id === id ? "badge-accent" : "hover:bg-surface-hover cursor-pointer"}`}>{v.lang}</button>
                ))}
              </div>
            )}
            <p className="text-subtle text-xs mt-3 hidden md:block">Keys: ↑/↓ channel · type a number · Space pause · ←/→ 10 s · F fullscreen · M mute · P picture-in-picture</p>
          </div>
        </div>

        <aside className="min-w-0">
          <div className="flex gap-1 mb-2">
            {(["guide", "channels"] as const).map((s) => (
              <button key={s} onClick={() => pickSide(s)} className={`tab ${side === s ? "active" : ""}`}>{s === "guide" ? "Programme guide" : "Channels"}</button>
            ))}
          </div>
          {side === "guide"
            ? <ProgrammeGuide programs={programs} playingStart={playingStart} onCatchup={playCatchup} onLive={goLive} />
            : <ChannelList list={list} active={card} onPick={open} />}
        </aside>
      </div>
    </div>
  );
}

/** The channel list beside the player (tablet-style): click to zap without leaving the page. */
function ChannelList({ list, active, onPick }: { list: Card[]; active?: Card; onPick: (c: Card) => void }) {
  const activeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => { activeRef.current?.scrollIntoView({ block: "center" }); }, [active?.id]);
  return (
    <div className="card !p-0 overflow-hidden">
      <div className="max-h-[66vh] overflow-y-auto">
        {list.map((c) => {
          const on = c === active;
          return (
            <button key={c.id} ref={on ? activeRef : undefined} onClick={() => onPick(c)}
              style={on ? { background: "var(--accent-soft)" } : undefined}
              className="w-full text-left px-3 py-2 flex items-center gap-3 border-b border-border last:border-0 hover:bg-surface-hover">
              <span className={`w-10 shrink-0 text-xs tabular-nums ${on ? "text-accent font-medium" : "text-subtle"}`}>{c.channelNumber || ""}</span>
              <img src={c.logoUrl} alt="" loading="lazy" className="h-8 w-8 rounded-full object-cover bg-surface-2 shrink-0"
                onError={(e) => ((e.target as HTMLImageElement).style.visibility = "hidden")} />
              <span className="text-sm truncate">{c.name}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

function fmt(ms: number) { return new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); }

/** A scannable vertical programme guide with a day picker; past shows are catch-up. The row that's
 *  actually playing (live OR a selected catch-up) is highlighted, not just the on-air show. */
function ProgrammeGuide({ programs, playingStart, onCatchup, onLive }: { programs: EpgProgram[]; playingStart: number | null; onCatchup: (p: EpgProgram) => void; onLive: () => void }) {
  const now = Date.now();
  const [day, setDay] = useState(0);
  const days = useMemo(() => daysFromPrograms(programs), [programs]);
  // If the chosen day isn't in the data (e.g. after switching channels), fall back to the newest day.
  const activeDay = days.some((d) => d.key === day) ? day : (days[0]?.key ?? 0);
  const selDate = days.find((d) => d.key === activeDay)?.date ?? new Date();
  const dayPrograms = useMemo(() => programs.filter((p) => sameDay(p.startMs, selDate)), [programs, activeDay]);
  const playingRef = useRef<HTMLButtonElement>(null);
  useEffect(() => { playingRef.current?.scrollIntoView({ block: "center" }); }, [dayPrograms.length, activeDay, playingStart]);

  return (
    <div className="card !p-0 overflow-hidden">
      <div className="px-4 py-3 border-b border-border">
        <h3 className="text-base">Programme guide</h3>
        {days.length > 1 && <div className="mt-2 overflow-x-auto no-scrollbar"><DateTabs days={days} value={activeDay} onChange={setDay} /></div>}
      </div>
      <div className="max-h-[58vh] overflow-y-auto">
        {dayPrograms.length === 0 ? (
          <div className="empty-state text-sm">No programme info for this day.</div>
        ) : dayPrograms.map((p) => {
          const isNow = p.startMs <= now && p.stopMs > now;
          const isPast = p.stopMs <= now;
          const watchingCatchup = playingStart != null;
          // The row that's actually on screen: the selected catch-up, or (when live) the on-air show.
          const isActive = watchingCatchup ? p.startMs === playingStart : isNow;
          const dur = Math.max(1, Math.round((p.stopMs - p.startMs) / 60000));
          const prog = isNow ? Math.min(1, (now - p.startMs) / (p.stopMs - p.startMs)) : 0;
          // Jio's `isCatchupAvailable` flag is unreliable (often false when catch-up actually works),
          // so we offer catch-up for every past show — the Catchup request resolves regardless.
          const canCatchup = isPast;
          const clickable = isNow || canCatchup;
          return (
            <button key={p.startMs} ref={isActive ? playingRef : undefined} disabled={!clickable}
              onClick={() => (isNow ? onLive() : onCatchup(p))}
              style={isActive ? { background: "var(--accent-soft)" } : undefined}
              className={`w-full text-left px-4 py-3 flex gap-3 border-b border-border last:border-0 transition-colors ${clickable ? "hover:bg-surface-hover cursor-pointer" : "opacity-55 cursor-default"}`}>
              <div className={`w-14 shrink-0 text-sm tabular-nums ${isActive ? "text-accent font-medium" : "text-muted"}`}>{fmt(p.startMs)}</div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-sm truncate">{p.title}</span>
                  {watchingCatchup && isActive && <span className="badge badge-accent shrink-0">▶ Playing</span>}
                  {isNow && <span className="badge badge-accent shrink-0">ON NOW</span>}
                  {canCatchup && !isActive && <span className="badge shrink-0">Catch-up</span>}
                </div>
                <div className="text-subtle text-xs mt-0.5">{fmt(p.startMs)}–{fmt(p.stopMs)} · {dur}m</div>
                {isNow && <div className="progress mt-2"><div className="bar" style={{ width: `${prog * 100}%` }} /></div>}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
function langName(code: string): string {
  try { return new Intl.DisplayNames([navigator.language], { type: "language" }).of(code) ?? code; } catch { return code; }
}

/* ── embedded 16:9 player with custom controls ─────────────────────────── */
interface QualityOpt { label: string; height: number | "auto"; }

function PlayerBox({ cid, title, badge }: { cid: string; title: string; badge?: string }) {
  const boxRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const engineRef = useRef<{ kind: "shaka" | "hls" | "native"; inst: any } | null>(null);
  const hideTimer = useRef<number | undefined>(undefined);

  const [error, setError] = useState<string | null>(null);
  const [notEntitled, setNotEntitled] = useState(false);
  const [loading, setLoading] = useState(true);
  const [paused, setPaused] = useState(false);
  const [muted, setMuted] = useState(false);
  const [volume, setVolume] = useState(1);
  const [controls, setControls] = useState(true);
  const [menu, setMenu] = useState(false);
  const [audioLangs, setAudioLangs] = useState<string[]>([]);
  const [curLang, setCurLang] = useState("");
  const [qualities, setQualities] = useState<QualityOpt[]>([]);
  const [curQuality, setCurQuality] = useState<number | "auto">("auto");
  const [duration, setDuration] = useState(0);   // >0 only for catch-up VOD (seekable)
  const [position, setPosition] = useState(0);
  const [behind, setBehind] = useState(0);       // live: seconds behind the live edge (Jio keeps ~60 s)
  const isCatchup = cid.startsWith("cu.");

  useEffect(() => {
    let cancelled = false;
    setError(null); setNotEntitled(false); setLoading(true);
    setAudioLangs([]); setCurLang(""); setQualities([]); setCurQuality("auto");
    setDuration(0); setPosition(0);

    const proxied = (u: string) => `/api/proxy?cid=${encodeURIComponent(cid)}&u=${encodeURIComponent(u)}`;

    async function start() {
      const video = videoRef.current; if (!video) return;
      let info;
      try { info = await api.playInfo(cid); } catch (e: any) { if (!cancelled) { setError(e?.message ?? "Failed to start playback"); setLoading(false); } return; }
      if (cancelled) return;
      // Jio handed back a paywall fallback — the account isn't subscribed to this channel.
      if (!info.entitled) { setNotEntitled(true); setError("Not in your JioTV subscription"); setLoading(false); return; }

      // Non-DRM HLS → hls.js (or native HLS on Safari). This is the important path: it plays over plain
      // HTTP, whereas Shaka needs the Web Crypto API (secure-context only) and fails with error 4042
      // (NO_WEB_CRYPTO_API) on a http:// LAN address. Most JioTV channels resolve to this non-DRM HLS.
      if (!info.isMpd && !info.hasDrm) {
        // Prefer hls.js even where the browser also has native HLS, so our quality + audio-track menu
        // works consistently (native HLS gives us no track controls).
        if (Hls.isSupported()) {
          const hls = new Hls({ enableWorker: true, enableSoftwareAES: true, lowLatencyMode: false });
          engineRef.current = { kind: "hls", inst: hls };
          const syncTracks = () => {
            if (cancelled) return;
            // Quality menu from the video levels (dedupe by height, highest first).
            const heights = Array.from(new Set((hls.levels ?? []).map((l) => l.height).filter(Boolean))).sort((a, b) => b - a) as number[];
            setQualities(heights.length ? [{ label: "Auto", height: "auto" }, ...heights.map((h) => ({ label: `${h}p`, height: h }))] : []);
            // Audio menu (dedupe by language/name so a channel isn't listed twice for bitrate variants).
            const seen = new Set<string>(); const alist: string[] = [];
            for (const t of hls.audioTracks ?? []) { const l = t.lang || t.name; if (l && !seen.has(l)) { seen.add(l); alist.push(l); } }
            setAudioLangs(alist);
            const cur = (hls.audioTracks ?? [])[hls.audioTrack];
            setCurLang(cur ? cur.lang || cur.name : "");
          };
          hls.on(Hls.Events.ERROR, (_e, data) => {
            console.error("[hls.js]", data.type, data.details, data.reason ?? "", data.error ?? "", data);
            if (data.fatal && !cancelled) setError(isCatchup ? "Catch-up isn’t available for this show — Jio didn’t return a playable recording. Most other shows work." : hlsMessage(data));
          });
          hls.on(Hls.Events.MANIFEST_PARSED, () => {
            if (cancelled) return;
            setLoading(false); syncTracks();
            // Apply the user's default-quality preference (Channels page dropdown).
            const heights = Array.from(new Set((hls.levels ?? []).map((l) => l.height).filter(Boolean))) as number[];
            const target = prefTargetHeight(heights, readPrefQuality());
            if (target != null) { const i = hls.levels.findIndex((l) => l.height === target); if (i >= 0) { hls.currentLevel = i; setCurQuality(target); } }
          });
          hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, syncTracks);
          hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, syncTracks);
          hls.loadSource(proxied(info.manifestUrl));
          hls.attachMedia(video);
          return;
        }
        // Fallback for browsers with native HLS but no MSE (older Safari / iOS).
        if (video.canPlayType("application/vnd.apple.mpegurl")) {
          engineRef.current = { kind: "native", inst: null };
          video.src = proxied(info.manifestUrl);
          video.addEventListener("loadedmetadata", () => !cancelled && setLoading(false), { once: true });
          video.addEventListener("error", () => !cancelled && setError("Playback error — couldn’t load this stream."));
          return;
        }
        setError("This browser can’t play HLS."); setLoading(false); return;
      }

      // DRM DASH → Shaka (Widevine needs a secure context, i.e. the server's https:// URL).
      shaka.polyfill.installAll();
      if (!shaka.Player.isBrowserSupported()) { setError("This browser can’t play the stream (no MSE/EME)."); setLoading(false); return; }
      try {
        const player: any = new shaka.Player();
        engineRef.current = { kind: "shaka", inst: player };
        await player.attach(video);
        if (info.hasDrm) player.configure({ drm: { servers: { "com.widevine.alpha": `/api/play/${encodeURIComponent(cid)}/license` } } });
        player.getNetworkingEngine().registerRequestFilter((type: any, request: any) => {
          if (type === shaka.net.NetworkingEngine.RequestType.LICENSE) return;
          request.uris = request.uris.map((u: string) =>
            u.startsWith("/api/proxy") || !/^https?:\/\//i.test(u) ? u : proxied(u));
        });
        player.addEventListener("error", (e: any) => setError(drmMessage(e?.detail)));
        await player.load(info.manifestUrl);
        if (cancelled) return;
        setLoading(false);
        try {
          setAudioLangs(player.getAudioLanguages());
          setCurLang(player.getVariantTracks().find((t: any) => t.active)?.language ?? "");
          const heights = Array.from(new Set(player.getVariantTracks().map((t: any) => t.height).filter(Boolean))).sort((a: any, b: any) => b - a) as number[];
          setQualities([{ label: "Auto", height: "auto" }, ...heights.map((h) => ({ label: `${h}p`, height: h }))]);
          // Apply the user's default-quality preference (Channels page dropdown).
          const target = prefTargetHeight(heights, readPrefQuality());
          if (target != null) { player.configure({ abr: { enabled: false } }); const t = player.getVariantTracks().find((v: any) => v.height === target); if (t) { player.selectVariantTrack(t, true); setCurQuality(target); } }
        } catch {}
      } catch (e: any) { if (!cancelled) { setError(drmMessage(e) || e?.message || "Failed to start playback"); setLoading(false); } }
    }
    start();
    return () => {
      cancelled = true;
      const eng = engineRef.current;
      if (eng?.kind === "shaka" || eng?.kind === "hls") eng.inst?.destroy();
      else if (eng?.kind === "native" && videoRef.current) { videoRef.current.removeAttribute("src"); videoRef.current.load(); }
      engineRef.current = null;
    };
  }, [cid]);

  useEffect(() => {
    const v = videoRef.current; if (!v) return;
    const onPlay = () => setPaused(false), onPause = () => setPaused(true);
    const onVol = () => { setMuted(v.muted); setVolume(v.volume); };
    // Only expose a finite duration for VOD (catch-up); live streams report Infinity.
    const onDur = () => setDuration(Number.isFinite(v.duration) ? v.duration : 0);
    const onTime = () => {
      setPosition(v.currentTime);
      const s = v.seekable;
      setBehind(s.length && !Number.isFinite(v.duration) ? Math.max(0, s.end(s.length - 1) - v.currentTime) : 0);
    };
    v.addEventListener("play", onPlay); v.addEventListener("pause", onPause); v.addEventListener("volumechange", onVol);
    v.addEventListener("durationchange", onDur); v.addEventListener("timeupdate", onTime);
    return () => {
      v.removeEventListener("play", onPlay); v.removeEventListener("pause", onPause); v.removeEventListener("volumechange", onVol);
      v.removeEventListener("durationchange", onDur); v.removeEventListener("timeupdate", onTime);
    };
  }, []);

  const nudge = useCallback(() => {
    setControls(true); window.clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => { setControls(false); setMenu(false); }, 3000);
  }, []);
  useEffect(() => () => window.clearTimeout(hideTimer.current), []);

  const togglePlay = () => { const v = videoRef.current; if (!v) return; v.paused ? v.play() : v.pause(); nudge(); };
  const onVolInput = (val: number) => { const v = videoRef.current; if (!v) return; v.volume = val; v.muted = val === 0; };
  const toggleMute = () => { const v = videoRef.current; if (v) v.muted = !v.muted; nudge(); };
  const togglePip = () => { const v = videoRef.current; if (!v) return; document.pictureInPictureElement ? document.exitPictureInPicture() : v.requestPictureInPicture?.().catch(() => {}); };
  // ←/→: 10 s inside what the stream keeps (the whole show for catch-up, ~60 s for live).
  const seekBy = (d: number) => {
    const v = videoRef.current; if (!v || !v.seekable.length) return;
    v.currentTime = Math.min(v.seekable.end(v.seekable.length - 1), Math.max(v.seekable.start(0), v.currentTime + d)); nudge();
  };
  const goLiveEdge = () => { const v = videoRef.current; if (v?.seekable.length) { v.currentTime = v.seekable.end(v.seekable.length - 1) - 3; v.play().catch(() => {}); } };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // Space on a focused button presses that button instead.
      if (typing(e) || (e.key === " " && (e.target as HTMLElement)?.tagName === "BUTTON")) return;
      const k = e.key.toLowerCase();
      const act: Record<string, () => void> = { " ": togglePlay, k: togglePlay, f: toggleFull, m: toggleMute, p: togglePip, arrowleft: () => seekBy(-10), arrowright: () => seekBy(10) };
      if (act[k]) { e.preventDefault(); act[k](); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });
  const toggleFull = () => { const el = boxRef.current; if (!el) return; document.fullscreenElement ? document.exitFullscreen() : el.requestFullscreen?.(); };
  const pickLang = (l: string) => {
    const eng = engineRef.current; if (!eng) return;
    if (eng.kind === "shaka") eng.inst.selectAudioLanguage(l);
    else if (eng.kind === "hls") { const i = (eng.inst.audioTracks ?? []).findIndex((t: any) => (t.lang || t.name) === l); if (i >= 0) eng.inst.audioTrack = i; }
    setCurLang(l);
  };
  const pickQuality = (q: QualityOpt) => {
    const eng = engineRef.current; if (!eng) return;
    if (eng.kind === "shaka") {
      if (q.height === "auto") eng.inst.configure({ abr: { enabled: true } });
      else { eng.inst.configure({ abr: { enabled: false } }); const t = eng.inst.getVariantTracks().find((v: any) => v.height === q.height); if (t) eng.inst.selectVariantTrack(t, true); }
    } else if (eng.kind === "hls") {
      if (q.height === "auto") eng.inst.currentLevel = -1;
      else { const i = eng.inst.levels.findIndex((l: any) => l.height === q.height); if (i >= 0) eng.inst.currentLevel = i; }
    }
    setCurQuality(q.height); setMenu(false);
  };

  return (
    <div ref={boxRef} className="player-box relative w-full aspect-video bg-black rounded-lg overflow-hidden"
      onMouseMove={nudge} onMouseLeave={() => setControls(false)} style={{ cursor: controls ? "default" : "none" }}>
      <video ref={videoRef} className="absolute inset-0 h-full w-full" autoPlay playsInline onClick={togglePlay} />

      {/* title (top) */}
      <div className={`absolute inset-x-0 top-0 p-3 bg-gradient-to-b from-black/70 to-transparent text-white text-sm font-medium truncate transition-opacity ${controls ? "opacity-100" : "opacity-0"}`}>{title}</div>

      {badge && <div className="absolute top-3 right-3 px-3 py-1 rounded-md bg-black/70 text-white text-2xl font-semibold tabular-nums">{badge}<span className="animate-pulse">_</span></div>}

      {/* center state */}
      <div className="absolute inset-0 grid place-items-center pointer-events-none">
        {loading && !error && <div className="h-9 w-9 rounded-full border-2 border-white/30 border-t-white animate-spin" />}
        {error && (
          <div className="card bg-surface max-w-sm mx-3 text-center pointer-events-auto">
            <div className={`font-semibold text-sm ${notEntitled ? "text-warning" : "text-error"}`}>{error}</div>
            {(() => {
              // Suppress the generic hint when the message already explains itself (unavailable / blocked
              // / catch-up / needs-https / not-in-plan), so we don't wrongly blame DRM/L1.
              if (notEntitled)
                return <p className="text-muted text-xs mt-2">Jio returned a paywall fallback for this channel — your account doesn’t have the pack that includes it. Free/subscribed channels play fine.</p>;
              if (/unavailable|blocked|not in your|isn’t (in|available)|catch-up|secure connection|https:\/\/|404|plan/i.test(error ?? ""))
                return null; // message is self-explanatory
              if (location.protocol !== "https:" && location.hostname !== "localhost")
                return <p className="text-muted text-xs mt-2">For DRM channels, open the server’s https:// URL (Account → HTTPS).</p>;
              return <p className="text-muted text-xs mt-2">Couldn’t play this stream. If it’s a DRM channel, open the server’s https:// URL; otherwise the channel may be temporarily down on Jio’s side.</p>;
            })()}
          </div>
        )}
      </div>

      {/* controls (bottom) */}
      <div className={`absolute inset-x-0 bottom-0 flex flex-col gap-1.5 p-3 bg-gradient-to-t from-black/80 to-transparent transition-opacity ${controls ? "opacity-100" : "opacity-0 pointer-events-none"}`}>
        {/* Seek bar — catch-up only (live HLS reports a short DVR window we must NOT expose as a scrubber). */}
        {isCatchup && duration > 0 && (
          <div className="flex items-center gap-2 text-white text-[11px] tabular-nums">
            <span>{fmtDur(position)}</span>
            <input type="range" min={0} max={Math.max(1, duration)} step={1} value={Math.min(position, duration)}
              onChange={(e) => { const v = videoRef.current; const t = Number(e.target.value); if (v) { v.currentTime = t; setPosition(t); } }}
              className="flex-1 accent-white" title="Seek" />
            <span>{fmtDur(duration)}</span>
          </div>
        )}
        <div className="flex items-center gap-2">
        <button className="text-white w-10 h-10 md:w-8 md:h-8 grid place-items-center" onClick={togglePlay} title={paused ? "Play" : "Pause"}>
          {paused ? <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
                  : <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h4v14H6zM14 5h4v14h-4z" /></svg>}
        </button>
        <button className="text-white w-10 h-10 md:w-8 md:h-8 grid place-items-center" onClick={toggleMute} title="Mute (M)">
          {muted || volume === 0 ? <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3L19 9.5 17.5 8 15 10.5 12.5 8 11 9.5 13.5 12 11 14.5 12.5 16 15 13.5 17.5 16 19 14.5z" /></svg>
                               : <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M3 9v6h4l5 5V4L7 9H3zm13 3a4 4 0 0 0-2-3.5v7A4 4 0 0 0 16 12z" /></svg>}
        </button>
        <input type="range" min={0} max={1} step={0.05} value={muted ? 0 : volume} onChange={(e) => onVolInput(Number(e.target.value))} className="w-20 accent-white" title="Volume" />
        {!isCatchup && !loading && !error && (
          <button onClick={goLiveEdge} title="Back to live" disabled={behind < 8}
            className={`ml-1 px-2 h-6 rounded text-[11px] font-semibold tracking-wide ${behind < 8 ? "text-white cursor-default" : "bg-white/20 text-white hover:bg-white/30"}`}>
            <span className={`inline-block w-1.5 h-1.5 rounded-full mr-1 align-middle ${behind < 8 ? "bg-red-500" : "bg-white/60"}`} />
            {behind < 8 ? "LIVE" : `−${Math.round(behind)}s · Go live`}
          </button>
        )}
        <div className="ml-auto flex items-center gap-1">
          {(audioLangs.length > 1 || qualities.length > 1) && (
            <div className="relative">
              <button className="text-white w-10 h-10 md:w-8 md:h-8 grid place-items-center" onClick={() => setMenu((m) => !m)} title="Audio & quality">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.9l-.1-.1A2 2 0 1 1 6.9 4l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1A2 2 0 1 1 20.9 6l-.1.1a1.7 1.7 0 0 0-.3 1.9V8a1.7 1.7 0 0 0 1.5 1H22a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></svg>
              </button>
              {menu && (
                <div className="absolute bottom-10 right-0 w-48 card !p-2 bg-surface shadow-lg">
                  {audioLangs.length > 1 && (<div className="mb-2"><div className="text-subtle text-xs px-2 py-1">Audio</div>
                    {audioLangs.map((l) => <button key={l} onClick={() => pickLang(l)} className={`block w-full text-left px-2 py-1.5 rounded-md text-sm ${curLang === l ? "text-accent" : "hover:bg-surface-hover"}`}>{langName(l)}</button>)}</div>)}
                  {qualities.length > 1 && (<div><div className="text-subtle text-xs px-2 py-1">Quality</div>
                    {qualities.map((q) => <button key={String(q.height)} onClick={() => pickQuality(q)} className={`block w-full text-left px-2 py-1.5 rounded-md text-sm ${curQuality === q.height ? "text-accent" : "hover:bg-surface-hover"}`}>{q.label}</button>)}</div>)}
                </div>
              )}
            </div>
          )}
          {document.pictureInPictureEnabled && (
            <button className="text-white w-10 h-10 md:w-8 md:h-8 grid place-items-center" onClick={togglePip} title="Picture-in-picture (P)">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="2" y="4" width="20" height="16" rx="2" /><rect x="12" y="11" width="8" height="6" rx="1" fill="currentColor" /></svg>
            </button>
          )}
          <button className="text-white w-10 h-10 md:w-8 md:h-8 grid place-items-center" onClick={toggleFull} title="Fullscreen (F)">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3" /></svg>
          </button>
        </div>
        </div>
      </div>
    </div>
  );
}

function fmtDur(s: number): string {
  const t = Math.max(0, Math.floor(s));
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), sec = t % 60;
  const mm = String(m).padStart(2, "0"), ss = String(sec).padStart(2, "0");
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`;
}

function readPrefQuality(): string { try { return localStorage.getItem("prefQuality") || "auto"; } catch { return "auto"; } }
/** Maps a High/Medium/Low preference to a concrete height from the available ladder (null = Auto). */
function prefTargetHeight(heights: number[], pref: string): number | null {
  if (pref === "auto" || !heights.length) return null;
  const asc = [...heights].sort((a, b) => a - b);
  if (pref === "low") return asc[0];
  if (pref === "high") return asc[asc.length - 1];
  return asc[Math.floor((asc.length - 1) / 2)]; // mid
}

function drmMessage(detail: any): string {
  const code = detail?.code ?? detail?.detail?.code;
  // 1001 = BAD_HTTP_STATUS (the stream/CDN returned an error — usually not in your plan).
  if (code === 1001) return "Stream unavailable — the CDN returned an error (often the channel isn’t in your plan).";
  // 6001/6006/6007/6008/6012 = DRM key/license/CDM issues.
  if (code && [6001, 6006, 6007, 6008, 6012].includes(code)) return "DRM error — this channel likely needs hardware DRM (L1), which desktop browsers can’t decrypt. It plays on the TV app.";
  // 4042 = NO_WEB_CRYPTO_API — Shaka can't run over insecure HTTP. Point the user at the HTTPS URL.
  if (code === 4042) return "This DRM channel needs a secure connection — open the server’s https:// URL (Account → HTTPS).";
  return code ? `Playback error ${code}` : "";
}

function hlsMessage(data: any): string {
  // Non-DRM channels: a fatal network error almost always means the CDN rejected the token or the
  // channel isn't in the account's plan (a genuine media error is rare for these clear streams).
  if (data?.type === "networkError") return "Stream unavailable — the CDN returned an error (often the channel isn’t in your plan).";
  return "Playback error — couldn’t load this stream.";
}
