// jtv-lite: a tiny JTV companion server for routers. It signs in to Jio once, keeps the tokens fresh
// and hands them to the JTV app (GET /api/credentials with an access code). No streaming proxy.
package main

import (
	"bufio"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	_ "embed"
	"encoding/hex"
	"flag"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

var version = "dev"

const discoveryPort = 29180

//go:embed ui.html
var uiHTML string

var (
	dataPath string
	srvName  string
	srvPort  int
)

func logf(s string) { os.Stderr.WriteString(time.Now().Format("2006-01-02 15:04:05 ") + s + "\n") }

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	host, _ := os.Hostname()
	port, _ := strconv.Atoi(env("PORT", strconv.Itoa(discoveryPort)))
	flag.IntVar(&srvPort, "port", port, "HTTP port (env PORT)")
	flag.StringVar(&srvName, "name", env("SERVER_NAME", host), "name shown in the app (env SERVER_NAME)")
	flag.StringVar(&dataPath, "data", env("JTV_DATA", "./jtv-lite.json"), "data file (env JTV_DATA)")
	flag.Parse()
	if srvName == "" {
		srvName = "JTV lite"
	}
	if err := load(); err != nil {
		logf("cannot read " + dataPath + ": " + err.Error())
		os.Exit(1)
	}
	l, err := net.Listen("tcp", ":"+strconv.Itoa(srvPort))
	if err != nil {
		logf(err.Error())
		os.Exit(1)
	}
	if srvPort != discoveryPort { // the app scans 29180, so always answer /jtv-server there
		if dl, err := net.Listen("tcp", ":"+strconv.Itoa(discoveryPort)); err == nil {
			go serve(dl, true)
		} else {
			logf("discovery port: " + err.Error())
		}
	}
	go refreshLoop()
	go startMDNS()
	logf("jtv-lite " + version + " on :" + strconv.Itoa(srvPort) + " — open http://" + firstAddr() + ":" + strconv.Itoa(srvPort))
	serve(l, false)
}

// ── Storage: one JSON file, written atomically (temp + rename), 0600 ──

type accessCode struct {
	Name, Code string
	Created    int64
}

var (
	mu       sync.Mutex
	pwSalt   string
	pwHash   string
	pwIter   int
	creds    map[string]string // nil when not signed in to Jio
	credsAt  time.Time
	codes    []accessCode
	sessions = map[string]time.Time{}
	reports  []map[string]any
	limits   = map[string][2]int64{}
)

func load() error {
	b, err := os.ReadFile(dataPath)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	v, err := parseJSON(string(b))
	if err != nil {
		return err
	}
	m, _ := v.(map[string]any)
	pwSalt, pwHash = str(m, "admin", "salt"), str(m, "admin", "hash")
	pwIter, _ = strconv.Atoi(str(m, "admin", "iter"))
	if c, ok := m["creds"].(map[string]any); ok {
		creds = map[string]string{}
		for k := range c {
			creds[k] = str(c, k)
		}
	}
	at, _ := strconv.ParseInt(str(m, "updatedAt"), 10, 64)
	credsAt = time.UnixMilli(at)
	list, _ := m["codes"].([]any)
	for _, e := range list {
		created, _ := strconv.ParseInt(str(e, "createdAt"), 10, 64)
		codes = append(codes, accessCode{str(e, "name"), str(e, "code"), created})
	}
	return nil
}

// save writes the state; caller holds mu.
func save() {
	m := map[string]any{"codes": codeList()}
	if pwHash != "" {
		m["admin"] = map[string]any{"salt": pwSalt, "hash": pwHash, "iter": pwIter}
	}
	if creds != nil {
		m["creds"], m["updatedAt"] = creds, credsAt.UnixMilli()
	}
	tmp := dataPath + ".tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err == nil {
		_, err = f.Write(enc(nil, m))
		if err == nil {
			err = f.Sync()
		}
		f.Close()
	}
	if err == nil {
		err = os.Rename(tmp, dataPath)
	}
	if err != nil {
		logf("save failed: " + err.Error())
	}
}

func codeList() []map[string]any {
	out := []map[string]any{}
	for _, c := range codes {
		out = append(out, map[string]any{"name": c.Name, "code": c.Code, "createdAt": c.Created})
	}
	return out
}

func getCreds() (map[string]string, time.Time) {
	mu.Lock()
	defer mu.Unlock()
	if creds == nil {
		return nil, credsAt
	}
	return clone(creds), credsAt
}

func putCreds(c map[string]string) {
	mu.Lock()
	defer mu.Unlock()
	creds, credsAt = c, time.Now()
	save()
}

// credsJSON is what the app gets. Never includes refreshToken or mobile.
func credsJSON(c map[string]string) map[string]any {
	out := map[string]any{}
	for _, k := range []string{"ssoToken", "authToken", "crmid", "uniqueId", "deviceId", "userId"} {
		out[k] = c[k]
	}
	return out
}

