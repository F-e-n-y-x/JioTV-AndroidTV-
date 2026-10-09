//go:build !nettls

package main

// HTTPS via the system's curl (or OpenWrt's uclient-fetch): Go's crypto/tls + net/http client would
// add ~1 MB to a router build. Docker builds use fetch_tls.go instead (-tags nettls).

import (
	"context"
	"errors"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

func fetch(method, url string, hdr [][2]string, body string) (int, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if p, err := exec.LookPath("curl"); err == nil {
		return viaCurl(ctx, p, method, url, hdr, body)
	}
	if p, err := exec.LookPath("uclient-fetch"); err == nil {
		return viaUclient(ctx, p, method, url, hdr, body)
	}
	return 0, "", errors.New("no curl or uclient-fetch found (install curl, or uclient-fetch + libustream-mbedtls + ca-bundle)")
}

// curl reads everything (headers, body) from a config on stdin, so tokens never show up in `ps`.
func viaCurl(ctx context.Context, path, method, url string, hdr [][2]string, body string) (int, string, error) {
	q := func(s string) string {
		return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`, "\r", `\r`).Replace(s) + `"`
	}
	var c strings.Builder
	c.WriteString("silent\nshow-error\nhttp1.1\nmax-time = 15\n")
	c.WriteString("url = " + q(url) + "\nrequest = " + q(method) + "\n")
	for _, h := range hdr {
		c.WriteString("header = " + q(h[0]+": "+h[1]) + "\n")
	}
	if body != "" {
		c.WriteString("data-binary = " + q(body) + "\n")
	}
	c.WriteString(`write-out = "\n%{http_code}"` + "\n")
	cmd := exec.CommandContext(ctx, path, "-K", "-")
	cmd.Stdin = strings.NewReader(c.String())
	var errb strings.Builder
	cmd.Stderr = &errb
	out, err := cmd.Output()
	if err != nil {
		return 0, "", errors.New("curl: " + strings.TrimSpace(errb.String()+" "+err.Error()))
	}
	s := string(out)
	i := strings.LastIndexByte(s, '\n')
	code, _ := strconv.Atoi(strings.TrimSpace(s[i+1:]))
	if i < 0 {
		i = 0
	}
	return code, s[:i], nil
}

// uclient-fetch only reports success (2xx body) or "HTTP error NNN" on stderr.
// ponytail: headers go in argv here (visible in `ps` on the router); install curl to avoid that.
func viaUclient(ctx context.Context, path, method, url string, hdr [][2]string, body string) (int, string, error) {
	args := []string{"-O", "-", "--timeout=15"}
	for _, h := range hdr {
		if strings.EqualFold(h[0], "user-agent") {
			args = append(args, "--user-agent="+h[1])
		} else if !strings.EqualFold(h[0], "content-type") || method == "POST" {
			args = append(args, "--header="+h[0]+": "+h[1])
		}
	}
	if method == "POST" {
		args = append(args, "--post-file=/dev/stdin")
	}
	cmd := exec.CommandContext(ctx, path, append(args, url)...)
	cmd.Stdin = strings.NewReader(body)
	var errb strings.Builder
	cmd.Stderr = &errb
	out, err := cmd.Output()
	if err == nil {
		return 200, string(out), nil
	}
	if i := strings.Index(errb.String(), "HTTP error "); i >= 0 {
		code, _ := strconv.Atoi(strings.Fields(errb.String()[i+11:])[0])
		return code, "", nil
	}
	return 0, "", errors.New("uclient-fetch: " + strings.TrimSpace(errb.String()+" "+err.Error()))
}
