package main

// Jio sign-in + token refresh. Mirrors server/src/jio/auth.ts, jio/tokens.ts and refresh.ts exactly
// (same URLs, header names/case, bodies).

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	jioUA      = "okhttp/4.12.0"
	jioApp     = "RJIL_JioTV"
	jioOS      = "android"
	jioDevType = "phone"

	refreshLead   = 2 * time.Hour // refresh when < 2 h of the 12 h token remain
	fallbackAge   = 6 * time.Hour // token without a readable exp: refresh every 6 h
	checkInterval = 30 * time.Minute
)

func mobile91(m string) string {
	if !strings.HasPrefix(m, "+91") {
		m = "+91" + m
	}
	return base64.StdEncoding.EncodeToString([]byte(m))
}

func ok2xx(code int) bool { return code >= 200 && code < 300 }

// jioErr returns Jio's `message` field, or "HTTP <code>".
func jioErr(code int, text string) string {
	v, _ := parseJSON(text)
	if m := str(v, "message"); m != "" {
		return m
	}
	return "HTTP " + strconv.Itoa(code)
}

func sendOtp(mobile string) error {
	code, _, err := fetch("POST", "https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/send", [][2]string{
		{"user-agent", jioUA}, {"os", jioOS}, {"devicetype", jioDevType}, {"appname", jioApp},
		{"Content-Type", "application/json"},
	}, string(enc(nil, map[string]any{"number": mobile91(mobile)})))
	if err != nil {
		return err
	}
	if !ok2xx(code) {
		return errors.New("Failed to send OTP (HTTP " + strconv.Itoa(code) + ")")
	}
	return nil
}

