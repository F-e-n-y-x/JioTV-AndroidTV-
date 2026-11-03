package main

// Minimal mDNS responder: _jtv-server._tcp.local → PTR/SRV/TXT/A. One socket per LAN interface
// (each sends out its own interface); a query is answered only by the socket whose subnet holds the
// sender, so Linux's "every socket sees every group packet" doesn't cause duplicate answers.

import (
	"net"
	"strings"

	"golang.org/x/net/dns/dnsmessage"
)

const mdnsTTL = 120

var mdnsGroup = &net.UDPAddr{IP: net.IPv4(224, 0, 0, 251), Port: 5353}

func startMDNS() {
	for _, a := range lanAddrs() {
		c, err := net.ListenMulticastUDP("udp4", &a.ifi, mdnsGroup)
		if err != nil {
			logf("mDNS off on " + a.ifi.Name + " (" + err.Error() + "); the app still finds this server by scanning port 29180")
			continue
		}
		if pkt, err := mdnsAnswer(nil, a.ip, true); err == nil {
			c.WriteToUDP(pkt, mdnsGroup)
		}
		go mdnsLoop(c, a)
	}
}

func mdnsLoop(c *net.UDPConn, a lanAddr) {
	buf := make([]byte, 1500)
	for {
		n, src, err := c.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if !a.net.Contains(src.IP) {
			continue
		}
		var p dnsmessage.Parser
		h, err := p.Start(buf[:n])
		if err != nil || h.Response {
			continue
		}
		qs, err := p.AllQuestions()
		if err != nil {
			continue
		}
		var want []dnsmessage.Question
		for _, q := range qs {
			if mdnsMatch(q) {
				want = append(want, q)
			}
		}
		if len(want) == 0 {
			continue
		}
		pkt, err := mdnsAnswer(&h, a.ip, false)
		if err != nil {
			continue
		}
		to := mdnsGroup
		if src.Port != 5353 { // legacy unicast query: answer the sender directly
			to = src
		}
		c.WriteToUDP(pkt, to)
	}
}

func mdnsNames() (svc, inst, host string) {
	label := strings.ReplaceAll(cut(srvName, 60), ".", " ")
	h := strings.Map(func(r rune) rune {
		if r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-' {
			return r
		}
		if r >= 'A' && r <= 'Z' {
			return r + 32
		}
		return '-'
	}, label)
	return "_jtv-server._tcp.local.", label + "._jtv-server._tcp.local.", cut(h, 50) + "-jtv.local."
}

func mdnsMatch(q dnsmessage.Question) bool {
	svc, inst, host := mdnsNames()
	n := strings.ToLower(q.Name.String())
	any := q.Type == dnsmessage.TypeALL
	return (n == svc && (q.Type == dnsmessage.TypePTR || any)) ||
		(n == strings.ToLower(inst) && (q.Type == dnsmessage.TypeSRV || q.Type == dnsmessage.TypeTXT || any)) ||
		(n == host && (q.Type == dnsmessage.TypeA || any))
}

// mdnsAnswer always sends the full record set (PTR+SRV+TXT+A): tiny, and saves a second round trip.
func mdnsAnswer(q *dnsmessage.Header, ip net.IP, announce bool) ([]byte, error) {
	svc, inst, host := mdnsNames()
	hdr := dnsmessage.Header{Response: true, Authoritative: true}
	if q != nil {
		hdr.ID = q.ID
	}
	b := dnsmessage.NewBuilder(nil, hdr)
	b.EnableCompression()
	if err := b.StartAnswers(); err != nil {
		return nil, err
	}
	name := func(s string) dnsmessage.Name { n, _ := dnsmessage.NewName(s); return n }
	flush := dnsmessage.ClassINET | 0x8000 // cache-flush: we own these records
	rh := func(n string, c dnsmessage.Class) dnsmessage.ResourceHeader {
		return dnsmessage.ResourceHeader{Name: name(n), Class: c, TTL: mdnsTTL}
	}
	var ip4 [4]byte
	copy(ip4[:], ip.To4())
	b.PTRResource(rh(svc, dnsmessage.ClassINET), dnsmessage.PTRResource{PTR: name(inst)})
	b.SRVResource(rh(inst, flush), dnsmessage.SRVResource{Port: uint16(srvPort), Target: name(host)})
	b.TXTResource(rh(inst, flush), dnsmessage.TXTResource{TXT: []string{"kind=lite", "name=" + srvName, "ver=" + version}})
	b.AResource(rh(host, flush), dnsmessage.AResource{A: ip4})
	return b.Finish()
}
