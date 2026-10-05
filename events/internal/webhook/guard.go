package webhook

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"slices"
	"strings"
	"time"
)

// Guard decides which callback URLs may be called and pins each connection to an address it
// checked, so DNS cannot point a callback at this machine or the local network (SSRF).
type Guard struct {
	// Hosts is the exact-host allowlist.
	Hosts []string
	// AllowPrivate admits loopback and private addresses and any port. Tests only.
	AllowPrivate bool
	// Resolver defaults to net.DefaultResolver.
	Resolver *net.Resolver
	// Transport is the base HTTP transport (for its TLS settings). Tests only.
	Transport *http.Transport
}

// Check validates a callback URL before anything is sent to it.
func (g *Guard) Check(raw string) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, err
	}
	if u.Scheme != "https" || u.User != nil || u.Host == "" {
		return nil, errors.New("callback must be a plain https URL")
	}
	if !g.AllowPrivate && u.Port() != "" && u.Port() != "443" {
		return nil, errors.New("callback must use port 443")
	}
	host := strings.ToLower(u.Hostname())
	if !slices.Contains(g.Hosts, host) {
		return nil, fmt.Errorf("callback host %s is not in callbackHosts", host)
	}
	return u, nil
}

// Client returns an HTTP client that refuses redirects and dials only checked addresses.
func (g *Guard) Client(timeout time.Duration) *http.Client {
	tr := g.Transport
	if tr == nil {
		tr = &http.Transport{TLSHandshakeTimeout: 10 * time.Second, ForceAttemptHTTP2: true}
	} else {
		tr = tr.Clone()
	}
	tr.Proxy = nil
	tr.DialContext = g.dial
	return &http.Client{
		Transport:     tr,
		Timeout:       timeout,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

func (g *Guard) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	resolver := g.Resolver
	if resolver == nil {
		resolver = net.DefaultResolver
	}
	addrs, err := resolver.LookupNetIP(ctx, "ip", host)
	if err != nil {
		return nil, err
	}
	for _, a := range addrs {
		if !g.AllowPrivate && !public(a) {
			return nil, fmt.Errorf("callback host %s resolves to a non-public address", host)
		}
	}
	if len(addrs) == 0 {
		return nil, fmt.Errorf("callback host %s has no address", host)
	}
	d := net.Dialer{Timeout: 10 * time.Second}
	// TLS still verifies the certificate against the original host name: only the dial is pinned.
	return d.DialContext(ctx, network, net.JoinHostPort(addrs[0].Unmap().String(), port))
}

var nonPublic = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),
	netip.MustParsePrefix("100.64.0.0/10"), // CGNAT, also Tailscale
	netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("64:ff9b::/96"),
	netip.MustParsePrefix("fc00::/7"),
}

func public(a netip.Addr) bool {
	a = a.Unmap()
	if a.IsLoopback() || a.IsPrivate() || a.IsLinkLocalUnicast() || a.IsLinkLocalMulticast() ||
		a.IsMulticast() || a.IsUnspecified() || a.IsInterfaceLocalMulticast() {
		return false
	}
	for _, p := range nonPublic {
		if p.Contains(a) {
			return false
		}
	}
	return true
}