func uuid4() string {
	b := make([]byte, 16)
	rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

func verifyOtp(mobile, otp string) (map[string]string, error) {
	body := map[string]any{
		"number": mobile91(mobile),
		"otp":    otp,
		"deviceInfo": map[string]any{
			"consumptionDeviceName": "unknown sdk_google_atv_x86",
			"info": map[string]any{
				"type":      "android",
				"platform":  map[string]any{"name": "generic_x86"},
				"androidId": uuid4(),
			},
		},
	}
	code, text, err := fetch("POST", "https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/verify", [][2]string{
		{"user-agent", jioUA}, {"os", jioOS}, {"devicetype", jioDevType}, {"appname", jioApp},
		{"Content-Type", "application/json"},
	}, string(enc(nil, body)))
	if err != nil {
		return nil, err
	}
	if !ok2xx(code) {
		return nil, errors.New("OTP verify failed (HTTP " + strconv.Itoa(code) + ")")
	}
	j, _ := parseJSON(text)
	if str(j, "ssoToken") == "" {
		if m := str(j, "message"); m != "" {
			return nil, errors.New(m)
		}
		return nil, errors.New("OTP verification did not return a token")
	}
	u := "sessionAttributes"
	return map[string]string{
		"ssoToken":     str(j, "ssoToken"),
		"authToken":    str(j, "authToken"),
		"refreshToken": str(j, "refreshToken"),
		"crmid":        str(j, u, "user", "subscriberId"),
		"uniqueId":     str(j, u, "user", "unique"),
		"deviceId":     str(j, "deviceId"),
		"userId":       str(j, u, "user", "uid"),
		"mobile":       mobile,
	}, nil
}

func refreshTokens(a map[string]string) (map[string]string, error) {
	if a["ssoToken"] == "" {
		return nil, errors.New("No ssoToken to refresh")
	}
	if a["refreshToken"] == "" {
		return nil, errors.New("No refreshToken stored — sign out and sign in again to capture it.")
	}
	code, text, err := fetch("POST", "https://auth.media.jio.com/tokenservice/apis/v1/refreshtoken?langId=6", [][2]string{
		{"ssotoken", a["ssoToken"]}, {"accesstoken", a["authToken"]}, {"appName", jioApp}, {"os", jioOS},
		{"devicetype", jioDevType}, {"deviceId", a["deviceId"]}, {"uniqueId", a["uniqueId"]},
		{"versionCode", "422"}, {"user-agent", jioUA}, {"Content-Type", "application/json"},
	}, string(enc(nil, map[string]any{
		"appName": jioApp, "deviceId": a["deviceId"], "refreshToken": a["refreshToken"], "uniqueId": a["uniqueId"],
	})))
	if err != nil {
		return nil, err
	}
	if !ok2xx(code) {
		return nil, errors.New("Refresh failed: " + jioErr(code, text))
	}
	j, _ := parseJSON(text)
	na, ns, nr := str(j, "authToken"), str(j, "ssoToken"), str(j, "refreshToken")
	if na == "" && ns == "" {
		return nil, errors.New("Refresh returned no new tokens")
	}
	out := clone(a)
	setIf(out, "authToken", na)
	setIf(out, "ssoToken", ns)
	setIf(out, "refreshToken", nr)
	return out, nil
}

// recoverSession rebuilds a session whose refresh token Jio rejected, from the SSO token + mobile.
func recoverSession(a map[string]string) (map[string]string, error) {
	if a["ssoToken"] == "" {
		return nil, errors.New("No ssoToken to recover from")
	}
	if a["mobile"] == "" {
		return nil, errors.New("Mobile number unknown — sign in again once so it can be saved.")
	}
	sso := a["ssoToken"]
	code, text, err := fetch("GET", "https://tv.media.jio.com/apis/v2.0/loginotp/refresh?langId=6", [][2]string{
		{"devicetype", jioDevType}, {"versionCode", "422"}, {"os", jioOS}, {"user-agent", jioUA},
		{"ssoToken", sso}, {"uniqueid", a["uniqueId"]}, {"deviceid", a["deviceId"]},
	}, "")
	if err == nil && ok2xx(code) {
		if v, _ := parseJSON(text); str(v, "ssoToken") != "" {
			sso = str(v, "ssoToken")
		}
	}
	code, text, err = fetch("POST", "https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/exchangetoken", [][2]string{
		{"ssotoken", sso}, {"appname", jioApp}, {"deviceid", a["deviceId"]}, {"devicetype", jioDevType},
		{"os", jioOS}, {"subscriberid", a["crmid"]}, {"persistentRefreshToken", "true"},
		{"versionCode", "422"}, {"user-agent", jioUA}, {"Content-Type", "application/json"},
	}, string(enc(nil, map[string]any{"number": mobile91(a["mobile"])})))
	if err != nil {
		return nil, err
	}
	if !ok2xx(code) {
		return nil, errors.New("Session recovery failed: " + jioErr(code, text))
	}
	j, _ := parseJSON(text)
	if str(j, "authToken") == "" {
		return nil, errors.New("Session recovery returned no access token")
	}
	out := clone(a)
	out["ssoToken"] = sso
	out["authToken"] = str(j, "authToken")
	setIf(out, "refreshToken", str(j, "refreshToken"))
	setIf(out, "userId", str(j, "userId"))
	setIf(out, "crmid", str(j, "subscriberId"))
	return out, nil
}

func clone(m map[string]string) map[string]string {
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

func setIf(m map[string]string, k, v string) {
	if v != "" {
		m[k] = v
	}
}

// jwtExp is the `exp` of a JWT, or zero time when unreadable.
func jwtExp(tok string) time.Time {
	parts := strings.Split(tok, ".")
	if len(parts) < 2 {
		return time.Time{}
	}
	b, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(parts[1], "="))
	if err != nil {
		return time.Time{}
	}
	v, _ := parseJSON(string(b))
	m, _ := v.(map[string]any)
	if f, ok := m["exp"].(float64); ok {
		return time.Unix(int64(f), 0)
	}
	return time.Time{}
}

func needsRefresh(auth string, updated, now time.Time) bool {
	if exp := jwtExp(auth); !exp.IsZero() {
		return exp.Sub(now) < refreshLead
	}
	return now.Sub(updated) > fallbackAge
}

// Single-flight refresh shared by the scheduler, the TVs and the admin button.
var (
	rmu        sync.Mutex
	inflight   chan struct{}
	lastOK     bool
	lastErr    string
	rejectedAt string // authToken Jio rejected; stop retrying until a new sign-in
)

func refreshNow(force bool) (bool, string) {
	rmu.Lock()
	if ch := inflight; ch != nil {
		rmu.Unlock()
		<-ch
		rmu.Lock()
		defer rmu.Unlock()
		return lastOK, lastErr
	}
	ch := make(chan struct{})
	inflight = ch
	rmu.Unlock()

	ok, msg := doRefresh(force)

	rmu.Lock()
	lastOK, lastErr, inflight = ok, msg, nil
	rmu.Unlock()
	close(ch)
	return ok, msg
}

// doRefresh runs under the single-flight; each fetch has its own timeout, so it always returns.
func doRefresh(force bool) (bool, string) {
	c, updated := getCreds()
	if c == nil {
		return false, "No credentials — sign in to Jio first."
	}
	if rejectedAt != "" && rejectedAt == c["authToken"] {
		return false, "Jio rejected the saved sign-in. Sign in again on the admin page."
	}
	if !force && !needsRefresh(c["authToken"], updated, time.Now()) {
		return true, ""
	}
	n, err := refreshTokens(c)
	if err == nil {
		putCreds(n)
		logf("refresh: tokens refreshed")
		return true, ""
	}
	msg := err.Error()
	logf("refresh failed: " + msg)
	l := strings.ToLower(msg)
	if strings.Contains(l, "refresh token") || strings.Contains(l, "expired") || strings.Contains(l, "not found") || strings.Contains(l, "http 4") {
		if r, err2 := recoverSession(c); err2 == nil {
			putCreds(r)
			logf("refresh: session recovered without a new sign-in")
			return true, ""
		} else {
			logf("refresh: recovery failed: " + err2.Error())
			rejectedAt = c["authToken"]
		}
	}
	return false, msg
}

func refreshLoop() {
	time.Sleep(30 * time.Second)
	for {
		refreshNow(false)
		time.Sleep(checkInterval)
	}
}