func discoveryJSON() map[string]any {
	return map[string]any{"app": "jtv-server", "kind": "lite", "name": srvName, "port": srvPort, "https": false, "version": version}
}

// ── Access codes (same alphabet + lengths as server/src/util/code.ts) ──

const codeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

func randCode(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	for i := range b {
		b[i] = codeAlphabet[int(b[i])%len(codeAlphabet)] // 256 % 32 == 0: no bias
	}
	return string(b)
}

// hasCode reports whether tok (as typed, or upper-cased) is a valid code; caller holds mu.
func hasCode(tok string) bool {
	tok = strings.TrimSpace(tok)
	if tok == "" {
		return false
	}
	up := strings.ToUpper(tok)
	for _, c := range codes {
		if c.Code == tok || c.Code == up {
			return true
		}
	}
	return false
}

func addCode(name string) string {
	c := randCode(6)
	for hasCode(c) {
		c = randCode(6)
	}
	codes = append(codes, accessCode{name, c, time.Now().UnixMilli()})
	return c
}

// ── Admin password: PBKDF2-SHA256 ──

const kdfIter = 20000 // ponytail: low for slow router CPUs (~1 s on MIPS); raise on faster boxes

func hashPw(pw, saltHex string, iter int) string {
	salt, _ := hex.DecodeString(saltHex)
	k, _ := pbkdf2.Key(sha256.New, pw, salt, iter, 32)
	return hex.EncodeToString(k)
}

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// ── Tiny HTTP/1.1 server (net/http costs ~1 MB on mipsle) ──

type request struct {
	method, path, ip string
	hdr              map[string]string // lower-case names
	body             string
}

type response struct {
	code   int
	ctype  string
	body   []byte
	cookie string
}

const maxBody = 256 << 10

var connSlots = make(chan struct{}, 32)

func serve(l net.Listener, discoveryOnly bool) {
	for {
		c, err := l.Accept()
		if err != nil {
			time.Sleep(100 * time.Millisecond)
			continue
		}
		select {
		case connSlots <- struct{}{}:
			go func() {
				defer func() { <-connSlots }()
				handleConn(c, discoveryOnly)
			}()
		default:
			c.Close() // too busy
		}
	}
}

func handleConn(c net.Conn, discoveryOnly bool) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	br := bufio.NewReaderSize(c, 4096)
	line, err := br.ReadSlice('\n')
	if err != nil {
		return
	}
	f := strings.Fields(string(line))
	if len(f) != 3 {
		return
	}
	r := &request{method: f[0], path: f[1], hdr: map[string]string{}}
	if i := strings.IndexByte(r.path, '?'); i >= 0 {
		r.path = r.path[:i]
	}
	r.ip, _, _ = net.SplitHostPort(c.RemoteAddr().String())
	for n := 0; ; n++ {
		line, err := br.ReadSlice('\n')
		if err != nil || n > 64 {
			return
		}
		s := strings.TrimRight(string(line), "\r\n")
		if s == "" {
			break
		}
		if k, v, ok := strings.Cut(s, ":"); ok {
			r.hdr[strings.ToLower(strings.TrimSpace(k))] = strings.TrimSpace(v)
		}
	}
	var res response
	if cl := r.hdr["content-length"]; cl != "" {
		n, err := strconv.Atoi(cl)
		if err != nil || n < 0 || n > maxBody {
			res = jsonRes(413, map[string]any{"error": "Body too large"})
		} else {
			b := make([]byte, n)
			if _, err := io.ReadFull(br, b); err != nil {
				return
			}
			r.body = string(b)
		}
	}
	if res.code == 0 {
		if discoveryOnly && r.path != "/jtv-server" {
			res = jsonRes(404, map[string]any{"error": "Not found"})
		} else {
			res = route(r)
		}
	}
	h := "HTTP/1.1 " + strconv.Itoa(res.code) + " " + statusText(res.code) + "\r\nContent-Type: " + res.ctype +
		"\r\nContent-Length: " + strconv.Itoa(len(res.body)) + "\r\nCache-Control: no-store\r\nConnection: close\r\n"
	if res.cookie != "" {
		h += "Set-Cookie: " + res.cookie + "\r\n"
	}
	io.WriteString(c, h+"\r\n")
	if r.method != "HEAD" {
		c.Write(res.body)
	}
}

func statusText(code int) string {
	switch code {
	case 200:
		return "OK"
	case 400:
		return "Bad Request"
	case 401:
		return "Unauthorized"
	case 403:
		return "Forbidden"
	case 404:
		return "Not Found"
	case 409:
		return "Conflict"
	case 413:
		return "Payload Too Large"
	case 429:
		return "Too Many Requests"
	case 502:
		return "Bad Gateway"
	}
	return "Status"
}

