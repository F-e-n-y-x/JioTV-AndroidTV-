package main

// Tiny JSON for our fixed shapes. encoding/json costs ~300 KB on mipsle (the whole budget is 1 MB).
// Parses into map[string]any / []any / string / float64 / bool / nil, like encoding/json's `any`.

import (
	"errors"
	"sort"
	"strconv"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

var errJSON = errors.New("bad JSON")

type jp struct {
	s string
	i int
}

func parseJSON(s string) (any, error) {
	p := &jp{s: s}
	v, err := p.val(0)
	if err != nil {
		return nil, err
	}
	p.ws()
	if p.i != len(p.s) {
		return nil, errJSON
	}
	return v, nil
}

// parseObj parses a JSON object body; anything else yields an empty map.
func parseObj(s string) map[string]any {
	v, _ := parseJSON(s)
	m, _ := v.(map[string]any)
	if m == nil {
		m = map[string]any{}
	}
	return m
}

func (p *jp) ws() {
	for p.i < len(p.s) && strings.IndexByte(" \t\r\n", p.s[p.i]) >= 0 {
		p.i++
	}
}

func (p *jp) eat(c byte) bool {
	p.ws()
	if p.i < len(p.s) && p.s[p.i] == c {
		p.i++
		return true
	}
	return false
}

func (p *jp) val(depth int) (any, error) {
	p.ws()
	if depth > 32 || p.i >= len(p.s) {
		return nil, errJSON
	}
	rest := p.s[p.i:]
	switch {
	case rest[0] == '{':
		p.i++
		m := map[string]any{}
		if p.eat('}') {
			return m, nil
		}
		for {
			p.ws()
			if p.i >= len(p.s) || p.s[p.i] != '"' {
				return nil, errJSON
			}
			k, err := p.str()
			if err != nil {
				return nil, err
			}
			if !p.eat(':') {
				return nil, errJSON
			}
			if m[k], err = p.val(depth + 1); err != nil {
				return nil, err
			}
			if p.eat(',') {
				continue
			}
			if p.eat('}') {
				return m, nil
			}
			return nil, errJSON
		}
	case rest[0] == '[':
		p.i++
		a := []any{}
		if p.eat(']') {
			return a, nil
		}
		for {
			v, err := p.val(depth + 1)
			if err != nil {
				return nil, err
			}
			a = append(a, v)
			if p.eat(',') {
				continue
			}
			if p.eat(']') {
				return a, nil
			}
			return nil, errJSON
		}
	case rest[0] == '"':
		return p.str()
	case strings.HasPrefix(rest, "true"):
		p.i += 4
		return true, nil
	case strings.HasPrefix(rest, "false"):
		p.i += 5
		return false, nil
	case strings.HasPrefix(rest, "null"):
		p.i += 4
		return nil, nil
	}
	j := p.i
	for j < len(p.s) && strings.IndexByte("+-0123456789.eE", p.s[j]) >= 0 {
		j++
	}
	f, err := strconv.ParseFloat(p.s[p.i:j], 64)
	if j == p.i || err != nil {
		return nil, errJSON
	}
	p.i = j
	return f, nil
}

func (p *jp) str() (string, error) {
	p.i++ // opening quote
	var b []byte
	for p.i < len(p.s) {
		c := p.s[p.i]
		if c == '"' {
			p.i++
			return string(b), nil
		}
		if c != '\\' {
			b = append(b, c)
			p.i++
			continue
		}
		if p.i+1 >= len(p.s) {
			break
		}
		e := p.s[p.i+1]
		p.i += 2
		switch e {
		case '"', '\\', '/':
			b = append(b, e)
		case 'b':
			b = append(b, '\b')
		case 'f':
			b = append(b, '\f')
		case 'n':
			b = append(b, '\n')
		case 'r':
			b = append(b, '\r')
		case 't':
			b = append(b, '\t')
		case 'u':
			r, ok := p.hex4(p.i)
			if !ok {
				return "", errJSON
			}
			p.i += 4
			if utf16.IsSurrogate(r) && strings.HasPrefix(p.s[p.i:], `\u`) {
				if r2, ok := p.hex4(p.i + 2); ok {
					if d := utf16.DecodeRune(r, r2); d != utf8.RuneError {
						r = d
						p.i += 6
					}
				}
			}
			b = utf8.AppendRune(b, r)
		default:
			return "", errJSON
		}
	}
	return "", errJSON
}

func (p *jp) hex4(i int) (rune, bool) {
	if i+4 > len(p.s) {
		return 0, false
	}
	n, err := strconv.ParseUint(p.s[i:i+4], 16, 32)
	return rune(n), err == nil
}

// str returns a string field (numbers formatted) from nested maps: str(m, "a", "b") = m.a.b.
func str(v any, path ...string) string {
	for _, k := range path {
		m, _ := v.(map[string]any)
		v = m[k]
	}
	switch x := v.(type) {
	case string:
		return x
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	}
	return ""
}

// enc appends v as JSON. Supports the types the parser yields plus int, int64, []string,
// map[string]string, []map[string]any. Map keys are sorted.
func enc(b []byte, v any) []byte {
	switch x := v.(type) {
	case nil:
		return append(b, "null"...)
	case string:
		return encStr(b, x)
	case bool:
		return strconv.AppendBool(b, x)
	case int:
		return strconv.AppendInt(b, int64(x), 10)
	case int64:
		return strconv.AppendInt(b, x, 10)
	case float64:
		return strconv.AppendFloat(b, x, 'f', -1, 64)
	case []any:
		b = append(b, '[')
		for i, e := range x {
			if i > 0 {
				b = append(b, ',')
			}
			b = enc(b, e)
		}
		return append(b, ']')
	case []string:
		a := make([]any, len(x))
		for i, e := range x {
			a[i] = e
		}
		return enc(b, a)
	case []map[string]any:
		a := make([]any, len(x))
		for i, e := range x {
			a[i] = e
		}
		return enc(b, a)
	case map[string]string:
		m := make(map[string]any, len(x))
		for k, e := range x {
			m[k] = e
		}
		return enc(b, m)
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		b = append(b, '{')
		for i, k := range keys {
			if i > 0 {
				b = append(b, ',')
			}
			b = append(encStr(b, k), ':')
			b = enc(b, x[k])
		}
		return append(b, '}')
	}
	panic("enc: unsupported type")
}

func encStr(b []byte, s string) []byte {
	const hex = "0123456789abcdef"
	b = append(b, '"')
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c == '"' || c == '\\':
			b = append(b, '\\', c)
		case c < 0x20 || c == '<' || c == '>' || c == '&':
			b = append(b, '\\', 'u', '0', '0', hex[c>>4], hex[c&15])
		default:
			b = append(b, c)
		}
	}
	return append(b, '"')
}
