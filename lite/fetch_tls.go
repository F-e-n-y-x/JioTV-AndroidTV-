//go:build nettls

package main

// Go's own HTTPS client, for Docker (scratch has no curl). Size doesn't matter there.

import (
	"crypto/tls"
	"io"
	"net/http"
	"strings"
	"time"
)

var client = &http.Client{
	Timeout: 15 * time.Second,
	// HTTP/1.1 only: Jio's auth endpoints are header-case-sensitive and HTTP/2 lowercases names.
	Transport: &http.Transport{TLSNextProto: map[string]func(string, *tls.Conn) http.RoundTripper{}},
}

func fetch(method, url string, hdr [][2]string, body string) (int, string, error) {
	req, err := http.NewRequest(method, url, strings.NewReader(body))
	if err != nil {
		return 0, "", err
	}
	for _, h := range hdr {
		req.Header[h[0]] = []string{h[1]} // raw key: keeps the exact case Jio expects
	}
	res, err := client.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer res.Body.Close()
	b, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	return res.StatusCode, string(b), err
}