func jsonRes(code int, v map[string]any) response {
	return response{code: code, ctype: "application/json", body: enc(nil, v)}
}

func errRes(code int, msg string) response { return jsonRes(code, map[string]any{"error": msg}) }

var okRes = map[string]any{"ok": true}

// allow is a fixed-window per-IP limiter; caller holds mu.
func allow(key string, max int, window int64) bool {
	now := time.Now().Unix()
	e := limits[key]
	if now-e[0] >= window {
		e = [2]int64{now, 0}
	}
	e[1]++
	if len(limits) > 4096 {
		limits = map[string][2]int64{} // ponytail: crude reset bounds memory under a flood
	}
	limits[key] = e
	return e[1] <= int64(max)
}

func bearer(r *request) string {
	t, _ := strings.CutPrefix(r.hdr["authorization"], "Bearer ")
	return strings.TrimSpace(t)
}

func sessionOK(r *request) bool {
	for _, kv := range strings.Split(r.hdr["cookie"], ";") {
		if k, v, _ := strings.Cut(strings.TrimSpace(kv), "="); k == "jtvl" {
			exp, ok := sessions[v]
			return ok && time.Now().Before(exp)
		}
	}
	return false
}

func newSession() string {
	now := time.Now()
	for k, exp := range sessions {
		if now.After(exp) {
			delete(sessions, k)
		}
	}
	sid := randHex(24)
	sessions[sid] = now.Add(7 * 24 * time.Hour)
	return "jtvl=" + sid + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800"
}

