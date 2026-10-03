import { useEffect, useMemo, useState } from "react";
import { Routes, Route, Navigate, NavLink, Outlet, useNavigate } from "react-router-dom";
import { api, type AccessCode, type Channel, type ProblemReport } from "./api";
import { WatchPage } from "./Player";
import { GuidePage } from "./Guide";
import {
  IconTv, IconUser, IconLogOut, IconSun, IconMoon, IconStar, IconSearch,
  IconCopy, IconPlus, IconTrash, IconRefresh, IconCheck, IconGuide, IconDownload, IconList, categoryIcon,
} from "./Icons";
import { useLangFilter, LanguageMenu, usePrefQuality, QualitySelect, CategoryMenu } from "./lang";

/* ── theme ─────────────────────────────────────────────────────────────── */
function useTheme() {
  const [theme, setTheme] = useState<"dark" | "light">(
    (document.documentElement.getAttribute("data-theme") as "dark" | "light") || "dark"
  );
  const toggle = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    try { localStorage.setItem("theme", next); } catch {}
    setTheme(next);
  };
  return { theme, toggle };
}

/* ── root ──────────────────────────────────────────────────────────────── */
export default function App() {
  const [authed, setAuthed] = useState<boolean | null>(null);
  const [state, setState] = useState<{ needsSetup: boolean; authEnabled: boolean } | null>(null);

  useEffect(() => {
    api.setupState().then(setState).catch(() => setState({ needsSetup: false, authEnabled: false }));
    api.adminStatus().then(() => setAuthed(true)).catch(() => setAuthed(false));
  }, []);

  if (authed === null || state === null)
    return <div className="grid place-items-center h-full text-muted">Loading…</div>;
  if (state.needsSetup && !authed)
    return <SetupWizard onDone={() => { setState({ needsSetup: false, authEnabled: true }); setAuthed(true); }} />;
  if (state.authEnabled && !authed) return <Login onLogin={() => setAuthed(true)} />;

  return (
    <Routes>
      <Route element={<Layout onLogout={() => setAuthed(false)} />}>
        <Route index element={<Navigate to="/channels" replace />} />
        <Route path="channels" element={<Channels />} />
        <Route path="guide" element={<GuidePage />} />
        <Route path="watch/:id" element={<WatchPage />} />
        <Route path="account" element={<Account />} />
        <Route path="*" element={<Navigate to="/channels" replace />} />
      </Route>
    </Routes>
  );
}

/* ── auth screens ──────────────────────────────────────────────────────── */
function Centered({ children }: { children: React.ReactNode }) {
  return <div className="grid place-items-center h-full p-6"><div className="card w-full max-w-sm">{children}</div></div>;
}

