package main

import (
	"encoding/base64"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestAccessCode(t *testing.T) {
	codes = nil
	c := addCode("Default")
	if len(c) != 6 || strings.Trim(c, codeAlphabet) != "" {
		t.Fatalf("bad code %q", c)
	}
	if !hasCode(c) || !hasCode(" "+strings.ToLower(c)+" ") {
		t.Fatal("valid code rejected")
	}
	if hasCode("") || hasCode("WRONG1") {
		t.Fatal("invalid code accepted")
	}
	r := route(&request{method: "GET", path: "/api/credentials", ip: "t1", hdr: map[string]string{}})
	if r.code != 401 {
		t.Fatalf("no code: got %d", r.code)
	}
	creds = nil
	r = route(&request{method: "GET", path: "/api/credentials", ip: "t1", hdr: map[string]string{"authorization": "Bearer " + c}})
	if r.code != 404 {
		t.Fatalf("not signed in: got %d", r.code)
	}
}

func TestCredentialsShape(t *testing.T) {
	codes = []accessCode{{"x", "ABCDEF", 0}}
	creds = map[string]string{"ssoToken": "s", "authToken": "a", "refreshToken": "SECRET", "crmid": "c",
		"uniqueId": "u", "deviceId": "d", "userId": "id", "mobile": "9999999999"}
	r := route(&request{method: "GET", path: "/api/credentials", ip: "t2", hdr: map[string]string{"authorization": "Bearer ABCDEF"}})
	want := `{"authToken":"a","crmid":"c","deviceId":"d","ssoToken":"s","uniqueId":"u","userId":"id"}`
	if r.code != 200 || string(r.body) != want {
		t.Fatalf("got %d %s", r.code, r.body)
	}
	for i := 0; i < 10; i++ {
		route(&request{method: "GET", path: "/api/credentials", ip: "t2", hdr: map[string]string{}})
	}
	if r := route(&request{method: "GET", path: "/api/credentials", ip: "t2", hdr: map[string]string{}}); r.code != 429 {
		t.Fatalf("rate limit: got %d", r.code)
	}
}

func TestDiscoveryJSON(t *testing.T) {
	srvName, srvPort, version = `My "Router"`, 29180, "1.0"
	got := string(enc(nil, discoveryJSON()))
	want := `{"app":"jtv-server","https":false,"kind":"lite","name":"My \"Router\"","port":29180,"version":"1.0"}`
	if got != want {
		t.Fatalf("got %s", got)
	}
	v, err := parseJSON(got)
	if err != nil || str(v, "name") != `My "Router"` || str(v, "port") != "29180" {
		t.Fatalf("round trip: %v %v", v, err)
	}
}

func TestJSONParse(t *testing.T) {
	v, err := parseJSON(`{"a":{"b":[1,true,null,"xé😀\n"]},"n":-1.5e2}`)
	if err != nil {
		t.Fatal(err)
	}
	if s := v.(map[string]any)["a"].(map[string]any)["b"].([]any)[3]; s != "xé😀\n" {
		t.Fatalf("got %q", s)
	}
	for _, bad := range []string{``, `{`, `{"a"}`, `[1,]`, `"x`, `{"a":1}x`} {
		if _, err := parseJSON(bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
}

func TestNeedsRefresh(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	tok := func(exp int64) string {
		return "h." + strings.TrimRight(b64(`{"exp":`+itoa(exp)+`}`), "=") + ".s"
	}
	if needsRefresh(tok(now.Unix()+3*3600), now, now) || !needsRefresh(tok(now.Unix()+3600), now, now) {
		t.Fatal("exp-based check wrong")
	}
	if needsRefresh("opaque", now.Add(-time.Hour), now) || !needsRefresh("opaque", now.Add(-7*time.Hour), now) {
		t.Fatal("age-based check wrong")
	}
}

func b64(s string) string { return base64.RawURLEncoding.EncodeToString([]byte(s)) }
func itoa(n int64) string { return strconv.FormatInt(n, 10) }