func route(r *request) response {
	mu.Lock()
	defer mu.Unlock() // released around the slow calls below
	p, m := r.path, r.method
	limited := func(name string, max int, window int64) bool { return !allow(name+r.ip, max, window) }

	switch {
	case p == "/" && (m == "GET" || m == "HEAD"):
		return response{code: 200, ctype: "text/html; charset=utf-8", body: []byte(uiHTML)}
	case p == "/jtv-server":
		return jsonRes(200, discoveryJSON())
	case p == "/api/status":
		return jsonRes(200, map[string]any{"ok": true, "hasCredentials": creds != nil})

	// ── App endpoints (access code) ──
	case p == "/api/credentials" || (p == "/api/refresh" && m == "POST"):
		if limited(p, 10, 60) {
			return errRes(429, "Too many requests")
		}
		if !hasCode(bearer(r)) {
			return errRes(401, "Invalid access code")
		}
		if p == "/api/credentials" {
			if creds == nil {
				return errRes(404, "No active login on the server yet")
			}
			return jsonRes(200, credsJSON(creds))
		}
		mu.Unlock()
		ok, msg := refreshNow(false)
		mu.Lock()
		if creds == nil {
			return errRes(404, "No active login on the server yet")
		}
		out := credsJSON(creds)
		out["ok"] = ok
		if !ok {
			out["error"] = msg
		}
		return jsonRes(200, out)
	case p == "/api/reports" && m == "POST":
		if limited(p, 20, 3600) {
			return errRes(429, "Too many requests")
		}
		if !hasCode(bearer(r)) {
			return errRes(401, "Invalid access code")
		}
		b := parseObj(r.body)
		if strings.TrimSpace(str(b, "text")) == "" {
			return errRes(400, "Expected {kind, at, appVersion, device, android, text}")
		}
		rep := map[string]any{"receivedAt": time.Now().UnixMilli()}
		for k, max := range map[string]int{"kind": 16, "at": 20, "appVersion": 40, "device": 80, "android": 40, "text": 16 << 10} {
			rep[k] = cut(str(b, k), max)
		}
		reports = append(reports, rep)
		if len(reports) > 20 {
			reports = reports[len(reports)-20:]
		}
		return jsonRes(200, okRes)

	// ── Admin: setup + login ──
	case p == "/api/admin/state":
		st := map[string]any{"needsSetup": pwHash == "", "authed": sessionOK(r)}
		if sessionOK(r) {
			st["name"], st["port"], st["version"], st["addrs"] = srvName, srvPort, version, privateIPv4s()
			st["codes"], st["reports"] = codeList(), reports
			if reports == nil {
				st["reports"] = []any{}
			}
			jio := map[string]any{"signedIn": creds != nil}
			if creds != nil {
				jio["mobile"], jio["updatedAt"], jio["hasRefreshToken"] = creds["mobile"], credsAt.UnixMilli(), creds["refreshToken"] != ""
				if exp := jwtExp(creds["authToken"]); !exp.IsZero() {
					jio["expiresAt"] = exp.UnixMilli()
				}
			}
			st["jio"] = jio
		}
		return jsonRes(200, st)
	case (p == "/api/setup" || p == "/api/admin/login") && m == "POST":
		if limited("login", 10, 60) {
			return errRes(429, "Too many requests")
		}
		pw := str(parseObj(r.body), "password")
		if p == "/api/setup" {
			if pwHash != "" {
				return errRes(403, "Already set up")
			}
			if len(pw) < 4 {
				return errRes(400, "Choose a password of at least 4 characters")
			}
			pwSalt, pwIter = randHex(16), kdfIter
			pwHash = hashPw(pw, pwSalt, pwIter)
			if len(codes) == 0 {
				addCode("Default")
			}
			save()
		} else {
			if pwHash == "" {
				return errRes(409, "Not set up yet")
			}
			if subtle.ConstantTimeCompare([]byte(hashPw(pw, pwSalt, pwIter)), []byte(pwHash)) != 1 {
				return errRes(401, "Wrong password")
			}
		}
		res := jsonRes(200, okRes)
		res.cookie = newSession()
		return res
	case p == "/api/admin/logout" && m == "POST":
		for _, kv := range strings.Split(r.hdr["cookie"], ";") {
			if k, v, _ := strings.Cut(strings.TrimSpace(kv), "="); k == "jtvl" {
				delete(sessions, v)
			}
		}
		res := jsonRes(200, okRes)
		res.cookie = "jtvl=; Path=/; Max-Age=0"
		return res
	}

	// ── Admin-only below ──
	if !strings.HasPrefix(p, "/api/") {
		return errRes(404, "Not found")
	}
	if !sessionOK(r) {
		return errRes(401, "Not authenticated")
	}
	b := parseObj(r.body)
	switch {
	case p == "/api/login/otp/send" && m == "POST":
		if limited("otp", 5, 60) {
			return errRes(429, "Too many requests")
		}
		mob := digits(str(b, "mobile"))
		if len(mob) < 10 {
			return errRes(400, "Enter a valid 10-digit mobile number")
		}
		mu.Unlock()
		err := sendOtp(mob[len(mob)-10:])
		mu.Lock()
		if err != nil {
			return errRes(502, err.Error())
		}
		return jsonRes(200, okRes)
	case p == "/api/login/otp/verify" && m == "POST":
		if limited("login", 10, 60) {
			return errRes(429, "Too many requests")
		}
		mob, otp := digits(str(b, "mobile")), strings.TrimSpace(str(b, "otp"))
		if len(mob) < 10 || otp == "" {
			return errRes(400, "Mobile and OTP are required")
		}
		mu.Unlock()
		c, err := verifyOtp(mob[len(mob)-10:], otp)
		mu.Lock()
		if err != nil {
			return errRes(502, err.Error())
		}
		creds, credsAt = c, time.Now()
		save()
		return jsonRes(200, okRes)
	case p == "/api/admin/refresh" && m == "POST":
		if limited(p, 10, 60) {
			return errRes(429, "Too many requests")
		}
		mu.Unlock()
		ok, msg := refreshNow(true)
		mu.Lock()
		if !ok {
			return errRes(400, msg)
		}
		return jsonRes(200, okRes)
	case p == "/api/admin/logout-jio" && m == "POST":
		creds = nil
		save()
		return jsonRes(200, okRes)
	case p == "/api/admin/codes" && m == "POST":
		name := cut(strings.TrimSpace(str(b, "name")), 40)
		if name == "" {
			name = "TV"
		}
		c := addCode(name)
		save()
		return jsonRes(200, map[string]any{"code": c, "name": name})
	case strings.HasPrefix(p, "/api/admin/codes/") && m == "DELETE":
		del := strings.TrimPrefix(p, "/api/admin/codes/")
		kept := codes[:0]
		for _, c := range codes {
			if c.Code != del {
				kept = append(kept, c)
			}
		}
		codes = kept
		save()
		return jsonRes(200, okRes)
	}
	return errRes(404, "Not found")
}

func digits(s string) string {
	var b []byte
	for i := 0; i < len(s); i++ {
		if s[i] >= '0' && s[i] <= '9' {
			b = append(b, s[i])
		}
	}
	return string(b)
}

func cut(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

// privateIPv4s lists the host's LAN addresses (what the user types into the app).
func privateIPv4s() []string {
	out := []string{}
	for _, a := range lanAddrs() {
		out = append(out, a.ip.String())
	}
	return out
}

type lanAddr struct {
	ifi net.Interface
	ip  net.IP
	net *net.IPNet
}

func lanAddrs() []lanAddr {
	var out []lanAddr
	ifs, _ := net.Interfaces()
	for _, ifi := range ifs {
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifi.Addrs()
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && n.IP.IsPrivate() {
				out = append(out, lanAddr{ifi, n.IP.To4(), n})
			}
		}
	}
	return out
}

func firstAddr() string {
	if a := privateIPv4s(); len(a) > 0 {
		return a[0]
	}
	return "localhost"
}