function SetupWizard({ onDone }: { onDone: () => void }) {
  const [pw, setPw] = useState(""); const [pw2, setPw2] = useState("");
  const [err, setErr] = useState(""); const [busy, setBusy] = useState(false);
  const go = async (fn: () => Promise<unknown>) => { setErr(""); setBusy(true); try { await fn(); onDone(); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  const create = () => pw.length < 4 ? setErr("Use at least 4 characters.") : pw !== pw2 ? setErr("Passwords don’t match.") : go(() => api.setup({ password: pw }));
  return (
    <Centered>
      <h1>Welcome to JTV</h1>
      <p className="text-muted text-sm mt-1 mb-5">First run — set an admin password (saved on the server, no <code>.env</code>).</p>
      <label className="text-sm text-muted">Admin password</label>
      <input className="input mt-1.5" type="password" value={pw} onChange={(e) => setPw(e.target.value)} />
      <label className="text-sm text-muted mt-3 block">Confirm password</label>
      <input className="input mt-1.5" type="password" value={pw2} onChange={(e) => setPw2(e.target.value)} onKeyDown={(e) => e.key === "Enter" && create()} />
      <button className="btn-primary w-full mt-4" disabled={busy} onClick={create}>Create &amp; continue</button>
      <button className="btn-secondary w-full mt-2" disabled={busy} onClick={() => go(() => api.setup({ disableAuth: true }))}>Continue without a password</button>
      <p className="text-subtle text-xs mt-2">No password = open dashboard on your network. Fine for a home LAN; don’t expose it to the internet without one.</p>
      {err && <p className="text-error text-sm mt-3">{err}</p>}
    </Centered>
  );
}

function Login({ onLogin }: { onLogin: () => void }) {
  const [pw, setPw] = useState(""); const [err, setErr] = useState(""); const [busy, setBusy] = useState(false);
  const submit = async () => { setErr(""); setBusy(true); try { await api.login(pw); onLogin(); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  return (
    <Centered>
      <h1>JTV Server</h1>
      <p className="text-muted text-sm mt-1 mb-5">Sign in to the dashboard.</p>
      <label className="text-sm text-muted">Admin password or master key</label>
      <input className="input mt-1.5" type="password" value={pw} onChange={(e) => setPw(e.target.value)} onKeyDown={(e) => e.key === "Enter" && submit()} />
      <button className="btn-primary w-full mt-4" disabled={busy} onClick={submit}>{busy ? "Signing in…" : "Sign in"}</button>
      {err && <p className="text-error text-sm mt-3">{err}</p>}
    </Centered>
  );
}

/* ── layout (header + nav) ─────────────────────────────────────────────── */
function Layout({ onLogout }: { onLogout: () => void }) {
  const { theme, toggle } = useTheme();
  const logout = async () => { try { await api.logout(); } catch {} onLogout(); };
  const link = ({ isActive }: { isActive: boolean }) => `tab ${isActive ? "active" : ""}`;
  return (
    <div className="h-full flex flex-col">
      <header className="flex items-center gap-2 sm:gap-4 px-3 sm:px-5 h-14 border-b border-border shrink-0">
        <div className="flex items-center gap-2 shrink-0">
          <span className="grid place-items-center w-8 h-8 rounded-lg text-white shadow-sm"
            style={{ background: "linear-gradient(135deg, var(--accent-strong), var(--accent))" }}><IconTv size={18} /></span>
          <span className="font-semibold tracking-tight">JTV<span className="text-muted font-normal hidden sm:inline"> Server</span></span>
        </div>
        {/* On phones the nav sits on the right (theme/logout are hidden); on desktop it follows the logo. */}
        <nav className="min-w-0 overflow-x-auto no-scrollbar ml-auto md:ml-2">
          <div className="tabs">
            <NavLink to="/channels" className={link}><IconTv size={15} /> <span className="hidden sm:inline">Channels</span></NavLink>
            <NavLink to="/guide" className={link}><IconGuide size={15} /> <span className="hidden sm:inline">Guide</span></NavLink>
            <NavLink to="/account" className={link}><IconUser size={15} /> <span className="hidden sm:inline">Account</span></NavLink>
          </div>
        </nav>
        {/* Theme + logout are desktop-only chrome; hidden on phones to keep the top bar clean. */}
        <div className="hidden md:flex ml-auto items-center gap-1 sm:gap-2 shrink-0">
          <button className="icon-btn" title="Toggle theme" onClick={toggle}>{theme === "dark" ? <IconSun /> : <IconMoon />}</button>
          <button className="icon-btn" title="Log out" onClick={logout}><IconLogOut /></button>
        </div>
      </header>
      {/* stable both-edges keeps left/right gutters equal whether or not a scrollbar is present —
          otherwise the vertical scrollbar eats the right gutter and the page looks shifted left. */}
      <main className="flex-1 min-h-0 overflow-y-auto [scrollbar-gutter:stable_both-edges]"><Outlet /></main>
    </div>
  );
}

/* ── channels ──────────────────────────────────────────────────────────── */
function Channels() {
  const nav = useNavigate();
  const [channels, setChannels] = useState<Channel[] | null>(null);
  const [err, setErr] = useState(""); const [group, setGroup] = useState("All");
  const [q, setQ] = useState(""); const [favs, setFavs] = useState<Set<string>>(new Set());
  const [langs, toggleLang, clearLangs] = useLangFilter();
  const [prefQ, setPrefQ] = usePrefQuality();
  const FAV = "Favorites";

  useEffect(() => {
    api.channels().then((r) => setChannels(r.channels)).catch((e) => setErr(e.message));
    api.favorites().then((r) => { const s = new Set(r.ids); setFavs(s); if (s.size) setGroup(FAV); }).catch(() => {});
  }, []);

  const toggleFav = async (id: string) => {
    const next = new Set(favs); next.has(id) ? next.delete(id) : next.add(id); setFavs(next);
    try { await api.toggleFavorite(id); } catch { setFavs(favs); }
  };
  const groups = useMemo(() => [FAV, "All", ...Array.from(new Set((channels ?? []).map((c) => c.group))).sort()], [channels]);
  const languages = useMemo(() => Array.from(new Set((channels ?? []).map((c) => c.language))).sort(), [channels]);
  const filtered = useMemo(() => (channels ?? [])
    .filter((c) => langs.size === 0 || langs.has(c.language))
    .filter((c) => group === FAV ? favs.has(c.id) : group === "All" || c.group === group)
    .filter((c) => c.name.toLowerCase().includes(q.toLowerCase())), [channels, group, q, favs, langs]);

  if (err) return <div className="empty-state">Couldn’t load channels: {err}</div>;
  if (!channels) return <div className="empty-state">Loading channels…</div>;

  return (
    <div className="flex h-full min-h-0">
      {/* Desktop category rail — hidden on phone/tablet where the toolbar strip takes over. */}
      <aside className="hidden md:block w-52 shrink-0 border-r border-border overflow-y-auto py-3 px-2">
        {groups.map((g) => {
          const Ico = g === FAV ? null : categoryIcon(g);
          const active = group === g;
          return (
            <button key={g} onClick={() => setGroup(g)}
              className={`flex items-center gap-2.5 w-full text-left px-3 py-2 rounded-md text-sm truncate transition-colors ${active ? "bg-surface-2 text-fg" : "text-muted hover:text-fg hover:bg-surface-hover"}`}>
              {g === FAV ? <IconStar size={15} filled={active} className="shrink-0" /> : Ico && <Ico size={15} className="shrink-0 opacity-80" />}
              <span className="truncate">{g}</span>
            </button>
          );
        })}
      </aside>
      <section className="flex-1 min-w-0 overflow-y-auto">
        {/* Solid (not backdrop-blur): a backdrop-filter ancestor becomes the containing block for
            position:fixed, which would break the mobile language bottom-sheet's bottom anchoring. */}
        <div className="sticky top-0 z-10 bg-bg border-b border-border px-4 sm:px-6 py-3 flex flex-wrap items-center gap-2">
          <div className="relative flex-1 min-w-[150px] max-w-sm">
            <IconSearch className="absolute left-3 top-1/2 -translate-y-1/2 text-subtle" size={16} />
            <input className="input pl-9" placeholder="Search channels…" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          {/* Phone/tablet category picker — the desktop rail's replacement, as a compact dropdown. */}
          <div className="md:hidden"><CategoryMenu groups={groups} value={group} onChange={setGroup} favKey={FAV} /></div>
          <QualitySelect value={prefQ} onChange={setPrefQ} />
          <LanguageMenu available={languages} langs={langs} onToggle={toggleLang} onClear={clearLangs} />
        </div>
        {filtered.length === 0 ? (
          <div className="empty-state">{group === FAV ? "No favourites yet — hover a channel and tap the star." : "No channels match."}</div>
        ) : (
          <div className="grid gap-3 sm:gap-4 p-4 sm:p-6" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(140px, 1fr))" }}>
            {filtered.map((c) => (
              <div key={c.id} className="relative group">
                <button onClick={() => nav(`/watch/${c.id}`, { state: { name: c.name } })}
                  className="card card-hover w-full !p-4 flex flex-col items-center gap-2 aspect-[4/5] justify-center">
                  <img src={c.logoUrl} alt="" className="h-16 w-16 rounded-full object-cover bg-surface-2"
                    onError={(e) => ((e.target as HTMLImageElement).style.visibility = "hidden")} />
                  <div className="text-sm text-center leading-snug line-clamp-2">{c.name}</div>
                  <div className="text-subtle text-xs text-center line-clamp-1">{c.group}</div>
                </button>
                <button onClick={() => toggleFav(c.id)} title="Favourite"
                  className={`absolute top-2 right-2 ${favs.has(c.id) ? "text-accent" : "text-subtle opacity-0 group-hover:opacity-100"} transition-opacity`}>
                  <IconStar size={18} filled={favs.has(c.id)} />
                </button>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

/* ── account ───────────────────────────────────────────────────────────── */
type AdminStatus = { loggedIn: boolean; mobile: string; updatedAt: number; crmid: string; userId: string; uniqueId: string; deviceId: string; hasRefreshToken: boolean };
function Account() {
  const [st, setSt] = useState<AdminStatus | null>(null);
  const [mobile, setMobile] = useState(""); const [otp, setOtp] = useState("");
  const [step, setStep] = useState<1 | 2>(1);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [authEnabled, setAuthEnabled] = useState(false); const [newPw, setNewPw] = useState("");

  const refresh = () => api.adminStatus().then(setSt).catch(() => {});
  useEffect(() => { refresh(); api.setupState().then((s) => setAuthEnabled(s.authEnabled)).catch(() => {}); }, []);
  const run = async (fn: () => Promise<unknown>, ok: string) => {
    setMsg(null); try { await fn(); setMsg({ text: ok, ok: true }); refresh(); } catch (e: any) { setMsg({ text: e.message, ok: false }); }
  };

  return (
    <div className="px-4 sm:px-6 py-6 sm:py-8">
      <div className="mx-auto w-full max-w-6xl">
        <h2 className="mb-5">Account &amp; settings</h2>
        {/* Two independent columns, each stacking its own cards — so there are never uneven-height
            gaps between them. Balanced so both columns end at roughly the same height. */}
        <div className="flex flex-col lg:flex-row gap-5 items-start">
          {/* Column A */}
          <div className="flex-1 min-w-0 flex flex-col gap-5">
            <div className="card">
              <div className="flex items-center justify-between">
                <h3>Jio account</h3>
                <span className={`badge ${st?.loggedIn ? "badge-success" : "badge-error"}`}>{st?.loggedIn ? "Signed in" : "Not signed in"}</span>
              </div>
              <div className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 mt-4 text-sm">
                <span className="text-muted">Mobile</span><span className="text-right">{st?.mobile || "—"}</span>
                <span className="text-muted">Subscriber ID</span><span className="text-right font-mono text-xs break-all">{st?.crmid || "—"}</span>
                <span className="text-muted">User ID</span><span className="text-right font-mono text-xs break-all">{st?.userId || "—"}</span>
                <span className="text-muted">Unique ID</span><span className="text-right font-mono text-xs break-all">{st?.uniqueId || "—"}</span>
                <span className="text-muted">Device ID</span><span className="text-right font-mono text-xs break-all">{st?.deviceId || "—"}</span>
                <span className="text-muted">Auto-refresh</span>
                <span className="text-right">{st?.loggedIn ? (st?.hasRefreshToken ? <span className="text-success">Ready</span> : <span className="text-warning">Re-login needed</span>) : "—"}</span>
                <span className="text-muted">Tokens updated</span><span className="text-right">{st?.updatedAt ? new Date(st.updatedAt).toLocaleString() : "—"}</span>
              </div>
              <div className="flex gap-2 mt-5">
                <button className="btn-secondary" onClick={() => run(api.refresh, "Tokens refreshed.")}><IconRefresh /> Refresh</button>
                <button className="btn-ghost" onClick={() => run(api.logoutJio, "Jio account signed out.")}>Sign out Jio</button>
              </div>
            </div>
            <HttpsCard />
          </div>

          {/* Column B */}
          <div className="flex-1 min-w-0 flex flex-col gap-5">
            <div className="card">
              <h3>Sign in to Jio</h3>
              {step === 1 ? (
                <div className="flex flex-wrap gap-2 mt-3">
                  <input className="input flex-1 min-w-[180px]" inputMode="numeric" placeholder="10-digit mobile number" value={mobile} onChange={(e) => setMobile(e.target.value)} />
                  <button className="btn-primary shrink-0" onClick={() => run(async () => { await api.sendOtp(mobile); setStep(2); }, "OTP sent.")}>Send OTP</button>
                </div>
              ) : (
                <div className="flex flex-wrap gap-2 mt-3">
                  <input className="input flex-1 min-w-[140px]" inputMode="numeric" placeholder="Enter OTP" value={otp} onChange={(e) => setOtp(e.target.value)} />
                  <button className="btn-primary shrink-0" onClick={() => run(async () => { await api.verifyOtp(mobile, otp); setStep(1); setOtp(""); }, "Signed in — all TVs will pick this up.")}>Verify</button>
                  <button className="btn-ghost shrink-0" onClick={() => setStep(1)}>Back</button>
                </div>
              )}
              {msg && <p className={`text-sm mt-3 ${msg.ok ? "text-success" : "text-error"}`}>{msg.text}</p>}
            </div>
            <EpgCard />
            <div className="card">
              <h3>Security</h3>
              {authEnabled ? (
                <div className="mt-2">
                  <p className="text-muted text-sm mb-3">Dashboard is password-protected.</p>
                  <button className="btn-secondary" onClick={() => run(async () => { await api.disableAuth(); setAuthEnabled(false); }, "Password removed — dashboard is open.")}>Remove password</button>
                </div>
              ) : (
                <div className="mt-2">
                  <p className="text-muted text-sm mb-3">No password — open on your network (fine for a home LAN).</p>
                  <div className="flex gap-2">
                    <input className="input" type="password" placeholder="New password" value={newPw} onChange={(e) => setNewPw(e.target.value)} />
                    <button className="btn-secondary shrink-0" onClick={() => run(async () => { await api.setPassword(newPw); setNewPw(""); setAuthEnabled(true); }, "Password set.")}>Set password</button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Wide panels that need the full row. */}
        <div className="grid gap-5 mt-5">
          <CodesCard />
          <M3uCard />
          <ReportsCard />
        </div>
      </div>
    </div>
  );
}

/* ── Problem reports (crashes / ANRs uploaded by the TV apps) ──────────── */
function ReportsCard() {
  const [reports, setReports] = useState<ProblemReport[]>([]);
  const load = () => api.reports().then((r) => setReports(r.reports)).catch(() => {});
  useEffect(() => { load(); }, []);
  if (reports.length === 0) return null;
  return (
    <div className="card">
      <h3>Problem reports</h3>
      <p className="text-muted text-sm mt-1">Crashes and freezes sent by your TVs (newest first, last 50 kept). Tokens are removed before they're stored.</p>
      <div className="flex flex-col gap-2 mt-4">
        {reports.map((r) => (
          <details key={r.id} className="rounded-lg border border-[var(--border)] p-3 min-w-0">
            <summary className="cursor-pointer text-sm flex flex-wrap gap-x-3 gap-y-1 items-center">
              <span className={`badge ${r.kind === "crash" || r.kind === "native" ? "badge-error" : "badge-accent"}`}>{r.kind}</span>
              <span>{new Date(r.at).toLocaleString()}</span>
              <span className="text-muted">{r.device} · Android {r.android} · v{r.appVersion}</span>
              <button className="icon-btn !w-8 !h-8 !border-0 hover:!text-error ml-auto" title="Delete"
                onClick={async (e) => { e.preventDefault(); try { await api.deleteReport(r.id); } catch {} load(); }}><IconTrash /></button>
            </summary>
            <pre className="mt-3 text-xs whitespace-pre-wrap break-all max-h-96 overflow-auto">{r.text}</pre>
          </details>
        ))}
      </div>
    </div>
  );
}

/* ── TV access codes ───────────────────────────────────────────────────── */
function CodesCard() {
  const [codes, setCodes] = useState<AccessCode[]>([]);
  const [name, setName] = useState(""); const [manual, setManual] = useState("");
  const [err, setErr] = useState(""); const [copied, setCopied] = useState<string | null>(null);

  const load = () => api.codes().then((r) => setCodes(r.codes)).catch(() => {});
  useEffect(() => { load(); }, []);
  const add = async () => {
    setErr("");
    try { await api.addCode({ name: name || "TV", code: manual.trim() || undefined }); setName(""); setManual(""); load(); }
    catch (e: any) { setErr(e.message); }
  };
  const copy = async (code: string) => { try { await navigator.clipboard.writeText(code); setCopied(code); setTimeout(() => setCopied(null), 1500); } catch {} };

  return (
    <div className="card">
      <h3>TV access codes</h3>
      <p className="text-muted text-sm mt-1">Short codes to connect each TV. Give one per device so you can revoke it later.</p>
      {codes.length > 0 && (
        <div className="overflow-x-auto">
          <table className="table mt-4 min-w-[320px]">
            <thead><tr><th>Name</th><th>Code</th><th></th></tr></thead>
            <tbody>
              {codes.map((c) => (
                <tr key={c.code}>
                  <td>{c.name}</td>
                  <td><span className="font-mono tracking-widest">{c.code}</span></td>
                  <td className="text-right whitespace-nowrap">
                    <button className="icon-btn !w-8 !h-8 !border-0" title="Copy" onClick={() => copy(c.code)}>{copied === c.code ? <IconCheck className="text-success" /> : <IconCopy />}</button>
                    <button className="icon-btn !w-8 !h-8 !border-0 hover:!text-error" title="Delete" onClick={async () => {
                      if (!confirm(`Delete code "${c.code}" (${c.name})? A TV using it will stop connecting until you give it a new code.`)) return;
                      setErr("");
                      try { await api.deleteCode(c.code); } catch (e: any) { setErr(e.message || "Delete failed"); }
                      load();
                    }}><IconTrash /></button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="flex flex-wrap items-end gap-2 mt-4">
        <div className="grow min-w-[160px]"><label className="text-sm text-muted">Device name</label><input className="input mt-1" placeholder="e.g. Living Room TV" value={name} onChange={(e) => setName(e.target.value)} /></div>
        <div className="w-40"><label className="text-sm text-muted">Code (optional)</label><input className="input mt-1 font-mono" placeholder="auto-generate" value={manual} onChange={(e) => setManual(e.target.value)} /></div>
        <button className="btn-primary" onClick={add}><IconPlus /> Add</button>
      </div>
      {err && <p className="text-error text-sm mt-2">{err}</p>}
    </div>
  );
}

/* ── M3U playlist builder (for VLC / TiviMate / OTT Navigator) ─────────── */
const QUALITIES = [
  { v: "auto", label: "Auto (adaptive)" },
  { v: "1080", label: "Up to 1080p" },
  { v: "720", label: "Up to 720p" },
  { v: "480", label: "Up to 480p" },
  { v: "360", label: "Up to 360p" },
];

function useToggleSet(): [Set<string>, (v: string) => void] {
  const [set, setSet] = useState<Set<string>>(new Set());
  const toggle = (v: string) => setSet((s) => { const n = new Set(s); n.has(v) ? n.delete(v) : n.add(v); return n; });
  return [set, toggle];
}

/** Compact checkbox list used for the language / category pickers. */
function CheckList({ options, selected, onToggle }: { options: string[]; selected: Set<string>; onToggle: (v: string) => void }) {
  return (
    <div className="grid grid-cols-2 gap-x-2 gap-y-0.5 max-h-44 overflow-auto rounded-md border border-border p-2 bg-surface-2">
      {options.map((o) => (
        <button key={o} onClick={() => onToggle(o)} className="flex items-center gap-2 min-w-0 text-left px-1.5 py-1 rounded-md text-sm hover:bg-surface-hover">
          <span className={`w-4 h-4 shrink-0 rounded grid place-items-center border ${selected.has(o) ? "bg-accent border-accent" : "border-border-strong"}`}>{selected.has(o) && <IconCheck size={12} className="text-white" />}</span>
          <span className="truncate">{o}</span>
        </button>
      ))}
    </div>
  );
}

function M3uCard() {
  const [channels, setChannels] = useState<Channel[]>([]);
  const [codes, setCodes] = useState<AccessCode[]>([]);
  const [authEnabled, setAuthEnabled] = useState(false);
  const [code, setCode] = useState("");
  const [langs, toggleLang] = useToggleSet();
  const [cats, toggleCat] = useToggleSet();
  const [quality, setQuality] = useState("auto");
  const [onlyFav, setOnlyFav] = useState(false);
  const [epg, setEpg] = useState(true);
  const [catchup, setCatchup] = useState(true);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    api.channels().then((r) => setChannels(r.channels)).catch(() => {});
    api.codes().then((r) => { setCodes(r.codes); if (r.codes[0]) setCode(r.codes[0].code); }).catch(() => {});
    api.setupState().then((s) => setAuthEnabled(s.authEnabled)).catch(() => {});
  }, []);

  const languages = useMemo(() => Array.from(new Set(channels.map((c) => c.language))).sort(), [channels]);
  const categories = useMemo(() => Array.from(new Set(channels.map((c) => c.group))).sort(), [channels]);
  const matchCount = useMemo(() => channels.filter((c) =>
    (langs.size === 0 || langs.has(c.language)) && (cats.size === 0 || cats.has(c.group))
  ).length, [channels, langs, cats]);

  const url = useMemo(() => {
    const p = new URLSearchParams();
    if (code) p.set("code", code);
    if (langs.size) p.set("lang", [...langs].join(","));
    if (cats.size) p.set("group", [...cats].join(","));
    if (quality !== "auto") p.set("quality", quality);
    if (onlyFav) p.set("fav", "1");
    if (epg) p.set("epg", "1");
    if (catchup) p.set("catchup", "1");
    return `${location.origin}/playlist.m3u?${p.toString()}`;
  }, [code, langs, cats, quality, onlyFav, epg, catchup]);

  const copy = async () => { try { await navigator.clipboard.writeText(url); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch {} };

  return (
    <div className="card">
      <div className="flex items-center gap-2"><IconList size={18} className="text-muted" /><h3>M3U playlist</h3></div>
      <p className="text-muted text-sm mt-1">
        Build a playlist link for external players (VLC, TiviMate, OTT Navigator). Pick filters below — only
        non-DRM channels play in these players; DRM channels use the web player or TV app.
      </p>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-5 mt-4">
        <div className="space-y-4">
          <div>
            <label className="text-sm text-muted">Access code {authEnabled ? "" : "(not required — auth is off)"}</label>
            {codes.length ? (
              <select className="input mt-1" value={code} onChange={(e) => setCode(e.target.value)}>
                {codes.map((c) => <option key={c.code} value={c.code}>{c.name} · {c.code}</option>)}
              </select>
            ) : (
              <p className="text-subtle text-sm mt-1">{authEnabled ? "Add a TV access code above first." : "No code needed."}</p>
            )}
          </div>
          <div>
            <label className="text-sm text-muted">Max quality</label>
            <select className="input mt-1" value={quality} onChange={(e) => setQuality(e.target.value)}>
              {QUALITIES.map((q) => <option key={q.v} value={q.v}>{q.label}</option>)}
            </select>
          </div>
          <div className="space-y-2 pt-1">
            <label className="flex items-center gap-2 text-sm cursor-pointer"><input type="checkbox" checked={onlyFav} onChange={(e) => setOnlyFav(e.target.checked)} /> Favourites only</label>
            <label className="flex items-center gap-2 text-sm cursor-pointer"><input type="checkbox" checked={epg} onChange={(e) => setEpg(e.target.checked)} /> Include EPG guide (<code>url-tvg</code>)</label>
            <label className="flex items-center gap-2 text-sm cursor-pointer"><input type="checkbox" checked={catchup} onChange={(e) => setCatchup(e.target.checked)} /> Enable catch-up (TiviMate)</label>
          </div>
        </div>

        <div>
          <div className="flex items-center justify-between"><label className="text-sm text-muted">Languages{langs.size ? ` · ${langs.size}` : " · all"}</label></div>
          <div className="mt-1"><CheckList options={languages} selected={langs} onToggle={toggleLang} /></div>
        </div>
        <div>
          <div className="flex items-center justify-between"><label className="text-sm text-muted">Categories{cats.size ? ` · ${cats.size}` : " · all"}</label></div>
          <div className="mt-1"><CheckList options={categories} selected={cats} onToggle={toggleCat} /></div>
        </div>
      </div>

      <div className="mt-5">
        <label className="text-sm text-muted">Playlist URL · <span className="text-foreground">{matchCount} channels</span></label>
        <div className="flex flex-wrap gap-2 mt-1">
          <input className="input font-mono text-xs flex-1 min-w-[200px]" readOnly value={url} onFocus={(e) => e.target.select()} />
          <button className="btn-secondary shrink-0" onClick={copy}>{copied ? <IconCheck className="text-success" /> : <IconCopy />} {copied ? "Copied" : "Copy"}</button>
          <a className="btn-primary shrink-0" href={url} download="jtv-playlist.m3u"><IconDownload /> Download</a>
        </div>
        <p className="text-subtle text-xs mt-2">Paste the URL into your player as a remote/URL playlist so it refreshes automatically, or use the downloaded <code>.m3u</code> file.</p>
      </div>
    </div>
  );
}

/* ── EPG source ────────────────────────────────────────────────────────── */
function EpgCard() {
  const [cfg, setCfg] = useState<{ mode: "native" | "xmltv"; url: string; status: string; lastSync: number; channels: number } | null>(null);
  const [url, setUrl] = useState("");
  const [msg, setMsg] = useState("");
  const load = () => api.epgConfig().then((c) => { setCfg(c); setUrl(c.url); }).catch(() => {});
  useEffect(() => { load(); }, []);
  const save = async (mode: "native" | "xmltv") => {
    setMsg(""); try { await api.setEpgConfig(mode, url); await load(); setMsg("Saved."); } catch (e: any) { setMsg(e.message); }
  };
  const refresh = async () => {
    setMsg("Downloading…"); try { const r = await api.refreshEpg(); setMsg(`Loaded ${r.channels} channels.`); load(); } catch (e: any) { setMsg(e.message); }
  };
  if (!cfg) return null;
  return (
    <div className="card">
      <h3>EPG source</h3>
      <p className="text-muted text-sm mt-1">Native = Jio’s per-channel guide. XMLTV = a downloaded guide file (.xml or .xml.gz).</p>
      <div className="tabs mt-3">
        <button className={`tab ${cfg.mode === "native" ? "active" : ""}`} onClick={() => save("native")}>Native</button>
        <button className={`tab ${cfg.mode === "xmltv" ? "active" : ""}`} onClick={() => save("xmltv")}>XMLTV</button>
      </div>
      <label className="text-sm text-muted mt-3 block">XMLTV URL</label>
      <div className="flex flex-wrap gap-2 mt-1">
        <input className="input font-mono text-sm flex-1 min-w-[200px]" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…/epg.xml.gz" />
        <button className="btn-secondary shrink-0" onClick={() => save(cfg.mode)}>Save</button>
      </div>
      {cfg.mode === "xmltv" && (
        <div className="flex items-center gap-3 mt-3 flex-wrap">
          <button className="btn-secondary" onClick={refresh}>Refresh now</button>
          <span className="text-subtle text-xs">
            {cfg.status}{cfg.channels ? ` · ${cfg.channels} channels` : ""}{cfg.lastSync ? ` · ${new Date(cfg.lastSync).toLocaleString()}` : ""}
          </span>
        </div>
      )}
      {msg && <p className="text-muted text-sm mt-2">{msg}</p>}
    </div>
  );
}

/* ── HTTPS ─────────────────────────────────────────────────────────────── */
function HttpsCard() {
  const [info, setInfo] = useState<{ httpsPort: number; hasCert: boolean } | null>(null);
  const [msg, setMsg] = useState("");
  useEffect(() => { api.https().then(setInfo).catch(() => {}); }, []);
  const url = info ? `https://${location.hostname}:${info.httpsPort}` : "";
  return (
    <div className="card">
      <h3>HTTPS (for DRM channels)</h3>
      <p className="text-muted text-sm mt-1">
        Encrypted DRM channels need a secure context in the browser. Open the HTTPS URL and accept the
        one-time “not private” warning (self-signed certificate).
      </p>
      {info && (
        <div className="flex flex-wrap items-center gap-2 mt-3">
          <input className="input font-mono text-sm flex-1 min-w-[200px]" readOnly value={url} onFocus={(e) => e.target.select()} />
          <a className="btn-secondary shrink-0" href={url} target="_blank" rel="noreferrer">Open</a>
        </div>
      )}
      <button className="btn-ghost mt-3" onClick={() => api.regenerateHttps().then((r) => setMsg(r.note)).catch((e) => setMsg(e.message))}>Regenerate certificate</button>
      {msg && <p className="text-muted text-sm mt-2">{msg}</p>}
    </div>
  );
}
